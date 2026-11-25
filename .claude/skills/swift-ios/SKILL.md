---
name: swift-ios
description: How to build AbodeNestly's iOS app under apps/ios/ — Swift 6, SwiftUI, iOS 17+, SQLite (GRDB) per-household local database, the outbox sync loop, Supabase Swift for Auth/Realtime, BGAppRefreshTask, push notifications, barcode scanning, Siri shortcuts and module-gated navigation. Use this for any task touching apps/ios/, a SwiftUI view, a Swift model, the iOS local DB or sync, Xcode project settings, or an App Intent, even if the user only says "the iPhone app" or "the list screen on iOS".
---

# Swift iOS skill

Read `docs/SPEC.md` §3, §4, §6, §9 before starting. Models are **generated** from `docs/openapi.yaml` into `apps/ios/Packages/Contract`; never hand-write a model that exists there.

## Allowed dependencies (Swift Package Manager)
`GRDB.swift` (SQLite), `supabase-swift` (Auth + Realtime only). Nothing else without PR justification. No Alamofire, no Combine wrappers, no third-party UI, no RxSwift. Networking is `URLSession` with `async/await`. JSON is `Codable`.

## Targets and layout
```
apps/ios/
  AbodeNestly.xcodeproj
  AbodeNestly/                 app target (SwiftUI App, scenes, DI container)
  Packages/
    Contract/                  generated models + API client protocol
    Core/                      auth, households, members, invites, settings, activity, search
    Sync/                      a library of the Core package (Packages/Core/Sources/Sync): HouseholdDatabase.swift + Migrations.swift (GRDB), Outbox.swift, SyncEngine.swift, Realtime.swift
    Shopping/                  ← module
    Pantry/                    ← module
    Maintenance/               ← module
    UI/                        shared components, tokens
    Shared/                    UUID, dates, money, NameKey
  AbodeNestlyIntents/          App Intents extension (Siri)
  AbodeNestlyTests/
```
Rule: `Shopping`, `Pantry`, `Maintenance` packages depend on `Core`, `Sync`, `UI`, `Shared`, `Contract` only. Package manifests enforce it: a module package must not list another module as a dependency.

## Architecture
- SwiftUI + `@Observable` view models (Observation framework). No MVVM boilerplate beyond that; no Coordinators.
- Dependencies passed through a single `AppEnvironment` struct injected via `@Environment`.
- Strict concurrency (Swift 6 language mode). Database and sync engine are `actor`s.
- Navigation: `NavigationStack` with a typed `Route` enum per module; tabs live in the app target and are filtered by module flags.

## Local database (GRDB)
- One SQLite file per household: `Application Support/households/<household_id>.sqlite`. `DatabasePool` per open household; switching households closes the pool.
- Tables mirror the server (same names/columns, `snake_case` columns mapped via `CodingKeys`), plus `outbox` and `meta`.
- Migrations in `Sync/Migrations.swift` with `DatabaseMigrator`, append-only, mirroring server migrations.
- `attachment_outbox` (CORE-11) is a second, separate queue for picked files (bytes saved under Application Support, path in the row). `/sync` and `outbox` carry rows, not bytes, so SHOP-08 must not drain it.
- Reads via `ValueObservation` → `AsyncSequence` feeding view models. Views never hit the network to render.

## Writes: always through the outbox
```swift
try await database.upsert(table: "list_item", row: ["id": .string(id), "list_id": .string(list), "name": .string("Milk")])
await syncEngine.kick()
```
- `HouseholdDatabase` (`Sync/Outbox.swift`) has `upsert(table:row:)`, `patch(table:id:fields:localOnly:)`, `remove(table:id:)` (soft delete) and `delta(pantryItemId:delta:reason:)`. Each does the local write and the outbox insert in **one** `pool.write`, strips the server-owned columns (`id`, `household_id`, `created_at`, `updated_at`, `sync_seq`, `name_key`, `share_token`) from `data`, and returns; the caller then kicks. Rows are `[String: JSONValue]` keyed by column. `patch` queues only the changed columns so a concurrent edit of another column is not overwritten.
- Pantry quantity changes update the local row optimistically and enqueue a `delta`.
- Core settings (create household, invites, modules, members) call the API directly and show "needs connection" when offline.

