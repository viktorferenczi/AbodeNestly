---
name: react-web
description: How to build AbodeNestly's web client under web/ — React 19 + TypeScript + Vite, IndexedDB via Dexie for the per-household local database, the outbox sync loop, Supabase Auth/Realtime, service worker offline shell, and module-gated UI. Use this for any task touching web/, a screen, component, hook, route, the local DB, sync on web, or the share-link page, even if the user only says "add a page" or "the list screen".
---

# React web skill

Read `docs/SPEC.md` §3, §4, §6, §9 before starting. Types come from `packages/contract`; never redeclare them.

## Allowed dependencies
`react`, `react-dom`, `react-router`, `dexie`, `@supabase/supabase-js`. Dev: `vite`, `typescript`, `vitest`, `@testing-library/react`, `eslint`, `fake-indexeddb`. Nothing else without PR justification. No Redux/Zustand/TanStack Query, no UI kit, no CSS-in-JS, no date libraries, no lodash.

## Layout
```
web/src/
  app/            router, providers, household switcher, shell
  core/           auth, households, members, invites, settings, activity, search
  shopping/       ← module
  pantry/         ← module
  maintenance/    ← module
  sync/           db.ts (Dexie schema), outbox.ts, sync.ts, realtime.ts, useSync.ts
  ui/             shared primitives (Button, Sheet, ListRow, Badge, EmptyState)
  lib/            uuid.ts, dates.ts, money.ts, nameKey.ts
```
Rule: `shopping/`, `pantry/`, `maintenance/` never import from each other. They import from `core/`, `sync/`, `ui/`, `lib/` only. ESLint `no-restricted-imports` enforces this.

## Local database (Dexie)
- One Dexie database per household: name `abodenestly-<household_id>`. Opening a household opens its DB; switching closes it.
- Tables mirror the server tables with the same names and columns, plus `outbox` and `meta` (`{ key: 'cursor', value: number }`).
- Indexes: `id` primary, plus what the screens query (`list_id`, `[household_id+expires_at]`, `next_due`).
- Dexie `version()` bumps are append-only and mirror server migrations.

## Writes: always through the outbox
```ts
await db.transaction('rw', db.list_item, db.outbox, async () => {
  await db.list_item.put(row);
  await db.outbox.add(upsertMutation('list_item', row));
});
void sync.kick();
```
- Helper `mutate()` in `sync/outbox.ts` does this for `upsert`, `delete` and `delta`. UI code never calls `fetch` for data writes.
- Pantry quantity: `mutate.delta('pantry_item', id, -1, 'used')` and update the local `quantity` optimistically.
- Core settings (create household, invites, module toggles, members) call the API directly; they require network and show a "needs connection" state when offline.

## Sync loop (`sync/sync.ts`)
- `kick()` coalesces calls; runs push (`POST /sync` with outbox in insertion order) then pull (`GET /sync?since=cursor`) until `has_more` is false.
- Applied mutation ids are removed from the outbox; rejected ones are moved to `outbox_failed` and surfaced in a non-blocking toast, never retried automatically except `rls_denied` after a token refresh.
- Pull applies rows with `bulkPut` per table in one transaction, then writes the cursor.
- Triggers: `visibilitychange` → visible, `online` event, Realtime event for the household, `setInterval` 5 min while visible.
- Realtime: `supabase.channel(household_id).on('postgres_changes', { schema: 'public', filter: `household_id=eq.${id}` }, () => sync.kick())`. Payload ignored.

## Reads: `useLiveQuery` from Dexie
Screens subscribe to the local DB with `useLiveQuery`. No component fetches from the network to render. Derived data (aisle sort, eat-soon, due tasks) is computed in pure functions under the module's `model/` folder with unit tests.

## Aisle order (SHOP-06/07)
```ts
sortItems(items, orderMap, manualPositions): ListItem[]
```
Order: unchecked first; within unchecked, `manual_position` if set, else `orderMap[name_key].mean_position`, else `Infinity` (bottom) then by `created_at`. Checked items after, newest check first. Pure function, tested.

## Module gating
`useHousehold()` exposes `modules: { shopping, pantry, maintenance }`. Routes for a disabled module redirect to `/`. Tabs hide. Cross-module UI (pantry badge on list items, "Send to list" button) renders only when the target module is enabled.

## Offline shell
Service worker (`web/src/sw.ts`, built with Vite's `build.rollupOptions` as a separate entry, no Workbox) precaches the app shell and serves it on navigation when offline. It also listens for `sync` events (Background Sync) to call `sync.kick()` where supported. API responses are never cached by the SW.

## Configuration
`import.meta.env.VITE_API_BASE_URL`, `VITE_SUPABASE_URL`, `VITE_SUPABASE_ANON_KEY` from `.env.development` / `.env.production`. Never hard-code a URL or key.

## Auth
Supabase JS for sign-in (magic link, Apple, Google). Session persisted by the SDK. The API client reads `session.access_token` and refreshes via the SDK on 401 once before failing.

## Share-link page (SHOP-13)
`/shared/:token` is a public route outside the authenticated shell. It calls `GET /shared/{token}` directly, no local DB, no Supabase session required.

## UI conventions
Every screen is built from its plan in `docs/DESIGN.md` (layout, states, interactions, tokens in Part A). Implement all listed states; deviations go into the PR and `docs/DESIGN.md` together.
- Mobile-first; the web app is mostly used on a phone browser.
- Primary actions are one tap; destructive actions need undo (5 s toast) instead of confirm dialogs.
- Empty states explain the module in one sentence and offer the first action.
- CSS modules, design tokens in `ui/tokens.css`, `prefers-color-scheme` dark mode.
- Accessibility: every interactive element keyboard reachable, labels on inputs, `aria-live` for sync status.

## Testing
Playwright is the only allowed e2e dependency (`@playwright/test`, dev). Component tests: one case per State and Interaction in the screen's `docs/DESIGN.md` plan (see `docs/TESTING.md` §7). E2E journeys live in `web/e2e/` and follow `docs/TESTING.md` §8.

- `vitest` + Testing Library + `fake-indexeddb`.
- Required for shopping/pantry stories: a test that performs the action with `navigator.onLine = false`, asserts the outbox row, then simulates reconnect and asserts the push call body.
- Pure model functions get table-driven tests.

## Checklist before PR
- [ ] No cross-module import
- [ ] Writes go through `mutate()`; no direct fetch for data
- [ ] Works with the module flag off
- [ ] Offline test for shopping/pantry
- [ ] Types from `packages/contract`
- [ ] No new dependency, or justified
- [ ] Feature ID in branch/commit/PR
