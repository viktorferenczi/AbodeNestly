---
name: node-backend
description: How to write, change or test AbodeNestly's backend — Vercel serverless functions in Node/TypeScript under api/, the shared contract in packages/contract/, the sync endpoints, auth, push delivery and cron handlers. Use this whenever a task touches api/, packages/contract/, an endpoint, the sync protocol, JWT verification, the Supabase pooler, APNs/FCM/Web Push, or OpenAPI generation, even if the user just says "add an endpoint" or "fix sync".
---

# Node backend skill

Read `docs/SPEC.md` §3, §6, §7, §8, §11 before starting. This skill tells you how those sections become code.

## Allowed dependencies
`pg`, `jose`, `rrule`, `web-push`. Dev: `typescript`, `vitest`, `tsx`, `@types/pg`, `@types/web-push`. Anything else: justify in the PR or do not add it. Do not add Express, Fastify, Prisma, Drizzle, zod, axios, lodash, dayjs.

## Layout
```
api/
  sync.ts                     GET + POST /sync
  households/index.ts         POST /households
  households/[id]/invites.ts
  households/[id]/members/[user_id].ts
  households/[id]/modules.ts
  invites/[code]/accept.ts
  attachments/sign.ts
  products/[barcode].ts
  tasks/[id]/done.ts
  tasks/[id]/consumables-to-list.ts
  lists/[id]/share.ts
  shared/[token].ts
  templates/[id]/apply.ts
  export/maintenance.pdf.ts
  reports/maintenance-costs.ts
  pantry/usage.ts
  pantry/can-make.ts
  devices/index.ts
  devices/[token].ts
  internal/cron/deliver-notifications.ts
  health.ts
  _lib/        "_" prefix keeps helpers from deploying as Vercel functions
    db.ts        pool + withUser(jwt, fn)
    auth.ts      verifyJwt(req) -> { sub }
    http.ts      json(), error(), parseJson()
    errors.ts    ApiError class with code
    sync/        pull.ts, push.ts, tables.ts (allow-list + column lists)
    push/        apns.ts, fcm.ts, webpush.ts
  test/
```
One file per route. Route files are thin: parse → auth → call a function in `_lib/` → respond.

## Handler shape
```ts
export const config = { runtime: 'nodejs' };

export default async function handler(req: Request): Promise<Response> {
  try {
    const { sub } = await verifyJwt(req);
    const body = await parseJson(req, isSyncPushRequest);   // guard from packages/contract
    const result = await withUser(req, (client) => pushMutations(client, sub, body));
    return json(result);
  } catch (e) {
    return errorResponse(e);
  }
}
```

## Database access
- `_lib/db.ts` creates one `pg.Pool` per function instance pointing at the **Supabase pooler, transaction mode** (`DATABASE_URL` ends in `:6543/postgres?pgbouncer=true`). `max: 3`.
- `withUser(req, fn)` takes a client from the pool, runs:
  ```sql
  begin;
  set local role authenticated;
  select set_config('request.jwt.claims', $1, true);
  ```
  with the verified claims JSON, runs `fn`, commits. This makes RLS apply inside the API exactly as it does for the Supabase SDK. **Never** use the service role for user requests. Service role is only for `internal/cron/*` and `products/[barcode]` cache writes.
- Plain SQL with parameters. No string interpolation of values. Column lists come from `_lib/sync/tables.ts`, never from request input.

## Sync implementation rules
- `tables.ts` is an allow-list: `{ list_item: { columns: [...], module: 'shopping' }, ... }`. A mutation for a table not in it is rejected `unknown_table`.
- Push:
  1. Load household module flags once. Reject mutations for disabled modules with `module_disabled`.
  2. For each mutation in order: skip if `mutation.id` is in `sync_applied` (24 h table) → treat as applied.
  3. `upsert`: `insert ... on conflict (id) do update set <columns> = excluded.<columns>` using only allowed columns and never `updated_at`, `sync_seq`, `created_at`, `household_id` from the payload (household_id is forced to the request's household_id).
  4. `delete`: `update ... set deleted_at = now() where id = $1 and household_id = $2`.
  5. `delta`: insert into `pantry_movement`. Never update quantity directly.
  6. RLS failure → `rls_denied` for that mutation; continue with the rest. Wrap each mutation in a savepoint.
  7. Record applied ids in `sync_applied`.
  8. Write `activity` rows for check-offs, task completions, member joins.
- Pull: one query per allowed table: `select * from <t> where household_id = $1 and sync_seq > $2 order by sync_seq limit $3`. Cursor = max `sync_seq` seen. `has_more` when any table hit the limit. Membership tables (`member`) are included even though they have no `household_id` index named the same; use the explicit query.

## Auth
`_lib/auth.ts` verifies the Supabase JWT with `jose.createRemoteJWKSet(SUPABASE_JWKS_URL)`. Cache the JWKS in module scope. Reject `exp` in the past, wrong `iss`. Return `{ sub, claims }`.

## Errors
```ts
export class ApiError extends Error { constructor(public status: number, public code: string, message: string) { super(message); } }
```
Codes are `snake_case` and stable: `unauthorized`, `forbidden`, `not_found`, `validation`, `module_disabled`, `conflict`, `rate_limited`. Response body is `ApiError` from the contract.

## Contract and OpenAPI
- Types live in `packages/contract/src/types.ts`; guards in `guards.ts`. No runtime schema library.
- `packages/contract/scripts/openapi.ts` emits `docs/openapi.yaml` from a hand-maintained route table (`routes.ts`) that references the types. Run `pnpm --filter contract openapi` and commit the result. CI fails if the committed file is stale.

## Push delivery
- `internal/cron/deliver-notifications.ts` is called by Vercel cron (`vercel.json`), guarded by `x-cron-secret`.
- Select up to 200 `notification` rows with `sent_at is null`, join `device_token`, send per platform, set `sent_at` on success, delete `device_token` rows on `410 Gone`/`UNREGISTERED`.
- APNs: HTTP/2 via `http2` core module, token auth (`APNS_KEY_ID`, `APNS_TEAM_ID`, `APNS_PRIVATE_KEY`). FCM v1: OAuth2 service-account JWT signed with `jose`. Web Push: `web-push`.

## Testing
Follow `docs/TESTING.md` §3 (`-api` row): integration test per endpoint for happy path, auth failure and validation failure; e2e HTTP tests against the dev deployment in `api/test/e2e/`.

- `vitest`. Unit test `_lib/sync/*` with a fake client; integration test against `supabase start` using a real JWT minted with the local JWT secret.
- Required integration cases: X1 trigger fires through push; `delta` concurrent from two "devices" sums; RLS denies cross-household mutation; idempotent retry of the same push.

## Environment variables
Two environments: `.env.development` (committed) and `.env.production` (Vercel dashboard only). `API_BASE_URL` is used when building share links and PDF export URLs; never hard-code the domain.
`DATABASE_URL`, `SUPABASE_URL`, `SUPABASE_JWKS_URL`, `SUPABASE_SERVICE_ROLE_KEY`, `CRON_SECRET`, `APNS_*`, `FCM_SERVICE_ACCOUNT_JSON`, `VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY`, `OPENFOODFACTS_USER_AGENT`. Document new ones in `api/README.md`.

## Checklist before PR
- [ ] Route is thin; logic in `_lib/`
- [ ] Uses `withUser`, not service role
- [ ] No new dependency, or justified
- [ ] Contract updated + openapi regenerated if shape changed
- [ ] Tests for new logic; integration test if touching triggers/RLS
- [ ] Feature ID in branch/commit/PR