## Sync engine (`LiveSyncEngine` actor, `Sync/LiveSyncEngine.swift`)
- `kick()` coalesces (a kick during a run causes exactly one more); push the outbox (`POST /sync`, up to 500 rows in `seq` order, only the tables the server accepts: `SyncTables.pushable`), then, if the push reached the server, pull (`GET /sync?since=`) until `has_more == false`. `applyPull` writes a page and the cursor in one transaction and ignores unknown tables and columns; a row with a change still queued keeps its local value; `*_at` timestamps are stored as millisecond UTC text. A migration that adds a pulled table must also reset `meta.cursor` to 0.
- The wire types (`SyncMutation`, `SyncPushResult`, `SyncPullPage`) live in `Sync/SyncWire.swift`; `Sync` reaches the network only through `SyncTransport` (`HttpApiService` and `FakeApiService` conform).
- Triggers: `scenePhase == .active`, `NWPathMonitor` becomes satisfied, entering or switching a household, 5-minute timer while active, `BGAppRefreshTask` (`com.abodenestly.sync`, scheduled when backgrounded and after each run, ≥ 15 min; identifier and `fetch` mode are in `Info.plist`), and a Realtime event for the household (SHOP-09-ios).
- Realtime: `supabase.realtimeV2.channel(householdId)` with `postgresChange(AnyAction.self, schema: "public", filter: "household_id=eq.<id>")`; payload ignored, triggers `kick()`.
- Rejected mutations move to `outbox_failed` and show only through the `SyncStatusChip` ("N to sync" counts queued plus failed) and the sync sheet (DESIGN B12), like web: no banner. The sheet's Retry puts the change back at its old `seq` with the same mutation id; Discard drops it and, if nothing else targets the row, the local row too, then resets the cursor and syncs. `rls_denied` gets one automatic retry after a session refresh. Rows queued for tables the server does not accept yet (`notification_pref`, pantry, maintenance) wait, unsent and uncounted.

## Configuration
`Config/Dev.xcconfig` and `Config/Prod.xcconfig` set `API_BASE_URL`, `SUPABASE_URL`, `SUPABASE_ANON_KEY` as Info.plist keys; `Shared/AppConfig` reads them once. Two schemes: `AbodeNestly Dev`, `AbodeNestly Prod`. Never hard-code a URL or key.

## Auth
`supabase-swift` Auth: email magic link (universal link callback), Sign in with Apple (`ASAuthorizationController`), Google via OAuth web flow. Session persisted by the SDK; API client attaches `session.accessToken` and retries once after refresh on 401.

## Platform features
- **Barcode** (SHOP-10, PANT-09): `AVCaptureSession` + `AVCaptureMetadataOutput` (EAN-13, EAN-8, UPC-A). No third-party scanner. Lookup through `GET /products/{barcode}`; cache in local `product` table.
- **Voice add** (SHOP-14): `SFSpeechRecognizer` on-device; parse with `Shared/QuantityParser` (tested: "2 litres milk" → qty 2, unit "l", name "milk").
- **Siri** (SHOP-15): App Intent `AddToShoppingListIntent(item: String, list: ListEntity?)` writes through the outbox in the extension using the shared app group container.
- **Push** (PANT-16, MAINT-08): `UNUserNotificationCenter`; register token with `POST /devices` on launch and on change. Tap routes to the entity via `Route`.
- **QR** (MAINT-14): generate with `CIFilter.qrCodeGenerator`; scan with the same capture pipeline; deep link `abodenestly://asset/<qr_code>`.
- **PDF export** (MAINT-13): download from API, present with `ShareLink`.

## UI conventions
Every screen is built from its plan in `docs/DESIGN.md` (layout, states, interactions, tokens in Part A). Implement all listed states; deviations go into the PR and `docs/DESIGN.md` together.
- SwiftUI system components; Dynamic Type; VoiceOver labels on every control; dark mode via system.
- Destructive actions use undo (5 s) rather than confirm alerts.
- List screens show a small sync status (`idle / syncing / offline / N pending`) in the toolbar.
- Money formatted with `Decimal` + `FormatStyle.Currency`; dates with `Date.FormatStyle`. Never `Double` for money.

## Module gating
`Household.modules` drives: tabs shown, routes allowed, cross-module controls (pantry badge, "Send to list", "Restock"). A disabled module's package is still linked but nothing in it is reachable.

## Testing
`ViewInspector` is the only allowed test-only dependency, for SwiftUI component tests: one case per State and Interaction in the screen's `docs/DESIGN.md` plan (see `docs/TESTING.md` §7). E2E journeys are XCUITest in `AbodeNestlyUITests/` and follow `docs/TESTING.md` §8.

- `XCTest`. GRDB in-memory `DatabaseQueue` for DB tests.
- Required for shopping/pantry stories: perform the action with a stubbed offline `URLProtocol`, assert outbox row, then simulate online and assert the push body.
- Pure logic (aisle sort, quantity parser, eat-soon, due dates) gets table-driven tests.
- UI tests only for sign-in, household switch and the TESTING §8 journeys (local only; CI skips them).

## Checklist before PR
- [ ] No module-to-module package dependency
- [ ] Writes via outbox; no direct network for data
- [ ] Works with module flag off
- [ ] Offline test for shopping/pantry
- [ ] Models from `Contract` package
- [ ] Strict concurrency clean, no `@unchecked Sendable` without comment
- [ ] No new dependency, or justified
- [ ] Feature ID in branch/commit/PR
