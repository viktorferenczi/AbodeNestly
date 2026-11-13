# Android

Kotlin 2, Jetpack Compose, minSdk 26. See `docs/SPEC.md` §3 and `.claude/skills/kotlin-android/SKILL.md`.
Needs JDK 17 (`JAVA_HOME`) and the Android SDK (`local.properties` → `sdk.dir`).

## Run locally

1. `supabase start` (from the repo root).
2. Paste the anon key from `supabase status` into `env/dev.properties` as `SUPABASE_ANON_KEY=` (keep it out of commits).
   The emulator reaches the host as `10.0.2.2`; the dev flavor allows cleartext to `10.0.2.2` and `localhost` only.
3. `./gradlew :app:installDevDebug`, then start **AbodeNestly (dev)** on an emulator.

Sign in with a magic link; the email lands in Mailpit at `http://127.0.0.1:54324`. The link points at `127.0.0.1`, which
is the emulator itself, so follow it from the host without redirects and hand the `Location` to the app:

```bash
curl -si "<verify URL from the email>" | grep -i '^location:'
adb shell am start -a android.intent.action.VIEW -d "abodenestly-dev://auth/callback?code=…"
```

### Web base URL

`WEB_BASE_URL` (`env/*.properties` → `BuildConfig`) is the web app's origin; the invite sheet shares
`<WEB_BASE_URL>/join/<code>`, which web routes to its join card. Dev is `http://localhost:5173`, not `10.0.2.2`:
the link is for other devices. App Links come with the production domain.

### Callback URL scheme

Magic link, Apple and Google all return to a custom scheme: `abodenestly-dev://auth/callback` (dev flavor) and
`abodenestly://auth/callback` (prod), set by `AUTH_URL_SCHEME` in `app/build.gradle.kts`, registered in the manifest
through a placeholder. Dev's URL is on the allow-list in `supabase/config.toml`; add prod's in the hosted project.
App Links replace the custom scheme once the production domain exists. `MainActivity` is `singleTask` and passes the
URL to `AuthService.handle`, which only accepts `<scheme>://auth/callback?code=…`.

### Apple and Google locally

Both use the supabase-kt web OAuth flow (system browser → callback), not native sheets, so no Play Services or
Credential Manager is needed. They are disabled in the local stack: set `enabled = true` and `client_id` for
`[auth.external.apple]` / `[auth.external.google]` in `supabase/config.toml` (do not commit that), export
`SUPABASE_AUTH_EXTERNAL_APPLE_SECRET` / `SUPABASE_AUTH_EXTERNAL_GOOGLE_SECRET` and `supabase stop && supabase start`.
Checked by hand only, never in e2e (TESTING §5).

## Tests

| What | Command |
|---|---|
| Module graph, lint, unit and Compose component tests (JVM, Robolectric) | `./gradlew checkModuleGraph lintDevDebug testDevDebugUnitTest` |
| E2E (instrumented, needs `supabase start` and the API on :3000: `pnpm --filter @abodenestly/api dev`) | `./gradlew :core:pixel6Api34DevDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.serviceRoleKey=<key> -Pandroid.testInstrumentationRunnerArguments.anonKey=<key>` |

Use `:core:connectedDevDebugAndroidTest` instead to run on an already-running emulator. Without both keys the e2e skips.

JUnit 5 runs the pure logic tests; the Robolectric/Compose tests are JUnit 4 and run on the same platform through the
vintage engine. In coroutine tests use `runCurrent()`, not `advanceUntilIdle()`: work in `backgroundScope` is ignored
by the latter.

The e2e journeys (E2E-CORE-1: sign-in, then create-a-household from Welcome, which needs the API on :3000 too) each create their own user through the admin API, read the magic link from Mailpit
(`10.0.2.2:54324`), follow the verify URL without redirects and hand the `abodenestly-dev://auth/callback?code=…`
URL to the real `SupabaseAuthService` for a PKCE exchange, then delete the user (and any household it created). They live in `:core`, so they host the
real screens and session view model in a plain activity rather than `:app`'s `MainActivity`. Debug builds of the
app also accept the `reset-session` boolean intent extra to start signed out
(`adb shell am start -n com.abodenestly.app.dev/com.abodenestly.app.MainActivity --ez reset-session true`).

## Generated models

`contract/src/main/kotlin` is generated from `docs/openapi.yaml`; never edit it. Regenerate with
`pnpm --filter @abodenestly/contract openapi && pnpm --filter @abodenestly/contract kotlin` (needs Java).

## Modules

`:app` (shell, DI) → `:core` (auth, sign-in) → `:ui`, `:shared`, `:contract`. Feature modules never depend on each other
(`checkModuleGraph`). `:core` declares the same `env` flavors as `:app` so root-level `*DevDebug*` tasks reach it.
