# api

Vercel Node functions (SPEC §3, §7). One file per route; logic in `_lib/` (the `_` prefix keeps helpers from deploying as Vercel functions).

## Run

```bash
pnpm --filter @abodenestly/api dev     # http://localhost:3000/health
pnpm --filter @abodenestly/api test    # unit
pnpm --filter @abodenestly/api test:int   # needs `supabase start`
```

Routes: `GET /health`, `POST /households` (JWT required; creates the household with its defaults, returns 201 `Household`), `POST /households/{id}/invites` (JWT required; a member mints an invite code, 201 `Invite`), `PATCH /households/{id}/modules` (JWT required; owner only; partial body of `shopping`/`pantry`/`maintenance` booleans; 200 `Household`), `POST /invites/{code}/accept` (JWT required; joins the household, 200 `{ household_id }`), `POST /attachments/sign` (JWT required; member of a household whose module is on; inserts the `attachment` row and returns 201 `{ attachment_id, upload_url, storage_path }`; `400 entity_unavailable` until the entity's table exists).

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
| `OPENFOODFACTS_USER_AGENT` | Barcode lookup |
