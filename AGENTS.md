# AGENTS.md — instructions for every AI agent working in this repo

You are working on **AbodeNestly**, a shared household app (shopping, pantry, maintenance). Read this file fully before touching code.

## 1. Read these first, in order

1. `docs/SPEC.md` — product, stack, features, endpoints, types, sync, triggers, schema. **It is the source of truth.** Never contradict it; propose a PR to `docs/SPEC.md` if it must change. Then `docs/DESIGN.md` (screen plans) and `docs/TESTING.md` (required tests) for any client or test work.
2. The skill for the area you are working in (`.claude/skills/<name>/SKILL.md`):
   - `node-backend` — anything under `api/` or `packages/contract/`
   - `sql-supabase` — anything under `supabase/` or `docs/schema.sql`
   - `react-web` — anything under `web/`
   - `swift-ios` — anything under `apps/ios/`
   - `kotlin-android` — anything under `apps/android/`
   - `pr-review` — when asked to review a pull request
3. The `README.md` of the package you are editing, if present.

## 2. Non-negotiable rules

- **Feature IDs everywhere.** Every story, branch, commit and PR references a feature ID from SPEC §4 (`SHOP-06`, `PANT-14`, …). Branch: `feat/<ID>-short-name`, `fix/<ID>-short-name`.
- **Minimal dependencies.** Allowed third-party packages are listed per package in SPEC §3 and the skills. Adding any other dependency requires a one-line justification in the PR description and is a review blocker without it.
- **Contract first.** API request/response shapes come from `packages/contract/src/types.ts`. Swift and Kotlin use the generated models. Never hand-write DTOs.
- **Sync columns are sacred.** `id` is client-generated UUID v4; `updated_at` and `sync_seq` are server-only; soft delete via `deleted_at`. Never add a household-scoped table without them. Never write `pantry_item.quantity` directly; use a movement/delta.
- **Modules stay independent.** Module code never imports another module's code. Cross-module behaviour is only what SPEC §9 lists. If you need new coupling, update SPEC §9 first.
- **Module flags.** Every module feature must be hidden and harmless when its flag is off.
- **Offline is a requirement, not a feature.** Shopping and pantry writes go through the local DB + outbox. A feature that only works online in those modules is incomplete.
- **RLS always on.** Every new table gets `call setup_scoped('<table>')` or explicit policies. The API forwards the user's JWT to Postgres so RLS applies there too.
- **No secrets in code.** Use environment variables; document new ones in `api/README.md`.

## 3. How to work

1. Restate the story's feature ID and acceptance criteria before coding.
2. Check whether the change touches the schema. If yes, write a migration in `supabase/migrations/` **and** update `docs/schema.sql` and SPEC §14 in the same PR.
3. Check whether the change touches the API contract. If yes, update `packages/contract` first, regenerate `docs/openapi.yaml`, then the clients.
4. Write the smallest change that satisfies the acceptance criteria. No speculative abstractions, no "while I'm here" refactors.
5. Tests: exactly what the story's "Tests required" section lists, which follows `docs/TESTING.md`. Component tests cover every State and Interaction in the screen's DESIGN.md plan; screen stories have an e2e journey.
6. Run the package's lint, typecheck and tests before declaring done.
7. PR description template:
   ```
   ## <ID> <title>
   What: …
   Why: …
   Schema change: yes/no (migration file)
   Contract change: yes/no
   New dependencies: none | <name> — <justification>
   Offline tested: yes/no/n.a.
   Module-off tested: yes/no/n.a.
   ```

## 4. Coding conventions (all languages)

- Names: tables and columns `snake_case`; TS/Kotlin/Swift `camelCase` for members, `PascalCase` for types; files named after their main export.
- Errors are values with a code (`ApiError` in TS, sealed classes in Kotlin, enums in Swift). No stringly-typed error handling.
- Dates: ISO 8601 strings over the wire, native date types in memory, UTC on the server. `done_at`, `expires_at`, `next_due` are **dates** (no time).
- Money: `numeric` in DB, `Decimal`/`BigDecimal`/`Decimal` in clients. Never float.
- Comments explain *why*, not *what*. No commented-out code.
- Commit messages: `<ID>: imperative summary` (`SHOP-06: sort list by learned store order`).

## 5. When unsure

Ask one precise question in the PR or story thread rather than guessing. If you must proceed, state the assumption at the top of the PR description.

## 6. For story-writing agents

Read `docs/SPEC.md`, `docs/DESIGN.md`, `docs/TESTING.md` and `docs/STORY-TEMPLATE.md` fully before writing a single story. Then:
- One story per feature ID per layer: `-db` (migration/trigger/policy work, if any), `-api`, `-web`, `-ios`, `-android`. Skip a layer only if the feature has nothing in it; say so in the parent story.
- Use the template verbatim. Every section present. Given/When/Then acceptance criteria, minimum 4 for screen stories, 3 for api/db, each mapped to a test in "Tests required".
- Screen stories list every State and Interaction from the screen's DESIGN.md plan they implement, and name the component test cases for each.
- Name the e2e journey from TESTING §8 (or propose a new `E2E-<MODULE>-n` and add it to the story).
- Shopping and pantry client stories include the offline test (TESTING §6).
- Cross-module stories (SPEC §9 X1–X7) are separate stories named `X1-<layer>` with both modules in scope and the module-off matrix as acceptance criteria.
- Order: CORE → SHOP → PANT → MAINT; within an ID: db → api → web → ios → android. Set "Blocked by" accordingly.
- Do not invent features not in SPEC §4. Put ambiguity in "Open questions" with a proposed default; never guess silently.
- Output one Markdown file per module into `docs/stories/` (`core.md`, `shopping.md`, `pantry.md`, `maintenance.md`, `cross-module.md`) **and** create the GitHub issues with labels `module:<name>`, `layer:<name>`, `id:<ID>`.
