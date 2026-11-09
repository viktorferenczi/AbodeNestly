# api

Vercel Node functions (SPEC §3, §7). One file per route; logic in `_lib/` (the `_` prefix keeps helpers from deploying as Vercel functions).

## Run

```bash
pnpm --filter @abodenestly/api dev     # http://localhost:3000/health
pnpm --filter @abodenestly/api test    # unit
pnpm --filter @abodenestly/api test:int
```

`scripts/dev-server.ts` maps paths to handlers locally; add each new route to its table.

## Environment variables

`.env.development` is committed with local `supabase start` values. Production values live in the Vercel dashboard (or a git-ignored `.env.production`, see `.env.production.example`).

| Variable | Used for |
|---|---|
| `DATABASE_URL` | Supabase pooler, transaction mode (port 6543 in prod) |
| `SUPABASE_URL` | Supabase project URL |
| `SUPABASE_JWKS_URL` | JWT verification keys |
| `SUPABASE_SERVICE_ROLE_KEY` | Cron delivery and product cache writes only |
| `API_BASE_URL` | Share links and PDF export URLs |
| `CRON_SECRET` | `x-cron-secret` header on internal cron routes |
| `APNS_KEY_ID`, `APNS_TEAM_ID`, `APNS_PRIVATE_KEY` | APNs token auth |
| `FCM_SERVICE_ACCOUNT_JSON` | FCM v1 |
| `VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY` | Web Push |
| `OPENFOODFACTS_USER_AGENT` | Barcode lookup |
