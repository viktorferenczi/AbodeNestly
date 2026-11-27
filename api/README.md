# api

Vercel Node functions (SPEC §3, §7). One file per route; logic in `_lib/` (the `_` prefix keeps helpers from deploying as Vercel functions).

## Run

```bash
pnpm --filter @abodenestly/api dev     # http://localhost:3000/health
pnpm --filter @abodenestly/api test    # unit
pnpm --filter @abodenestly/api test:int   # needs `supabase start`
```

Routes: `GET /health`, `POST /households` (JWT required; creates the household with its defaults, returns 201 `Household`), `POST /households/{id}/invites` (JWT required; a member mints an invite code, 201 `Invite`), `PATCH /households/{id}/modules` (JWT required; owner only; partial body of `shopping`/`pantry`/`maintenance` booleans; 200 `Household`), `POST /invites/{code}/accept` (JWT required; joins the household, 200 `{ household_id }`), `POST /attachments/sign` (JWT required; member of a household whose module is on; inserts the `attachment` row and returns 201 `{ attachment_id, upload_url, storage_path }`; `400 entity_unavailable` until the entity's table exists), `POST /lists/{id}/share` (JWT required; member; mints a new random token, which rotates any old link; 200 `{ url }` built from `API_BASE_URL`; `403 module_disabled` when shopping is off, `404` for a list the caller can't see), `DELETE /lists/{id}/share` (same checks; revokes; 204), `GET /products/{barcode}` (JWT required; barcode is 8 to 14 digits, else `400 invalid_barcode` before any query; answers from the `product` cache, else fetches Open Food Facts with a 3 s timeout, caches it and returns the six `Product` fields; `404 not_found` when OFF does not know it or has no name, `502 upstream_unavailable` when OFF is down, slow or 5xx, which is never cached; `Cache-Control: private, max-age=86400`; not module-gated; the cache write is a non-RLS upsert inside `_lib/products.ts`, a SPEC §11 exception), `GET /shared/{token}` (**public**, no JWT; 200 `{ list: { name }, items: [{ name, quantity }] }` with unchecked items only; 404 for an unknown, malformed or revoked token, a deleted list, or shopping off; reads through a private non-RLS pool inside `_lib/shares.ts`, selected by token; a malformed token is rejected before a connection is taken).

`scripts/dev-server.ts` maps paths to handlers locally; add each new route to its table.

## Environment variables

`.env.development` is committed with local `supabase start` values. Production values live in the Vercel dashboard (or a git-ignored `.env.production`, see `.env.production.example`).

| Variable | Used for |
|---|---|
| `DATABASE_URL` | Supabase pooler, transaction mode (port 6543 in prod) |
| `SUPABASE_URL` | Supabase project URL |
| `SUPABASE_JWKS_URL` | JWT verification keys |
| `SUPABASE_ANON_KEY` | Publishable key. Sent as `apikey` with the user's JWT when `POST /attachments/sign` asks Storage for an upload URL; integration tests also use it for GoTrue sign-up. Fill in `.env.development.local` from `supabase status` |
| `SUPABASE_SERVICE_ROLE_KEY` | Cron delivery and product cache writes only |
| `API_BASE_URL` | Share links and PDF export URLs |
| `CRON_SECRET` | `x-cron-secret` header on internal cron routes |
| `APNS_KEY_ID`, `APNS_TEAM_ID`, `APNS_PRIVATE_KEY` | APNs token auth |
| `FCM_SERVICE_ACCOUNT_JSON` | FCM v1 |
| `VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY` | Web Push |
| `OPENFOODFACTS_USER_AGENT` | Barcode lookup; blank uses the default `AbodeNestly/1.0 (+repo URL)`. Never put a personal email in it |
| `OFF_BASE_URL` | Barcode lookup; blank uses `https://world.openfoodfacts.org` (tests point it at a fake) |
