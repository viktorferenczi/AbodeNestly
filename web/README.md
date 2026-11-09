# web

React 19 + Vite client. See `docs/SPEC.md` §3 and `.claude/skills/react-web/SKILL.md`.

## Run locally

1. `supabase start` (from the repo root).
2. Put the anon key from `supabase status` into `web/.env.development.local` (git-ignored):
   `VITE_SUPABASE_ANON_KEY=...`. The other `VITE_*` values are in `.env.development`.
3. `pnpm --filter @abodenestly/web dev` serves `http://localhost:5173` (the port is fixed; Supabase's
   `site_url` and redirect allow-list in `supabase/config.toml` point at it).

Sign in locally with a magic link; the email lands in Mailpit at `http://127.0.0.1:54324`.
Apple and Google are disabled in the local stack (credentials are set per project in the dashboard).

## Tests

- `pnpm --filter @abodenestly/web test` — vitest unit and component tests.
- `pnpm --filter @abodenestly/web e2e` — Playwright (Chromium and WebKit iPhone viewport). Needs the
  local stack and `SUPABASE_SERVICE_ROLE_KEY` (from `supabase status`) in the **process environment**.
  It is deliberately not `VITE_`-prefixed so it can never be bundled. Optional: `E2E_SUPABASE_URL`,
  `E2E_BASE_URL`. First run: `pnpm exec playwright install chromium webkit`.
