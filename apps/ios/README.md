# iOS

SwiftUI, Swift 6, iOS 17+. See `docs/SPEC.md` §3 and `.claude/skills/swift-ios/SKILL.md`.

## Run locally

1. `supabase start` (from the repo root).
2. Paste the anon key from `supabase status` into `apps/ios/Config/Dev.local.xcconfig` (git-ignored):
   `SUPABASE_ANON_KEY = ...`. The other values are in `Config/Dev.xcconfig`.
3. Open `AbodeNestly.xcodeproj`, scheme **AbodeNestly Dev**, run on an iPhone simulator.

Sign in with a magic link; the email lands in Mailpit at `http://127.0.0.1:54324`. Open its link in
Safari on the simulator, or paste the `Location` of the verify redirect with
`xcrun simctl openurl booted "abodenestly-dev://auth/callback?code=…"`.

### Callback URL scheme

Magic link and Google return to a custom scheme: `abodenestly-dev://auth/callback` (Dev) and
`abodenestly://auth/callback` (Prod), set by `AUTH_URL_SCHEME` in the xcconfig and registered in
`Info.plist`. Dev's URL is on the allow-list in `supabase/config.toml`; add Prod's in the hosted project.
Universal links replace this once the production domain exists.

### Apple and Google locally

Both are disabled in the local stack. To try them, set `enabled = true` and `client_id` for
`[auth.external.apple]` / `[auth.external.google]` in `supabase/config.toml` (do not commit that), export the
secrets (`SUPABASE_AUTH_EXTERNAL_APPLE_SECRET`, `SUPABASE_AUTH_EXTERNAL_GOOGLE_SECRET`) and `supabase stop && supabase start`.
Native Apple sign-in needs the app's bundle id in `additional_client_ids` and a signed-in Apple ID on the
simulator. They are checked by hand only, never in e2e (TESTING §5).

## Tests

| What | Command |
|---|---|
| Package unit tests | `cd apps/ios/Packages/Core && xcodebuild test -scheme Core -destination 'platform=iOS Simulator,name=iPhone 16 Pro'` (same for `Shared`) |
| Component tests | `cd apps/ios && xcodebuild test -project AbodeNestly.xcodeproj -scheme "AbodeNestly Dev" -destination 'platform=iOS Simulator,name=iPhone 16 Pro' -skip-testing:AbodeNestlyUITests` |
| E2E (XCUITest) | `TEST_RUNNER_SUPABASE_SERVICE_ROLE_KEY=<from supabase status> xcodebuild test … -only-testing:AbodeNestlyUITests` |

The e2e journey creates its own user through the admin API, reads the magic link from Mailpit
(`127.0.0.1:54324`), follows the verify URL without redirects and opens the resulting
`abodenestly-dev://auth/callback?code=…` in the app for a real PKCE exchange, then deletes the user.
Without the key it skips. The app launches with `-reset-session` (Debug builds only) to start signed out.
If a name-based destination fails because a paired watch is attached, use `id=<simulator udid>` from `xcrun simctl list devices`.

## Generated models

`Packages/Contract/Sources/Contract` is generated from `docs/openapi.yaml`; never edit it. Regenerate with
`pnpm --filter @abodenestly/contract openapi && pnpm --filter @abodenestly/contract swift` (needs Java).
