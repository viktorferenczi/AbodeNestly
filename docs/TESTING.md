# AbodeNestly — Testing Standard

Every story names the tests it requires using the levels below. A PR without them is not done (SPEC §13).

## 1. Test levels

| Level | What it proves | Scope | Runs on |
|---|---|---|---|
| **Unit** | A pure function or small class behaves per spec | No I/O, no UI, no DB | Every push, < 10 s |
| **Component** | One screen or widget renders the right states and emits the right events, with data and sync faked | One view + its view model, fakes for DB/API | Every push |
| **Integration** | Code talks to a real local Postgres/Supabase correctly: RLS, triggers, sync push/pull | API + `supabase start`; client sync engine + real API | Every PR |
| **E2E** | A user journey works end to end on a real build against a dev environment | Real app, dev Supabase project, seeded household | Every PR for touched flows; full suite nightly |
| **Contract** | Clients and API agree | `docs/openapi.yaml` fresh; generated models compile | Every PR |

## 2. Tooling per platform

| Platform | Unit | Component | Integration | E2E |
|---|---|---|---|---|
| API (`api/`) | vitest | — | vitest + `supabase start`, real JWT | Postman-free: vitest HTTP tests against the dev deployment (`api/test/e2e`) |
| DB (`supabase/`) | — | — | pgTAP (`supabase/tests/*.sql`) | — |
| Web (`web/`) | vitest | vitest + Testing Library + `fake-indexeddb` | vitest, sync engine against local API | **Playwright** (`web/e2e`), Chromium + WebKit mobile viewport |
| iOS (`apps/ios/`) | XCTest | XCTest with `ViewInspector` (test-only dependency) + view-model tests with in-memory GRDB | XCTest, sync engine against local API | **XCUITest** (`AbodeNestlyUITests`), iPhone simulator |
| Android (`apps/android/`) | JUnit 5 | Compose UI test (`createComposeRule`) + Robolectric Room | JUnit, sync engine against local API | **Compose UI test on device/emulator** via `androidTest`, Gradle managed devices |

Allowed test-only dependencies beyond the above: none. No Detox, Appium, Maestro, Cypress, Mockito/MockK (hand-write fakes), snapshot libraries.

## 3. Required tests by story layer

| Story suffix | Required |
|---|---|
| `-api` | Unit for logic; integration for every endpoint touched (happy path, auth failure, validation failure); contract check |
| `-db` (migrations/triggers/policies) | pgTAP: policy denies cross-household; trigger fires once; module flag respected; idempotent on replay |
| `-web`, `-ios`, `-android` screen stories | Component tests for every state in the screen's DESIGN.md plan (empty, loading, populated, error, offline); e2e for the primary journey; offline test if the module is shopping/pantry |
| Cross-module (X1–X7) | Integration through sync push; e2e covering both modules on one platform |

## 4. Test naming and IDs

- Test names start with the feature ID: `SHOP-06: sorts unseen items to bottom`.
- E2E journeys are named in the story and in the test file: `e2e/SHOP-04-check-off.spec.ts`.
- One assertion per behaviour; table-driven where inputs vary.

## 5. Fixtures and seeds

- `supabase/seed.sql` provides global data only.
- `test/fixtures/household.sql` (API) and `e2e/seed/` (clients) create: household "Test Home" with owner `owner@test.local` and member `member@test.local`, one store "Test Market", list "Groceries" with 5 items, 8 pantry items (2 expiring soon, 1 below threshold), 1 house, 2 assets, 3 tasks (1 overdue).
- E2E signs in with email magic link against the dev project using Supabase's `generateLink` admin API in a test helper; never with Apple/Google.
- Every e2e run creates its own household and deletes it at the end; no shared mutable state.

## 6. Offline test pattern (shopping and pantry, all clients)

1. Seed and sync so the local DB is populated.
2. Cut network (Playwright `context.setOffline(true)`; iOS stub `URLProtocol` throwing; Android fake `ApiClient` throwing `IOException`).
3. Perform the action through the UI.
4. Assert the local DB row and the outbox row.
5. Restore network, trigger `kick()`.
6. Assert the push request body contains the mutation and the outbox is empty.
7. Assert the server row (integration) or the other platform sees it (e2e, optional).

## 7. Component test pattern

For a screen `X`, the component test file has one test per row of the screen's **States** table in DESIGN.md plus one per **Interaction**. Fakes:
- Web: a `FakeDb` implementing the Dexie tables used + a `FakeSync` recording `kick()`.
- iOS: in-memory `DatabaseQueue` + `FakeSyncEngine` actor.
- Android: Room in-memory + `FakeSyncEngine`.

## 8. E2E journeys (minimum set per platform)

| ID | Journey |
|---|---|
| E2E-CORE-1 | Sign in → create household → invite → second user accepts → both see each other |
| E2E-CORE-2 | Switch between two households; data does not leak |
| E2E-CORE-3 | Toggle pantry off → pantry tab gone, list has no pantry badges |
| E2E-SHOP-1 | Add item, check off, undo, clear checked |
| E2E-SHOP-2 | Go offline, add and check items, go online, second client sees them |
| E2E-SHOP-3 | Assign store, check items in order twice, list re-sorts |
| E2E-SHOP-4 | Share link opens read-only without sign-in |
| E2E-PANT-1 | Add item with threshold, use it below threshold → list line appears |
| E2E-PANT-2 | Check that line off → restock → quantity updated |
| E2E-PANT-3 | Eat-soon shows item expiring in 2 days |
| E2E-MAINT-1 | Add asset, recurring task, mark done → log entry, next due rolled |
| E2E-MAINT-2 | Send consumables to list → items appear with task badge |
| E2E-MAINT-3 | Export PDF downloads a non-empty file |

## 9. CI gates

`lint` → `typecheck` → `unit` → `component` → `contract` → `integration` (with `supabase start`) → `e2e` (touched flows). Nightly: full e2e on all three clients. A red gate blocks merge.
