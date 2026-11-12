# AbodeNestly — Product & Technical Specification

> Single source of truth for every agent (Claude, Cursor, Grok, humans).
> If code and this document disagree, this document wins until it is amended by a PR.
> Version 1.0 — 2026-11-08

---

## Table of contents

1. [What AbodeNestly is](#1-what-abodenestly-is)
2. [Principles](#2-principles)
3. [Tech stack](#3-tech-stack)
4. [Modules and feature list (v1)](#4-modules-and-feature-list-v1)
5. [Domain model](#5-domain-model)
6. [Sync protocol and offline](#6-sync-protocol-and-offline)
7. [API endpoints](#7-api-endpoints)
8. [TypeScript types](#8-typescript-types)
9. [Cross-module rules and triggers](#9-cross-module-rules-and-triggers)
10. [Notifications](#10-notifications)
11. [Security and RLS](#11-security-and-rls)
12. [Repository layout](#12-repository-layout)
13. [Definition of done](#13-definition-of-done)
14. [SQL schema](#14-sql-schema)

---

## 1. What AbodeNestly is

AbodeNestly is a shared household app. A **household** is a group of people living together; everything in the app belongs to a household and is visible to all its members in real time. A user can belong to several households (own home, parents' house, holiday flat) and switches between them.

It has three **modules**. Each works on its own; together they feed each other.

| Module | One line | Primary cadence |
|---|---|---|
| **Shopping** | Shopping lists that learn each store's aisle order and refill themselves from the pantry | Weekly, used in-store, offline |
| **Pantry** | What the household has in the fridge, freezer and cupboards, with expiry and low-stock tracking | Daily/weekly |
| **Maintenance** | The home's assets, recurring upkeep tasks, and a log of what was done, by whom, at what cost | Monthly/seasonal |

Every module can be switched off per household. Switching a module off hides its UI and disables its cross-module hooks; it never breaks the other two.

Clients: iOS (Swift/SwiftUI), Android (Kotlin/Jetpack Compose), Web (React). Backend: Node/TypeScript on Vercel + Supabase (Postgres, Auth, Realtime, Storage).

---

## 2. Principles

1. **Server is the source of truth; clients are caches.** Clients keep a local database, write to it first, and sync. The server resolves conflicts.
2. **Modules are independent.** Module A never imports module B's code. Cross-module behaviour lives in Postgres triggers or in the sync layer, gated by the household's module flags.
3. **Offline-first where it matters.** Shopping and pantry are fully usable offline. Maintenance is readable offline and writable offline for log entries.
4. **Minimal dependencies.** Prefer platform SDKs and the standard library. Every new third-party dependency needs a one-line justification in the PR.
5. **One contract, four codebases.** The API contract (`docs/openapi.yaml`, generated from the backend) and the types in §8 are the interface. Clients generate from it; they never hand-write request/response shapes.
6. **Boring conflict resolution.** Last-write-wins per row, except quantities, which are deltas.

---

## 3. Tech stack

### Backend
- **Runtime**: Node 22, TypeScript 5 (strict). Vercel Serverless Functions, **Node runtime** (not Edge).
- **HTTP**: Vercel's native handler signature (`(req: Request) => Response`). No Express/Fastify.
- **Database access**: `pg` (node-postgres) with plain SQL. No ORM. Connect through the **Supabase pooler in transaction mode** (port 6543).
- **Auth**: Supabase Auth. API verifies the Supabase JWT with `jose` (one dependency) using the project JWKS; `auth.uid()` in RLS and `sub` in the API are the same user id.
- **Validation**: hand-written type guards (see §8). No zod/yup/io-ts.
- **Recurrence**: `rrule` (one dependency) for maintenance task rolling. Nothing else.
- **Push**: raw HTTPS to APNs (HTTP/2, token auth) and FCM v1; Web Push via `web-push` (one dependency).
- **Allowed backend dependencies**: `pg`, `jose`, `rrule`, `web-push`. Everything else needs a PR justification.

### Database
- Supabase Postgres 17. Schema in `docs/schema.sql` (reproduced in §14). Migrations in `supabase/migrations/` managed with the Supabase CLI.
- Row Level Security on every table. Realtime publication on every household-scoped table.
- `pg_cron` for scheduled jobs.

### Storage
- Supabase Storage, bucket `households`, path `households/{household_id}/{entity}/{entity_id}/{filename}`. Clients upload with signed URLs obtained from the API.

### Clients
- **iOS**: Swift 6, SwiftUI, iOS 17+. Local DB: **SQLite via GRDB** (one dependency). Networking: `URLSession`. Supabase Swift SDK for Auth and Realtime only.
- **Android**: Kotlin 2, Jetpack Compose, minSdk 26. Local DB: **Room**. Networking: `OkHttp` + `kotlinx.serialization`. Supabase Kotlin SDK for Auth and Realtime only. Background sync: WorkManager.
- **Web**: React 19, TypeScript, Vite. Local DB: **IndexedDB via Dexie** (one dependency). Routing: React Router. State: React context + reducers, no Redux/Zustand. Styling: CSS modules. Supabase JS SDK for Auth and Realtime only. Service Worker for offline shell and background sync.

### Environments
Two environments, `dev` and `prod`, each with its own Supabase project and Vercel deployment. All URLs and keys come from environment files, never from code:

| Package | Files | Variables |
|---|---|---|
| `api/` | `.env.development`, `.env.production` (Vercel env vars in the dashboard; local via `vercel env pull`) | `DATABASE_URL`, `SUPABASE_URL`, `SUPABASE_JWKS_URL`, `SUPABASE_SERVICE_ROLE_KEY`, `API_BASE_URL`, `CRON_SECRET`, push keys |
| `web/` | `.env.development`, `.env.production` (Vite) | `VITE_API_BASE_URL`, `VITE_SUPABASE_URL`, `VITE_SUPABASE_ANON_KEY`, `VITE_VAPID_PUBLIC_KEY` |
| `apps/ios/` | `Config/Dev.xcconfig`, `Config/Prod.xcconfig` → `Info.plist` keys, read via `Bundle.main.infoDictionary` | `API_BASE_URL`, `SUPABASE_URL`, `SUPABASE_ANON_KEY` |
| `apps/android/` | `app/src/dev/`, `app/src/prod/` product flavors reading `env/dev.properties`, `env/prod.properties` into `BuildConfig` | `API_BASE_URL`, `SUPABASE_URL`, `SUPABASE_ANON_KEY` |

`.env.development` and `*.example` files are committed; `.env.production`, `Prod.xcconfig` and `prod.properties` are git-ignored and provided by CI secrets.

### Tooling
- Monorepo with pnpm workspaces for `api/`, `web/`, `packages/contract/`. Native apps live in `apps/ios` and `apps/android` and consume generated types.
- OpenAPI 3.1 spec generated from backend route definitions; Swift and Kotlin models generated with `openapi-generator` in CI.
- CI: GitHub Actions. Lint, typecheck, unit tests, schema diff check, contract check.

---

## 4. Modules and feature list (v1)

All features below are **in scope for v1**. IDs are stable and must be referenced in stories, branches and PR titles (e.g. `SHOP-07`).

### 4.1 Core (household)

| ID | Feature | Acceptance summary |
|---|---|---|
| CORE-01 | Sign in with email magic link, Apple, Google | Supabase Auth; `profile` row created by the `on_auth_user_created` trigger on `auth.users` (display name from provider metadata, else email local part) |
| CORE-02 | Create household | Creator becomes owner; default list "Groceries" and default locations (Fridge, Freezer, Pantry) are created |
| CORE-03 | Invite by link/code | Invite has expiry and max uses; accepting joins with the invite's role (member by default) |
| CORE-04 | Member list, roles (owner/member), remove member, leave household | Owner can remove; last owner cannot leave |
| CORE-05 | Module toggles per household | Owner toggles shopping/pantry/maintenance; UI hides tab, triggers respect flag |
| CORE-06 | Profile (name, avatar) | Avatar via Storage |
| CORE-07 | Multiple households per user | Household switcher; each household has its own local DB and sync cursor |
| CORE-08 | Activity feed | "Anna checked off Milk", per household, last 30 days |
| CORE-09 | Household-wide search | Searches names across all enabled modules, local DB only |
| CORE-10 | Soft delete with 30-day restore | "Recently deleted" screen per module |
| CORE-11 | Attachments | Photos and PDFs on pantry items, assets, log entries; signed upload |
| CORE-12 | Notification preferences | Per household, per type |

### 4.2 Shopping

| ID | Feature | Acceptance summary |
|---|---|---|
| SHOP-01 | Multiple lists | Create, rename, archive, delete; default list per household |
| SHOP-02 | Add item with autocomplete | Suggestions from the household's item history (`name_key`) |
| SHOP-03 | Quantity, unit, note per item | Optional fields |
| SHOP-04 | Check off with undo; checked items collapse to bottom; clear checked | Undo within 5 s |
| SHOP-05 | Stores | Create stores; assign a list to a store |
| SHOP-06 | Learned aisle order | Items sorted by `store_item_order.mean_position` for the list's store; unseen items at bottom |
| SHOP-07 | Manual drag-to-reorder | Sets `manual_position`; also teaches the learned order |
| SHOP-08 | Full offline with queued sync | All list operations work with no network; sync on reconnect |
| SHOP-09 | Realtime check-offs across members | Via Supabase Realtime → pull |
| SHOP-10 | Barcode scan to add | Camera scan → `GET /products/{barcode}` → item name |
| SHOP-11 | Template lists | Mark list as template; "Add all to list" in one tap |
| SHOP-12 | Price memory per store | Enter price on check-off; show last price next to item |
| SHOP-13 | Read-only share link | `POST /lists/{id}/share` returns public URL; web renders without auth |
| SHOP-14 | Voice add | Platform speech-to-text, then parse "2 litres milk" into name/quantity/unit |
| SHOP-15 | Siri / Google Assistant shortcuts | "Add X to my shopping list" |
| SHOP-16 | Pantry-sourced items | Badge "from pantry"; checking off offers "Restock pantry" |
| SHOP-17 | Maintenance consumables | Items sourced from a task show "for: Boiler service" |

### 4.3 Pantry

| ID | Feature | Acceptance summary |
|---|---|---|
| PANT-01 | Locations (fridge, freezer, pantry, custom) | CRUD |
| PANT-02 | Items with name, qty, unit, location, category, added date, expiry, note | CRUD |
| PANT-03 | Quick +/- quantity from list view | Writes a `pantry_movement` delta |
| PANT-04 | Low-stock threshold and auto-add toggle | Per item |
| PANT-05 | "Eat soon" view | Items expiring within 7 days, sorted |
| PANT-06 | Search and filter by location/category | Local |
| PANT-07 | Usage history | Movements per item |
| PANT-08 | Categories with default shelf life | Suggest expiry when adding |
| PANT-09 | Barcode scan with product lookup | Same endpoint as SHOP-10 |
| PANT-10 | Photo of item | Attachment |
| PANT-11 | Usage stats | Average consumption per week per item, computed from movements |
| PANT-12 | "What can I make" | Recipes whose ingredients are all in stock |
| PANT-13 | Unit conversion | Household conversion table; display in preferred unit |
| PANT-14 | Low stock → shopping list | Trigger, see §9 |
| PANT-15 | Restock from checked-off list item | Creates `restocked` movement |
| PANT-16 | Eat-soon push notifications | Daily, see §10 |

### 4.4 Maintenance

| ID | Feature | Acceptance summary |
|---|---|---|
| MAINT-01 | House profile | Name, address, build year, notes |
| MAINT-02 | Assets | Kind, brand, model, serial, room, purchase date/price, warranty until, notes, attachments |
| MAINT-03 | Recurring tasks (RRULE) with next due | Rolls forward on completion |
| MAINT-04 | One-off tasks | `rrule` null |
| MAINT-05 | Mark done → log entry | Prompts for date, who, contractor, cost, notes, photos |
| MAINT-06 | Log entry CRUD | |
| MAINT-07 | Due / overdue view | |
| MAINT-08 | Push reminders | Daily, see §10 |
| MAINT-09 | Contractor contacts | Name, trade, phone, email |
| MAINT-10 | Cost reports | Per year, per asset |
| MAINT-11 | Warranty expiry reminders | 30 days before |
| MAINT-12 | Seasonal checklist templates | Global + household templates; apply creates tasks |
| MAINT-13 | Export full log to PDF | `GET /export/maintenance.pdf` |
| MAINT-14 | Asset QR labels | Print QR; scanning opens the asset |
| MAINT-15 | Assign task to member | Reminder goes to assignee only |
| MAINT-16 | Task consumables → shopping list | "Send to list" button, see §9 |

---

## 5. Domain model

Full DDL in §14. Summary of tables by module:

**Core**: `profile`, `household`, `member`, `invite`, `activity`, `attachment`, `device_token`, `notification_pref`, `notification`, `product` (global barcode cache).

**Shopping**: `store`, `shopping_list`, `list_item`, `store_item_order`, `item_price`.

**Pantry**: `location`, `category`, `pantry_item`, `pantry_movement`, `unit_conversion`, `recipe`, `recipe_ingredient`.

**Maintenance**: `house`, `contractor`, `asset`, `maint_task`, `task_consumable`, `maint_log`, `checklist_template`, `checklist_template_item`.

### Standard columns on every household-scoped table

| Column | Type | Rule |
|---|---|---|
| `id` | uuid | Primary key. **Clients generate it** (UUID v4) so offline creates need no round trip. |
| `household_id` | uuid | Every row belongs to exactly one household. RLS keys on it. |
| `created_at` | timestamptz | Server default. |
| `updated_at` | timestamptz | **Set by the server trigger**, never trusted from the client. |
| `deleted_at` | timestamptz | Soft delete. `null` = live. Purged after 30 days by cron. |
| `sync_seq` | bigint | Global monotonic sequence, set by the server trigger on every insert/update. Clients pull rows with `sync_seq > cursor`. |

### Item identity
Free-text names are normalised with `name_key(text)` = lower, trimmed, whitespace collapsed. `list_item.name_key`, `pantry_item.name_key`, `recipe_ingredient.name_key` are generated columns. Autocomplete, aisle learning, price memory and pantry↔list matching all use `name_key`; the UI always shows `name`.

### Quantities
`pantry_item.quantity` is never set directly by clients. Clients insert a `pantry_movement` with a `delta`; a trigger applies it. This makes concurrent offline edits additive instead of lossy.

---

## 6. Sync protocol and offline

### 6.1 Client-side model
- One local database **per household** (iOS: SQLite file per household; Android: Room DB per household; Web: Dexie DB per household).
- Local schema mirrors the server tables 1:1 plus one table `outbox`.
- Reads always hit the local DB. UI never waits for the network.
- Writes: apply to local DB **and** append a mutation to `outbox` in one local transaction.
- A sync cursor (`last_seq: bigint`) is stored per household.

### 6.2 Mutation envelope

```jsonc
{
  "id": "uuid",                 // mutation id, client-generated, for idempotency
  "table": "list_item",         // one of the household-scoped tables
  "op": "upsert" | "delete" | "delta",
  "row_id": "uuid",
  "data": { ... },              // upsert: full row minus server columns; delta: { "field": "quantity", "delta": -1, "reason": "used" }
  "client_ts": "2026-11-08T10:12:00Z"
}
```

- `upsert`: the server merges `data` into the row (insert if missing). Conflict: **last write wins per row**, decided by the server's receive order, not `client_ts`. `client_ts` is informational only.
- `delete`: sets `deleted_at = now()`.
- `delta`: allowed only on `pantry_item.quantity`. The server inserts a `pantry_movement`; the trigger applies it. Never lost on conflict.

### 6.3 Endpoints

**Pull** — `GET /sync?household_id=…&since=<last_seq>&limit=500`

Response:
```jsonc
{
  "cursor": 1872233,            // new last_seq
  "has_more": false,
  "changes": {
    "list_item": [ { ...row } ],  // full rows, including deleted ones (deleted_at set)
    "pantry_item": [ ... ],
    "member": [ ... ]
    // every household-scoped table may appear
  }
}
```
Clients upsert every row by `id` (including deleted ones), then store `cursor`. If `has_more`, call again immediately.

**Push** — `POST /sync`
```jsonc
{ "household_id": "…", "mutations": [ ...envelopes ] }
```
Response:
```jsonc
{
  "applied": ["mut-id", ...],
  "rejected": [ { "id": "mut-id", "reason": "rls_denied" | "validation" | "unknown_table", "detail": "…" } ]
}
```
Mutations are applied in order inside one transaction per push. Applied mutation ids are remembered for 24 h so a retried push is idempotent. After a successful push the client pulls.

### 6.4 Sync loop
1. On app foreground, on network regained, on a Realtime event for the household, and every 5 minutes while in foreground: **push outbox, then pull**.
2. Android additionally runs a WorkManager periodic job (15 min) when the app is backgrounded; iOS uses `BGAppRefreshTask`.
3. Realtime: subscribe to `postgres_changes` filtered by `household_id` on every published table. The event payload is ignored; it only triggers a pull. This keeps one code path.

### 6.5 Offline guarantees
| Area | Read offline | Write offline |
|---|---|---|
| Shopping | yes | yes, everything |
| Pantry | yes | yes, everything |
| Maintenance | yes | create/edit log entries and tasks; attachments queue until online |
| Core | yes | profile edits; household/member changes require network |

---

## 7. API endpoints

Base URL comes from the environment (see §3 Environments); never hard-code it. All requests carry `Authorization: Bearer <supabase-jwt>` except those marked **public**. All bodies are JSON. Errors: `{ "error": { "code": string, "message": string } }` with appropriate HTTP status.

Plain CRUD goes **through Supabase directly** from the clients (RLS enforces access). The API exists for the operations below.

| Method | Path | Purpose | Body / Query | Response |
|---|---|---|---|---|
| GET | `/sync` | Pull changes | `household_id`, `since`, `limit?` | `SyncPullResponse` |
| POST | `/sync` | Push mutations | `SyncPushRequest` | `SyncPushResponse` |
| POST | `/households` | Create household + defaults | `{ name }` | `Household` |
| POST | `/households/{id}/invites` | Create invite | `{ role?, expires_in_days?, max_uses? }` | `Invite` |
| POST | `/invites/{code}/accept` | Join household | — | `{ household_id }` |
| DELETE | `/households/{id}/members/{user_id}` | Remove member / leave | — | `204` |
| PATCH | `/households/{id}/modules` | Toggle modules (owner) | `{ shopping?, pantry?, maintenance? }` | `Household` |
| POST | `/attachments/sign` | Signed upload URL | `{ household_id, entity, entity_id, filename, mime, size }` | `{ attachment_id, upload_url, storage_path }` |
| GET | `/products/{barcode}` | Barcode lookup (cache → Open Food Facts) | — | `Product \| 404` |
| POST | `/tasks/{id}/done` | Complete maintenance task | `MaintLogInput` | `{ log: MaintLog, task: MaintTask }` (next_due rolled) |
| POST | `/tasks/{id}/consumables-to-list` | Send consumables to a list | `{ list_id }` | `ListItem[]` |
| POST | `/lists/{id}/share` | Create/rotate read-only share link | — | `{ url }` |
| DELETE | `/lists/{id}/share` | Revoke share link | — | `204` |
| GET | `/shared/{token}` **public** | Read-only list view data | — | `{ list: ShoppingList, items: ListItem[] }` |
| POST | `/templates/{id}/apply` | Create tasks from checklist template | `{ house_id? }` | `MaintTask[]` |
| GET | `/export/maintenance.pdf` | Export maintenance log | `household_id`, `from?`, `to?` | `application/pdf` |
| GET | `/reports/maintenance-costs` | Cost report | `household_id`, `year?`, `asset_id?` | `CostReport` |
| GET | `/pantry/usage` | Consumption stats | `household_id` | `UsageStat[]` |
| GET | `/pantry/can-make` | Recipes fully in stock | `household_id` | `Recipe[]` |
| POST | `/devices` | Register push token | `{ platform, token }` | `204` |
| DELETE | `/devices/{token}` | Unregister | — | `204` |
| POST | `/internal/cron/deliver-notifications` | Vercel cron; sends unsent `notification` rows | header `x-cron-secret` | `{ sent: number }` |
| GET | `/health` **public** | Liveness | — | `{ ok: true }` |

### Direct-to-Supabase operations (no API)
Reading any household table, CRUD on `store`, `shopping_list`, `location`, `category`, `recipe*`, `house`, `contractor`, `asset`, `maint_task`, `task_consumable`, `unit_conversion`, `notification_pref`, marking `notification.read_at`. Clients may do these through the Supabase SDK when online, **but the canonical write path is the outbox + `POST /sync`** so that offline and online behave identically. Direct SDK writes are allowed only for core settings screens that require network anyway.

---

## 8. TypeScript types

`packages/contract/src/types.ts`. Hand-written, zero dependencies. Swift and Kotlin models are generated from the OpenAPI spec which is generated from these.

```ts
// ---------- primitives ----------
export type UUID = string;            // RFC 4122 v4
export type ISODateTime = string;     // "2026-11-08T10:12:00.000Z"
export type ISODate = string;         // "2026-11-08"

export type Role = 'owner' | 'member';
export type Platform = 'ios' | 'android' | 'web';
export type LocationKind = 'fridge' | 'freezer' | 'pantry' | 'other';
export type ListItemSource = 'manual' | 'pantry' | 'maintenance' | 'template' | 'recipe';
export type MovementReason = 'added' | 'used' | 'restocked' | 'discarded' | 'adjusted';
export type Season = 'spring' | 'summer' | 'autumn' | 'winter';
export type NotificationType = 'eat_soon' | 'low_stock' | 'task_due' | 'warranty' | 'activity';
export type Module = 'shopping' | 'pantry' | 'maintenance';

/** Columns present on every household-scoped row. Server-owned fields are readonly. */
export interface Synced {
  id: UUID;
  household_id: UUID;
  readonly created_at: ISODateTime;
  readonly updated_at: ISODateTime;
  deleted_at: ISODateTime | null;
  readonly sync_seq: number;
}

// ---------- core ----------
export interface Profile {
  id: UUID;
  display_name: string;
  avatar_path: string | null;
  readonly created_at: ISODateTime;
  readonly updated_at: ISODateTime;
}

export interface Household extends Omit<Synced, 'household_id'> {
  name: string;
  created_by: UUID;
  module_shopping: boolean;
  module_pantry: boolean;
  module_maintenance: boolean;
  currency: string; // ISO 4217
}

export type CreateHouseholdRequest = { name: string };

export interface Member {
  household_id: UUID;
  user_id: UUID;
  role: Role;
  joined_at: ISODateTime;
  left_at: ISODateTime | null;
  readonly updated_at: ISODateTime;
  readonly sync_seq: number;
}

export interface Invite extends Synced {
  code: string;
  role: Role;
  created_by: UUID;
  expires_at: ISODateTime;
  max_uses: number;
  uses: number;
}

export interface Activity extends Synced {
  actor_id: UUID | null;
  module: Module | 'core';
  entity: string;
  entity_id: UUID;
  action: string;
  summary: string;
}

export interface Attachment extends Synced {
  entity: string;
  entity_id: UUID;
  storage_path: string;
  mime: string;
  size_bytes: number;
  created_by: UUID | null;
}

export interface DeviceToken {
  id: UUID;
  user_id: UUID;
  platform: Platform;
  token: string;
}

export interface NotificationPref {
  user_id: UUID;
  household_id: UUID;
  type: NotificationType;
  enabled: boolean;
}

export interface Notification {
  id: UUID;
  household_id: UUID;
  user_id: UUID;
  type: NotificationType;
  title: string;
  body: string;
  data: Record<string, string>;
  created_at: ISODateTime;
  sent_at: ISODateTime | null;
  read_at: ISODateTime | null;
}

export interface Product {
  barcode: string;
  name: string;
  brand: string | null;
  category: string | null;
  image_url: string | null;
  default_unit: string | null;
}

// ---------- shopping ----------
export interface Store extends Synced {
  name: string;
}

export interface ShoppingList extends Synced {
  name: string;
  store_id: UUID | null;
  is_template: boolean;
  share_token: string | null;
  archived_at: ISODateTime | null;
}

export interface ListItem extends Synced {
  list_id: UUID;
  name: string;
  readonly name_key: string;
  quantity: number | null;
  unit: string | null;
  note: string | null;
  barcode: string | null;
  checked: boolean;
  checked_at: ISODateTime | null;
  checked_by: UUID | null;
  manual_position: number | null;
  source: ListItemSource;
  pantry_item_id: UUID | null;
  maint_task_id: UUID | null;
  created_by: UUID | null;
}

export interface StoreItemOrder {
  household_id: UUID;
  store_id: UUID;
  name_key: string;
  mean_position: number;
  samples: number;
  readonly sync_seq: number;
}

export interface ItemPrice extends Synced {
  store_id: UUID;
  name_key: string;
  price: number;
  seen_at: ISODateTime;
}

// ---------- pantry ----------
export interface Location extends Synced {
  name: string;
  kind: LocationKind;
}

export interface Category extends Synced {
  name: string;
  default_shelf_days: number | null;
}

export interface PantryItem extends Synced {
  location_id: UUID | null;
  category_id: UUID | null;
  name: string;
  readonly name_key: string;
  readonly quantity: number;        // changed only via PantryMovement
  unit: string | null;
  low_threshold: number | null;
  auto_add: boolean;
  barcode: string | null;
  added_at: ISODate;
  expires_at: ISODate | null;
  note: string | null;
}

export interface PantryMovement extends Synced {
  pantry_item_id: UUID;
  delta: number;
  reason: MovementReason;
  actor_id: UUID | null;
}

export interface UnitConversion {
  household_id: UUID;
  from_unit: string;
  to_unit: string;
  factor: number;
}

export interface Recipe extends Synced {
  name: string;
  instructions: string | null;
}

export interface RecipeIngredient extends Synced {
  recipe_id: UUID;
  name: string;
  readonly name_key: string;
  quantity: number | null;
  unit: string | null;
}

// ---------- maintenance ----------
export interface House extends Synced {
  name: string;
  address: string | null;
  build_year: number | null;
  notes: string | null;
}

export interface Contractor extends Synced {
  name: string;
  trade: string | null;
  phone: string | null;
  email: string | null;
  notes: string | null;
}

export interface Asset extends Synced {
  house_id: UUID | null;
  name: string;
  kind: string | null;
  brand: string | null;
  model: string | null;
  serial: string | null;
  room: string | null;
  purchase_date: ISODate | null;
  purchase_price: number | null;
  warranty_until: ISODate | null;
  qr_code: string;
  notes: string | null;
}

export interface MaintTask extends Synced {
  house_id: UUID | null;
  asset_id: UUID | null;
  title: string;
  description: string | null;
  rrule: string | null;             // RFC 5545 RRULE value, e.g. "FREQ=MONTHLY;INTERVAL=3"
  next_due: ISODate | null;
  assigned_to: UUID | null;
  season: Season | null;
}

export interface TaskConsumable extends Synced {
  task_id: UUID;
  name: string;
  quantity: number | null;
  unit: string | null;
}

export interface MaintLog extends Synced {
  task_id: UUID | null;
  asset_id: UUID | null;
  done_at: ISODate;
  done_by: UUID | null;
  contractor_id: UUID | null;
  cost: number | null;
  notes: string | null;
}

export interface ChecklistTemplate {
  id: UUID;
  household_id: UUID | null;        // null = global template
  name: string;
  season: Season | null;
}

export interface ChecklistTemplateItem {
  id: UUID;
  household_id: UUID | null;
  template_id: UUID;
  title: string;
  rrule: string | null;
}

// ---------- sync ----------
export type SyncTable =
  | 'household' | 'member' | 'invite' | 'activity' | 'attachment'
  | 'store' | 'shopping_list' | 'list_item' | 'store_item_order' | 'item_price'
  | 'location' | 'category' | 'pantry_item' | 'pantry_movement'
  | 'unit_conversion' | 'recipe' | 'recipe_ingredient'
  | 'house' | 'contractor' | 'asset' | 'maint_task' | 'task_consumable' | 'maint_log';

export interface MutationUpsert {
  id: UUID;
  table: SyncTable;
  op: 'upsert';
  row_id: UUID;
  data: Record<string, unknown>;    // row fields minus server-owned columns
  client_ts: ISODateTime;
}
export interface MutationDelete {
  id: UUID;
  table: SyncTable;
  op: 'delete';
  row_id: UUID;
  client_ts: ISODateTime;
}
export interface MutationDelta {
  id: UUID;
  table: 'pantry_item';
  op: 'delta';
  row_id: UUID;
  data: { field: 'quantity'; delta: number; reason: MovementReason };
  client_ts: ISODateTime;
}
export type Mutation = MutationUpsert | MutationDelete | MutationDelta;

export interface SyncPushRequest {
  household_id: UUID;
  mutations: Mutation[];
}
export interface SyncRejection {
  id: UUID;
  reason: 'rls_denied' | 'validation' | 'unknown_table' | 'module_disabled';
  detail: string;
}
export interface SyncPushResponse {
  applied: UUID[];
  rejected: SyncRejection[];
}
export interface SyncPullResponse {
  cursor: number;
  has_more: boolean;
  changes: Partial<Record<SyncTable, unknown[]>>;
}

// ---------- API payloads ----------
export interface MaintLogInput {
  done_at: ISODate;
  done_by?: UUID | null;
  contractor_id?: UUID | null;
  cost?: number | null;
  notes?: string | null;
}
export interface CostReport {
  year: number;
  total: number;
  currency: string;
  by_asset: Array<{ asset_id: UUID | null; asset_name: string; total: number }>;
  by_month: Array<{ month: number; total: number }>;
}
export interface UsageStat {
  name_key: string;
  name: string;
  unit: string | null;
  per_week: number;
  last_restocked: ISODate | null;
}
export interface ApiError {
  error: { code: string; message: string };
}

// ---------- minimal runtime guards (no library) ----------
export const isUUID = (v: unknown): v is UUID =>
  typeof v === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(v);

export const isMutation = (v: unknown): v is Mutation => {
  if (typeof v !== 'object' || v === null) return false;
  const m = v as Record<string, unknown>;
  if (!isUUID(m.id) || !isUUID(m.row_id) || typeof m.table !== 'string') return false;
  if (m.op === 'delete') return true;
  if (m.op === 'upsert') return typeof m.data === 'object' && m.data !== null;
  if (m.op === 'delta') {
    const d = m.data as Record<string, unknown> | undefined;
    return m.table === 'pantry_item' && !!d && d.field === 'quantity' && typeof d.delta === 'number';
  }
  return false;
};
```

---

## 9. Cross-module rules and triggers

All cross-module behaviour is listed here. Nothing else may couple modules.

| # | From → To | Mechanism | Gate | Behaviour |
|---|---|---|---|---|
| X1 | Pantry → Shopping | Postgres trigger `pantry_item_low_stock` (AFTER INSERT/UPDATE OF quantity, low_threshold) | `household.module_shopping = true`, `pantry_item.auto_add = true`, `low_threshold` not null | When quantity crosses from above to at/below threshold, insert one `list_item` (`source='pantry'`, `pantry_item_id` set) into the household's oldest non-archived, non-template list. Partial unique index guarantees at most one open pantry line per item per list. If no list exists, nothing happens. |
| X2 | Shopping → Pantry | Client UI + sync `delta` | `household.module_pantry = true`, `list_item.source='pantry'` | On check-off of a pantry-sourced item the client offers "Restock". Accepting creates a `pantry_movement` with `reason='restocked'` and `delta = list_item.quantity ?? 1`. |
| X3 | Maintenance → Shopping | API `POST /tasks/{id}/consumables-to-list` | `household.module_shopping = true` | Inserts one `list_item` per `task_consumable` with `source='maintenance'`, `maint_task_id` set. Idempotent per open list (skips if an unchecked item with same `name_key` and `maint_task_id` exists). |
| X4 | Shopping → Shopping | Trigger `list_item_learn` (AFTER UPDATE) | list has a `store_id` | On check-off, updates `store_item_order` running mean for (store, name_key). |
| X5 | Pantry → Pantry | Trigger `pantry_movement_apply` (AFTER INSERT) | — | Applies `delta` to `pantry_item.quantity`. The only way quantity changes. |
| X6 | Any → Core | Application code in API/sync | — | Sync writes an `activity` row for user-visible actions: check-off, pantry add/use, task done, member joined. |
| X7 | Core → All | `household.module_*` flags | — | Sync rejects mutations for a disabled module's tables with `module_disabled`. Clients hide the module's tab. Triggers check the flag. |
| X8 | Core → Shopping, Pantry | Function `create_household()` (security definer) | — (flags default on at insert) | Inserts one `shopping_list` named Groceries and `location` rows Fridge, Freezer, Pantry for the new household. The rows are kept if a module is later switched off (hidden by X7, not deleted). |

Module disabled matrix — what still works:

| Enabled | Behaviour |
|---|---|
| Shopping only | Manual lists, aisle learning, prices, share, templates. No pantry badges. |
| Pantry only | Inventory, eat-soon, usage stats, recipes. Low-stock items show a "low" badge but go nowhere. |
| Maintenance only | Everything in the module. "Send to list" button hidden. |
| Shopping + Pantry | X1, X2 active. |
| Shopping + Maintenance | X3 active. |
| Pantry + Maintenance | No coupling; both standalone. |

---

## 10. Notifications

- A `pg_cron` job (`enqueue_daily_notifications`, 07:00 UTC) writes `notification` rows for: eat-soon (expiry within 3 days), task due/overdue (to assignee or everyone), warranty ending in 30 days. Deduplicated per item per window. Respects `notification_pref`.
- Low-stock notification is written by the sync layer when X1 fires (so it includes the actor).
- A Vercel cron (every 10 min) calls `POST /internal/cron/deliver-notifications`, which sends unsent rows via APNs/FCM/Web Push to every `device_token` of the user and sets `sent_at`.
- Clients also sync the `notification` table for an in-app inbox and mark `read_at`.

---

## 11. Security and RLS

- Every household-scoped table: `select/insert/update/delete` allowed only when `is_member(household_id)`.
- `household` insert allowed to any authenticated user; update only to owners.
- `invite`: only owners may create `role='owner'` invites; `members cannot change an invite's `role`, `uses`, `max_uses` or `expires_at` (only retire it); `accept_invite()` is a no-op for an already-active member.
- `member` rows are created only by `create_household()` and `accept_invite()` (security definer).
- `profile` readable by anyone sharing a household with the user. Rows are created by the `on_auth_user_created` trigger (security definer) on `auth.users`; clients only update their own row.
- `product` readable by all authenticated users; written only by the API with the service role.
- API runs with the **user's JWT** forwarded to Postgres (`set local role authenticated; set local request.jwt.claims`) so RLS applies to the sync layer too. The service role is used only for cron delivery and product cache writes.
- Share links (`/shared/{token}`) are the one public read path and return only the list name and unchecked item names/quantities.
- Storage policies mirror table RLS using the `households/{household_id}/…` path prefix.

---

## 12. Repository layout

```
abodenestly/
├── docs/
│   ├── SPEC.md              ← this file
│   ├── schema.sql           ← canonical DDL (also in §14)
│   ├── TESTING.md           ← test strategy and required tests
│   ├── DESIGN.md            ← design system + per-screen plans
│   ├── STORY-TEMPLATE.md    ← story format with full example
│   └── stories/             ← generated stories, one file per module
│   └── openapi.yaml         ← generated, do not edit
├── api/                     ← Vercel functions (Node/TS)
│   ├── sync.ts
│   ├── households/…
│   ├── _lib/db.ts, auth.ts, errors.ts   ← "_" prefix: Vercel does not deploy it as a function
│   └── test/
├── packages/contract/       ← types.ts, guards, OpenAPI generation
├── web/                     ← React app
├── apps/ios/                ← Swift package + Xcode project
├── apps/android/            ← Gradle project
├── supabase/
│   ├── migrations/
│   └── seed.sql             ← global categories, checklist templates
├── .claude/skills/          ← agent skills (see AGENTS.md)
├── .cursor/rules/
├── AGENTS.md                ← shared agent instructions
└── CLAUDE.md                ← points to AGENTS.md
```

---

## 13. Definition of done

Companion documents: `docs/TESTING.md` (test levels and required tests per story), `docs/DESIGN.md` (design system and per-screen plans), `docs/STORY-TEMPLATE.md` (what every story must contain).

A story is done when:
1. Feature ID is in the PR title and branch (`feat/SHOP-06-aisle-order`).
2. Behaviour matches the acceptance summary in §4 and any story-level acceptance criteria.
3. Works offline where §6.5 says it must; a test exercises the outbox path.
4. No new dependency without justification in the PR description.
5. Types come from `packages/contract` (web/api) or the generated models (iOS/Android); no hand-written DTOs.
6. Tests at the levels `docs/TESTING.md` requires for the story's layer: unit, component, integration (triggers/RLS), and e2e for every screen story.
7. Module flag off → feature hidden and no errors.
8. Screen stories match the screen's plan in `docs/DESIGN.md` (layout, states, components).
9. PR reviewed by the `pr-review` skill with no open blocking findings.

---

## 14. SQL schema

The canonical DDL (`docs/schema.sql`) is reproduced here verbatim.

```sql
-- Household app — Supabase/Postgres schema
-- Conventions:
--   * every household-scoped table has: id uuid (client-generated allowed),
--     household_id, created_at, updated_at, deleted_at (soft delete), sync_seq
--   * sync_seq is a global monotonic sequence bumped on every write; clients
--     pull with  WHERE household_id = $1 AND sync_seq > $cursor
--   * RLS: a user can touch a row only if they are a member of its household

create extension if not exists "pgcrypto";
create extension if not exists "pg_cron";

create sequence if not exists sync_seq_global;

-- ---------------------------------------------------------------------------
-- Helpers
-- ---------------------------------------------------------------------------

create or replace function set_sync_fields() returns trigger
language plpgsql as $$
begin
  new.updated_at := now();
  new.sync_seq := nextval('sync_seq_global');
  return new;
end $$;

create or replace function is_member(hid uuid) returns boolean
language sql stable security definer set search_path = public as $$
  select exists (
    select 1 from member
    where household_id = hid and user_id = auth.uid() and left_at is null
  );
$$;

create or replace function is_owner(hid uuid) returns boolean
language sql stable security definer set search_path = public as $$
  select exists (
    select 1 from member
    where household_id = hid and user_id = auth.uid()
      and role = 'owner' and left_at is null
  );
$$;

create or replace function name_key(p text) returns text
language sql immutable as $$
  select lower(regexp_replace(trim(p), '\s+', ' ', 'g'));
$$;

-- Attach standard sync trigger + RLS to a table in one call
create or replace procedure setup_scoped(tbl regclass)
language plpgsql as $$
declare t text := tbl::text;
begin
  execute format('create trigger %I_sync before insert or update on %s
                  for each row execute function set_sync_fields()', t, t);
  execute format('alter table %s enable row level security', t);
  execute format('create policy %I_select on %s for select using (is_member(household_id))', t, t);
  execute format('create policy %I_insert on %s for insert with check (is_member(household_id))', t, t);
  execute format('create policy %I_update on %s for update using (is_member(household_id))', t, t);
  execute format('create policy %I_delete on %s for delete using (is_member(household_id))', t, t);
  execute format('create index %I_sync_idx on %s (household_id, sync_seq)', t, t);
end $$;

-- ---------------------------------------------------------------------------
-- Core
-- ---------------------------------------------------------------------------

create table profile (
  id            uuid primary key references auth.users(id) on delete cascade,
  display_name  text not null,
  avatar_path   text,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now()
);
alter table profile enable row level security;
create policy profile_self on profile for all using (id = auth.uid());
-- members of the same household can see each other's name/avatar
create policy profile_household on profile for select using (
  exists (select 1 from member a join member b on a.household_id = b.household_id
          where a.user_id = auth.uid() and b.user_id = profile.id
            and a.left_at is null and b.left_at is null)
);

-- Create a profile row the first time a user signs in (any provider).
create or replace function handle_new_user() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  insert into profile(id, display_name, avatar_path)
  values (new.id,
          coalesce(new.raw_user_meta_data->>'full_name',
                   new.raw_user_meta_data->>'name',
                   split_part(new.email, '@', 1)),
          new.raw_user_meta_data->>'avatar_url')
  on conflict (id) do nothing;
  return new;
end $$;
create trigger on_auth_user_created after insert on auth.users
  for each row execute function handle_new_user();

create table household (
  id                uuid primary key default gen_random_uuid(),
  name              text not null,
  created_by        uuid not null references auth.users(id),
  module_shopping   boolean not null default true,
  module_pantry     boolean not null default true,
  module_maintenance boolean not null default true,
  currency          text not null default 'EUR',
  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now(),
  deleted_at        timestamptz,
  sync_seq          bigint not null default nextval('sync_seq_global')
);

create table member (
  household_id  uuid not null references household(id) on delete cascade,
  user_id       uuid not null references auth.users(id) on delete cascade,
  role          text not null check (role in ('owner','member')),
  joined_at     timestamptz not null default now(),
  left_at       timestamptz,
  updated_at    timestamptz not null default now(),
  sync_seq      bigint not null default nextval('sync_seq_global'),
  primary key (household_id, user_id)
);
create index member_user_idx on member(user_id);

-- household RLS (member is referenced by is_member, so policies are explicit)
alter table household enable row level security;
create policy household_select on household for select using (is_member(id));
create policy household_insert on household for insert with check (created_by = auth.uid());
create policy household_update on household for update using (is_owner(id));
create trigger household_sync before insert or update on household for each row execute function set_sync_fields();

alter table member enable row level security;
create policy member_select on member for select using (is_member(household_id));
create policy member_update on member for update using (is_owner(household_id) or user_id = auth.uid());
-- inserts happen via security-definer functions (create_household, accept_invite)
-- A client may only touch its own membership and may only *leave* (set left_at). RLS WITH CHECK cannot
-- see OLD, so this guard enforces what an UPDATE may change. Security-definer paths (accept_invite,
-- create_household) run as the function owner and are not restricted.
create or replace function guard_member_update() returns trigger
language plpgsql as $$
begin
  if current_user <> 'authenticated' then return new; end if;
  if new.household_id is distinct from old.household_id
     or new.user_id is distinct from old.user_id
     or new.joined_at is distinct from old.joined_at then
    raise exception 'member_identity_immutable' using errcode = '42501';
  end if;
  if is_owner(old.household_id) then return new; end if;
  if new.role is distinct from old.role then
    raise exception 'member_role_owner_only' using errcode = '42501';
  end if;
  if old.left_at is not null and new.left_at is distinct from old.left_at then
    raise exception 'member_rejoin_requires_invite' using errcode = '42501';
  end if;
  return new;
end $$;
create trigger member_guard before update on member for each row execute function guard_member_update();
create trigger member_sync before insert or update on member for each row execute function set_sync_fields();

-- Seeds the owner membership, the Groceries list and the default locations (CORE-02).
create or replace function create_household(p_name text) returns uuid
language plpgsql security definer set search_path = public as $$
declare hid uuid;
begin
  if auth.uid() is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;
  insert into household(name, created_by) values (p_name, auth.uid()) returning id into hid;
  insert into member(household_id, user_id, role) values (hid, auth.uid(), 'owner');
  insert into shopping_list(household_id, name) values (hid, 'Groceries');
  insert into location(household_id, name, kind) values
    (hid, 'Fridge', 'fridge'), (hid, 'Freezer', 'freezer'), (hid, 'Pantry', 'pantry');
  return hid;
end $$;

create table invite (
  id            uuid primary key default gen_random_uuid(),
  household_id  uuid not null references household(id) on delete cascade,
  code          text not null unique default encode(gen_random_bytes(6), 'hex'),
  role          text not null default 'member' check (role in ('owner','member')),
  created_by    uuid not null references auth.users(id),
  expires_at    timestamptz not null default now() + interval '7 days',
  max_uses      int not null default 10,
  uses          int not null default 0,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  deleted_at    timestamptz,
  sync_seq      bigint not null default nextval('sync_seq_global')
);
call setup_scoped('invite');
-- Only owners may mint owner invites; members may create member invites.
drop policy invite_insert on invite;
create policy invite_insert on invite for insert with check (
  is_member(household_id) and created_by = auth.uid()
  and (role = 'member' or is_owner(household_id)));

-- Clients may only retire an invite (deleted_at). uses is bumped only by accept_invite (function owner,
-- which returns early); role / max_uses / expires_at are owner-only and 'owner' invites need an owner.
create or replace function guard_invite_update() returns trigger
language plpgsql as $$
begin
  if current_user <> 'authenticated' then return new; end if;
  if new.household_id is distinct from old.household_id
     or new.code is distinct from old.code
     or new.created_by is distinct from old.created_by
     or new.uses is distinct from old.uses then
    raise exception 'invite_field_immutable' using errcode = '42501';
  end if;
  if not is_owner(old.household_id)
     and (new.role is distinct from old.role
          or new.max_uses is distinct from old.max_uses
          or new.expires_at is distinct from old.expires_at) then
    raise exception 'invite_owner_only' using errcode = '42501';
  end if;
  return new;
end $$;
create trigger invite_guard before update on invite for each row execute function guard_invite_update();

create or replace function accept_invite(p_code text) returns uuid
language plpgsql security definer set search_path = public as $$
declare inv invite;
begin
  if auth.uid() is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;
  select * into inv from invite
   where code = p_code and deleted_at is null and expires_at > now() and uses < max_uses
   for update;
  if not found then raise exception 'invalid_invite'; end if;
  -- Already an active member: no-op (no demotion, no use burned).
  if exists (select 1 from member
              where household_id = inv.household_id and user_id = auth.uid() and left_at is null) then
    return inv.household_id;
  end if;
  insert into member(household_id, user_id, role) values (inv.household_id, auth.uid(), inv.role)
    on conflict (household_id, user_id) do update set left_at = null, role = excluded.role;
  update invite set uses = uses + 1 where id = inv.id;
  return inv.household_id;
end $$;

create table activity (
  id            uuid primary key default gen_random_uuid(),
  household_id  uuid not null references household(id) on delete cascade,
  actor_id      uuid references auth.users(id),
  module        text not null,          -- core | shopping | pantry | maintenance
  entity        text not null,          -- table name
  entity_id     uuid not null,
  action        text not null,          -- created | updated | checked | done ...
  summary       text not null,          -- "Anna checked off Milk"
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  deleted_at    timestamptz,
  sync_seq      bigint not null default nextval('sync_seq_global')
);
call setup_scoped('activity');

create table attachment (
  id            uuid primary key default gen_random_uuid(),
  household_id  uuid not null references household(id) on delete cascade,
  entity        text not null,
  entity_id     uuid not null,
  storage_path  text not null,          -- households/{hid}/{entity}/{id}/{file}
  mime          text not null,
  size_bytes    bigint not null,
  created_by    uuid references auth.users(id),
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  deleted_at    timestamptz,
  sync_seq      bigint not null default nextval('sync_seq_global')
);
call setup_scoped('attachment');
create index attachment_entity_idx on attachment(entity, entity_id);

create table device_token (
  id          uuid primary key default gen_random_uuid(),
  user_id     uuid not null references auth.users(id) on delete cascade,
  platform    text not null check (platform in ('ios','android','web')),
  token       text not null unique,
  created_at  timestamptz not null default now(),
  last_seen   timestamptz not null default now()
);
alter table device_token enable row level security;
create policy device_token_self on device_token for all using (user_id = auth.uid());

create table notification_pref (
  user_id       uuid not null references auth.users(id) on delete cascade,
  household_id  uuid not null references household(id) on delete cascade,
  type          text not null,  -- eat_soon | low_stock | task_due | warranty | activity
  enabled       boolean not null default true,
  updated_at    timestamptz not null default now(),
  sync_seq      bigint not null default nextval('sync_seq_global'),
  primary key (user_id, household_id, type)
);
alter table notification_pref enable row level security;
create policy notification_pref_self on notification_pref for all using (user_id = auth.uid());

create table notification (
  id            uuid primary key default gen_random_uuid(),
  household_id  uuid not null references household(id) on delete cascade,
  user_id       uuid not null references auth.users(id) on delete cascade,
  type          text not null,
  title         text not null,
  body          text not null,
  data          jsonb not null default '{}',
  created_at    timestamptz not null default now(),
  sent_at       timestamptz,
  read_at       timestamptz
);
alter table notification enable row level security;
create policy notification_self on notification for all using (user_id = auth.uid());
create index notification_unsent_idx on notification(sent_at) where sent_at is null;

-- ---------------------------------------------------------------------------
-- Global product cache (barcode lookups) — readable by everyone, written by API
-- ---------------------------------------------------------------------------

create table product (
  barcode     text primary key,
  name        text not null,
  brand       text,
  category    text,
  image_url   text,
  default_unit text,
  source      text,                      -- openfoodfacts | manual
  fetched_at  timestamptz not null default now()
);
alter table product enable row level security;
create policy product_read on product for select using (auth.role() = 'authenticated');

-- ---------------------------------------------------------------------------
-- Shopping
-- ---------------------------------------------------------------------------

create table store (
  id            uuid primary key default gen_random_uuid(),
  household_id  uuid not null references household(id) on delete cascade,
  name          text not null,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  deleted_at    timestamptz,
  sync_seq      bigint not null default nextval('sync_seq_global')
);
call setup_scoped('store');

create table shopping_list (
  id            uuid primary key default gen_random_uuid(),
  household_id  uuid not null references household(id) on delete cascade,
  name          text not null,
  store_id      uuid references store(id),
  is_template   boolean not null default false,   -- "weekly basics"
  share_token   text unique,                       -- read-only public link
  archived_at   timestamptz,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  deleted_at    timestamptz,
  sync_seq      bigint not null default nextval('sync_seq_global')
);
call setup_scoped('shopping_list');

create table list_item (
  id              uuid primary key default gen_random_uuid(),
  household_id    uuid not null references household(id) on delete cascade,
  list_id         uuid not null references shopping_list(id) on delete cascade,
  name            text not null,
  name_key        text generated always as (name_key(name)) stored,
  quantity        numeric,
  unit            text,
  note            text,
  barcode         text references product(barcode),
  checked         boolean not null default false,
  checked_at      timestamptz,
  checked_by      uuid references auth.users(id),
  manual_position int,                              -- user drag order, null = use learned
  source          text not null default 'manual'
                  check (source in ('manual','pantry','maintenance','template','recipe')),
  pantry_item_id  uuid,                             -- fk added after pantry_item exists
  maint_task_id   uuid,
  created_by      uuid references auth.users(id),
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  deleted_at      timestamptz,
  sync_seq        bigint not null default nextval('sync_seq_global')
);
call setup_scoped('list_item');
create index list_item_list_idx on list_item(list_id) where deleted_at is null;
-- at most one open pantry-sourced line per pantry item per list
create unique index list_item_open_pantry_idx
  on list_item(list_id, pantry_item_id)
  where source = 'pantry' and checked = false and deleted_at is null;

-- learned aisle order: running mean of check-off index per store
create table store_item_order (
  household_id  uuid not null references household(id) on delete cascade,
  store_id      uuid not null references store(id) on delete cascade,
  name_key      text not null,
  mean_position numeric not null,
  samples       int not null default 1,
  updated_at    timestamptz not null default now(),
  sync_seq      bigint not null default nextval('sync_seq_global'),
  primary key (store_id, name_key)
);
call setup_scoped('store_item_order');

-- Learn when an item is checked: position = rank of check among list's checks
create or replace function learn_store_order() returns trigger
language plpgsql as $$
declare sid uuid; pos int;
begin
  if new.checked and not coalesce(old.checked,false) then
    select store_id into sid from shopping_list where id = new.list_id;
    if sid is null then return new; end if;
    select count(*) into pos from list_item
      where list_id = new.list_id and checked and deleted_at is null and id <> new.id;
    insert into store_item_order(household_id, store_id, name_key, mean_position)
      values (new.household_id, sid, new.name_key, pos)
    on conflict (store_id, name_key) do update
      set mean_position = (store_item_order.mean_position * store_item_order.samples + pos)
                          / (store_item_order.samples + 1),
          samples = store_item_order.samples + 1;
  end if;
  return new;
end $$;
create trigger list_item_learn after update on list_item
  for each row execute function learn_store_order();

create table item_price (
  id            uuid primary key default gen_random_uuid(),
  household_id  uuid not null references household(id) on delete cascade,
  store_id      uuid not null references store(id) on delete cascade,
  name_key      text not null,
  price         numeric not null,
  seen_at       timestamptz not null default now(),
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  deleted_at    timestamptz,
  sync_seq      bigint not null default nextval('sync_seq_global')
);
call setup_scoped('item_price');

-- ---------------------------------------------------------------------------
-- Pantry
-- ---------------------------------------------------------------------------

create table location (
  id            uuid primary key default gen_random_uuid(),
  household_id  uuid not null references household(id) on delete cascade,
  name          text not null,
  kind          text not null default 'pantry' check (kind in ('fridge','freezer','pantry','other')),
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  deleted_at    timestamptz,
  sync_seq      bigint not null default nextval('sync_seq_global')
);
call setup_scoped('location');

create table category (
  id                 uuid primary key default gen_random_uuid(),
  household_id       uuid not null references household(id) on delete cascade,
  name               text not null,
  default_shelf_days int,                            -- expiry suggestion
  created_at         timestamptz not null default now(),
  updated_at         timestamptz not null default now(),
  deleted_at         timestamptz,
  sync_seq           bigint not null default nextval('sync_seq_global')
);
call setup_scoped('category');

create table pantry_item (
  id              uuid primary key default gen_random_uuid(),
  household_id    uuid not null references household(id) on delete cascade,
  location_id     uuid references location(id),
  category_id     uuid references category(id),
  name            text not null,
  name_key        text generated always as (name_key(name)) stored,
  quantity        numeric not null default 1,
  unit            text,
  low_threshold   numeric,                           -- null = never auto-add
  auto_add        boolean not null default true,
  barcode         text references product(barcode),
  added_at        date not null default current_date,
  expires_at      date,
  note            text,
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  deleted_at      timestamptz,
  sync_seq        bigint not null default nextval('sync_seq_global')
);
call setup_scoped('pantry_item');
create index pantry_item_expires_idx on pantry_item(household_id, expires_at) where deleted_at is null;

alter table list_item add constraint list_item_pantry_fk
  foreign key (pantry_item_id) references pantry_item(id) on delete set null;

-- Quantity history: usage stats + the delta-safe sync path for quantity
create table pantry_movement (
  id              uuid primary key default gen_random_uuid(),
  household_id    uuid not null references household(id) on delete cascade,
  pantry_item_id  uuid not null references pantry_item(id) on delete cascade,
  delta           numeric not null,
  reason          text not null check (reason in ('added','used','restocked','discarded','adjusted')),
  actor_id        uuid references auth.users(id),
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  deleted_at      timestamptz,
  sync_seq        bigint not null default nextval('sync_seq_global')
);
call setup_scoped('pantry_movement');
create index pantry_movement_item_idx on pantry_movement(pantry_item_id, created_at);

-- Movements are the source of truth for quantity; apply delta on insert
create or replace function apply_pantry_movement() returns trigger
language plpgsql as $$
begin
  update pantry_item set quantity = quantity + new.delta where id = new.pantry_item_id;
  return new;
end $$;
create trigger pantry_movement_apply after insert on pantry_movement
  for each row execute function apply_pantry_movement();

create table unit_conversion (
  household_id  uuid not null references household(id) on delete cascade,
  from_unit     text not null,
  to_unit       text not null,
  factor        numeric not null,
  updated_at    timestamptz not null default now(),
  sync_seq      bigint not null default nextval('sync_seq_global'),
  primary key (household_id, from_unit, to_unit)
);
call setup_scoped('unit_conversion');

create table recipe (
  id            uuid primary key default gen_random_uuid(),
  household_id  uuid not null references household(id) on delete cascade,
  name          text not null,
  instructions  text,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  deleted_at    timestamptz,
  sync_seq      bigint not null default nextval('sync_seq_global')
);
call setup_scoped('recipe');

create table recipe_ingredient (
  id            uuid primary key default gen_random_uuid(),
  household_id  uuid not null references household(id) on delete cascade,
  recipe_id     uuid not null references recipe(id) on delete cascade,
  name          text not null,
  name_key      text generated always as (name_key(name)) stored,
  quantity      numeric,
  unit          text,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  deleted_at    timestamptz,
  sync_seq      bigint not null default nextval('sync_seq_global')
);
call setup_scoped('recipe_ingredient');

-- Cross-module: pantry low stock -> shopping list (only if shopping module on)
create or replace function pantry_low_stock_to_list() returns trigger
language plpgsql as $$
declare target_list uuid;
begin
  if new.deleted_at is not null or not new.auto_add or new.low_threshold is null then return new; end if;
  if new.quantity > new.low_threshold then return new; end if;
  if old is not null and old.quantity <= old.low_threshold then return new; end if; -- already low
  if not (select module_shopping from household where id = new.household_id) then return new; end if;

  select id into target_list from shopping_list
    where household_id = new.household_id and deleted_at is null and archived_at is null
      and not is_template
    order by created_at limit 1;
  if target_list is null then return new; end if;

  insert into list_item(household_id, list_id, name, quantity, unit, source, pantry_item_id)
    values (new.household_id, target_list, new.name, greatest(new.low_threshold * 2 - new.quantity, 1),
            new.unit, 'pantry', new.id)
  on conflict do nothing;
  return new;
end $$;
create trigger pantry_item_low_stock after insert or update of quantity, low_threshold on pantry_item
  for each row execute function pantry_low_stock_to_list();

-- ---------------------------------------------------------------------------
-- Maintenance
-- ---------------------------------------------------------------------------

create table house (
  id            uuid primary key default gen_random_uuid(),
  household_id  uuid not null references household(id) on delete cascade,
  name          text not null,
  address       text,
  build_year    int,
  notes         text,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  deleted_at    timestamptz,
  sync_seq      bigint not null default nextval('sync_seq_global')
);
call setup_scoped('house');

create table contractor (
  id            uuid primary key default gen_random_uuid(),
  household_id  uuid not null references household(id) on delete cascade,
  name          text not null,
  trade         text,
  phone         text,
  email         text,
  notes         text,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  deleted_at    timestamptz,
  sync_seq      bigint not null default nextval('sync_seq_global')
);
call setup_scoped('contractor');

create table asset (
  id              uuid primary key default gen_random_uuid(),
  household_id    uuid not null references household(id) on delete cascade,
  house_id        uuid references house(id),
  name            text not null,
  kind            text,                             -- boiler | hvac | roof | appliance ...
  brand           text,
  model           text,
  serial          text,
  room            text,
  purchase_date   date,
  purchase_price  numeric,
  warranty_until  date,
  qr_code         text unique default encode(gen_random_bytes(8), 'hex'),
  notes           text,
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  deleted_at      timestamptz,
  sync_seq        bigint not null default nextval('sync_seq_global')
);
call setup_scoped('asset');

create table maint_task (
  id            uuid primary key default gen_random_uuid(),
  household_id  uuid not null references household(id) on delete cascade,
  house_id      uuid references house(id),
  asset_id      uuid references asset(id),
  title         text not null,
  description   text,
  rrule         text,                               -- RFC 5545, null = one-off
  next_due      date,
  assigned_to   uuid references auth.users(id),
  season        text check (season in ('spring','summer','autumn','winter')),
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  deleted_at    timestamptz,
  sync_seq      bigint not null default nextval('sync_seq_global')
);
call setup_scoped('maint_task');
create index maint_task_due_idx on maint_task(household_id, next_due) where deleted_at is null;

alter table list_item add constraint list_item_task_fk
  foreign key (maint_task_id) references maint_task(id) on delete set null;

create table task_consumable (
  id            uuid primary key default gen_random_uuid(),
  household_id  uuid not null references household(id) on delete cascade,
  task_id       uuid not null references maint_task(id) on delete cascade,
  name          text not null,
  quantity      numeric,
  unit          text,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  deleted_at    timestamptz,
  sync_seq      bigint not null default nextval('sync_seq_global')
);
call setup_scoped('task_consumable');

create table maint_log (
  id            uuid primary key default gen_random_uuid(),
  household_id  uuid not null references household(id) on delete cascade,
  task_id       uuid references maint_task(id),
  asset_id      uuid references asset(id),
  done_at       date not null default current_date,
  done_by       uuid references auth.users(id),
  contractor_id uuid references contractor(id),
  cost          numeric,
  notes         text,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  deleted_at    timestamptz,
  sync_seq      bigint not null default nextval('sync_seq_global')
);
call setup_scoped('maint_log');
create index maint_log_asset_idx on maint_log(asset_id, done_at);

-- Seasonal/checklist templates: global rows have household_id null
create table checklist_template (
  id            uuid primary key default gen_random_uuid(),
  household_id  uuid references household(id) on delete cascade,
  name          text not null,
  season        text,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  deleted_at    timestamptz,
  sync_seq      bigint not null default nextval('sync_seq_global')
);
create table checklist_template_item (
  id            uuid primary key default gen_random_uuid(),
  household_id  uuid references household(id) on delete cascade,
  template_id   uuid not null references checklist_template(id) on delete cascade,
  title         text not null,
  rrule         text,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  deleted_at    timestamptz,
  sync_seq      bigint not null default nextval('sync_seq_global')
);
alter table checklist_template enable row level security;
alter table checklist_template_item enable row level security;
create policy tpl_select on checklist_template for select using (household_id is null or is_member(household_id));
create policy tpl_write on checklist_template for all using (household_id is not null and is_member(household_id));
create policy tpli_select on checklist_template_item for select using (household_id is null or is_member(household_id));
create policy tpli_write on checklist_template_item for all using (household_id is not null and is_member(household_id));

-- Rolling next_due is done in the API (needs an rrule library); keep the
-- "done" write path explicit: insert maint_log, then API updates next_due.

-- ---------------------------------------------------------------------------
-- Scheduled jobs (pg_cron) -> notification rows; Vercel cron delivers them
-- ---------------------------------------------------------------------------

create or replace function enqueue_daily_notifications() returns void
language plpgsql security definer set search_path = public as $$
begin
  -- eat soon (3 days)
  insert into notification(household_id, user_id, type, title, body, data)
  select p.household_id, m.user_id, 'eat_soon',
         'Eat soon', p.name || ' expires ' || to_char(p.expires_at, 'DD Mon'),
         jsonb_build_object('pantry_item_id', p.id)
  from pantry_item p
  join household h on h.id = p.household_id and h.module_pantry
  join member m on m.household_id = p.household_id and m.left_at is null
  left join notification_pref np on np.user_id = m.user_id and np.household_id = p.household_id and np.type = 'eat_soon'
  where p.deleted_at is null and p.expires_at between current_date and current_date + 3
    and coalesce(np.enabled, true)
    and not exists (select 1 from notification n where n.type='eat_soon' and n.user_id=m.user_id
                    and n.data->>'pantry_item_id' = p.id::text and n.created_at > now() - interval '3 days');

  -- tasks due today / overdue
  insert into notification(household_id, user_id, type, title, body, data)
  select t.household_id, m.user_id, 'task_due', 'Maintenance due', t.title,
         jsonb_build_object('task_id', t.id)
  from maint_task t
  join household h on h.id = t.household_id and h.module_maintenance
  join member m on m.household_id = t.household_id and m.left_at is null
       and (t.assigned_to is null or t.assigned_to = m.user_id)
  left join notification_pref np on np.user_id = m.user_id and np.household_id = t.household_id and np.type = 'task_due'
  where t.deleted_at is null and t.next_due <= current_date and coalesce(np.enabled, true)
    and not exists (select 1 from notification n where n.type='task_due' and n.user_id=m.user_id
                    and n.data->>'task_id' = t.id::text and n.created_at > now() - interval '1 day');

  -- warranty expiring in 30 days
  insert into notification(household_id, user_id, type, title, body, data)
  select a.household_id, m.user_id, 'warranty', 'Warranty ending', a.name || ' warranty ends ' || to_char(a.warranty_until, 'DD Mon'),
         jsonb_build_object('asset_id', a.id)
  from asset a
  join member m on m.household_id = a.household_id and m.left_at is null
  where a.deleted_at is null and a.warranty_until = current_date + 30;
end $$;

select cron.schedule('daily-notifications', '0 7 * * *', $$select enqueue_daily_notifications()$$);

-- Purge soft-deleted rows after 30 days (restore window)
create or replace function purge_soft_deleted() returns void
language plpgsql security definer set search_path = public as $$
declare t text;
begin
  foreach t in array array['list_item','shopping_list','pantry_item','maint_task','maint_log','asset',
                           'store','location','category','recipe','contractor','house','attachment']
  loop
    execute format('delete from %I where deleted_at < now() - interval ''30 days''', t);
  end loop;
end $$;
select cron.schedule('purge-soft-deleted', '0 3 * * *', $$select purge_soft_deleted()$$);

-- ---------------------------------------------------------------------------
-- Realtime: publish household-scoped tables so clients can subscribe by household_id
-- ---------------------------------------------------------------------------
alter publication supabase_realtime add table
  household, member, invite, activity, attachment,
  store, shopping_list, list_item, store_item_order, item_price,
  location, category, pantry_item, pantry_movement, recipe, recipe_ingredient,
  house, contractor, asset, maint_task, task_consumable, maint_log;

```
