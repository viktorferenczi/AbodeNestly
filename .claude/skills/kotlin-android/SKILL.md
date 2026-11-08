---
name: kotlin-android
description: How to build AbodeNestly's Android app under apps/android/ — Kotlin 2, Jetpack Compose, minSdk 26, Room per-household local database, the outbox sync loop, WorkManager background sync, Supabase Kotlin for Auth/Realtime, FCM push, CameraX/ML Kit barcode scanning, Assistant shortcuts and module-gated navigation. Use this for any task touching apps/android/, a Composable, a Kotlin model, Room, the Android sync or Gradle setup, even if the user only says "the Android app" or "the pantry screen on Android".
---

# Kotlin Android skill

Read `docs/SPEC.md` §3, §4, §6, §9 before starting. Models are **generated** from `docs/openapi.yaml` into `:contract`; never hand-write a model that exists there.

## Allowed dependencies
AndroidX: `compose-*`, `navigation-compose`, `room`, `work-runtime-ktx`, `camera-*`, `lifecycle-*`, `datastore-preferences`. `kotlinx-serialization-json`, `kotlinx-coroutines`, `okhttp`, `supabase-kt` (Auth + Realtime only), `firebase-messaging`, `com.google.mlkit:barcode-scanning`. Build: KSP, Gradle version catalog. Nothing else without PR justification. No Retrofit, Hilt/Dagger/Koin, Moshi/Gson, Coil, Timber, RxJava, Accompanist.

## Modules and layout
```
apps/android/
  app/                       Application, MainActivity, tabs, DI container (manual)
  contract/                  generated models + ApiClient (OkHttp + kotlinx.serialization)
  core/                      auth, households, members, invites, settings, activity, search
  sync/                      HouseholdDatabase (Room), Outbox, SyncEngine, RealtimeListener, SyncWorker
  shopping/                  ← module
  pantry/                    ← module
  maintenance/               ← module
  ui/                        shared composables, theme, tokens
  shared/                    Uuid, Dates, Money, NameKey, QuantityParser
```
Rule: `:shopping`, `:pantry`, `:maintenance` depend on `:core`, `:sync`, `:ui`, `:shared`, `:contract` only. A Gradle check task (`checkModuleGraph`) fails the build if a module depends on another module.

## Architecture
- Compose + `ViewModel` + `StateFlow`. Unidirectional: `UiState` data class, `onEvent(UiEvent)` function. No Hilt; one hand-written `AppGraph` object created in `Application` and passed to view models via a `ViewModelProvider.Factory`.
- Coroutines everywhere; `Dispatchers.IO` for DB/network, structured concurrency, no `GlobalScope`.
- Navigation: `navigation-compose` with typed routes (`@Serializable` route classes), one nested graph per module, tabs filtered by module flags.

## Local database (Room)
- One Room database per household: `HouseholdDatabase` built with name `household-<household_id>.db`. A `DatabaseRegistry` opens/closes on household switch.
- Entities mirror server tables (same names/columns via `@ColumnInfo`), plus `OutboxEntity` and `MetaEntity`.
- Migrations with `Migration(from, to)` objects, append-only, mirroring server migrations. `exportSchema = true`, schemas committed.
- DAOs return `Flow<List<T>>` for screens. Composables never trigger network to render.

## Writes: always through the outbox
```kotlin
db.withTransaction {
    listItemDao.upsert(row)
    outboxDao.insert(Outbox.upsert("list_item", row))
}
syncEngine.kick()
```
- `Outbox` has `upsert`, `delete`, `delta(pantryItemId, delta, reason)`.
- Pantry quantity: optimistic local update + `delta` mutation.
- Core settings (create household, invites, modules, members) call the API directly; show "needs connection" when offline.

## Sync engine
- `SyncEngine.kick()` coalesces with a `Mutex`; push outbox in insertion order, then pull until `has_more == false`; apply with DAO `upsertAll` in one transaction; store cursor in `MetaEntity`.
- Triggers: `ProcessLifecycleOwner` START, `ConnectivityManager.NetworkCallback` available, Realtime event, 5-minute ticker while foregrounded.
- Background: `SyncWorker` (`CoroutineWorker`) scheduled as `PeriodicWorkRequest` 15 min with `NetworkType.CONNECTED`, plus a one-time expedited request when the outbox is non-empty and the app goes to background.
- Realtime: `supabase.realtime.channel(householdId)` with `postgresChangeFlow<PostgresAction>(schema = "public") { filter = "household_id=eq.$id" }`; payload ignored, triggers `kick()`.
- Rejected mutations → `outbox_failed` table, surfaced as a dismissible banner.

## Networking
`ApiClient` in `:contract`: OkHttp, `kotlinx.serialization` with `ignoreUnknownKeys = true`, bearer token from Supabase session, one retry after token refresh on 401. Base URL from `BuildConfig.API_BASE_URL`, set per product flavor (`dev`, `prod`) from `env/dev.properties` / `env/prod.properties`; never hard-coded. Timeouts 15 s.

## Auth
`supabase-kt` Auth: magic link via app link, Google via Credential Manager, Apple via OAuth web flow. Session persisted by the SDK.

## Platform features
- **Barcode** (SHOP-10, PANT-09): CameraX `ImageAnalysis` + ML Kit `BarcodeScanning` (EAN-13/8, UPC-A). Lookup `GET /products/{barcode}`; cache in local `product` table.
- **Voice add** (SHOP-14): `SpeechRecognizer`; parse with `shared/QuantityParser` (same cases as iOS).
- **Assistant** (SHOP-15): App Actions `shortcuts.xml` with `actions.intent.CREATE_ITEM_LIST` → deep link handled by `MainActivity`, writes via outbox.
- **Push** (PANT-16, MAINT-08): `FirebaseMessagingService`; register token with `POST /devices` on refresh. Notification channels per `NotificationType`. Tap opens the entity route.
- **QR** (MAINT-14): generate with a small in-house QR encoder in `:shared` (no ZXing) or ML Kit scan + `Bitmap` from the API-provided PNG; deep link `abodenestly://asset/<qr_code>`.
- **PDF export** (MAINT-13): download via API to cache dir, share with `FileProvider`.

## UI conventions
Every screen is built from its plan in `docs/DESIGN.md` (layout, states, interactions, tokens in Part A). Implement all listed states; deviations go into the PR and `docs/DESIGN.md` together.
- Material 3, dynamic color on Android 12+, dark theme via system.
- Destructive actions use a 5 s undo `Snackbar`, not confirm dialogs.
- Sync status chip (`idle / syncing / offline / N pending`) in the top bar on list screens.
- Money with `BigDecimal` + `NumberFormat.getCurrencyInstance`; dates with `java.time`. Never `Double` for money.
- TalkBack content descriptions on all controls; minimum 48 dp touch targets.

## Module gating
`Household.modules` drives tabs, nav graphs and cross-module controls. A disabled module's Gradle module is still compiled but unreachable.

## Testing
Component tests use `createComposeRule`: one case per State and Interaction in the screen's `docs/DESIGN.md` plan (see `docs/TESTING.md` §7). E2E journeys are Compose UI tests under `androidTest/` on Gradle managed devices and follow `docs/TESTING.md` §8.

- Unit: JUnit 5 + `kotlinx-coroutines-test`; Room in-memory with Robolectric for DAO/outbox tests.
- Required for shopping/pantry stories: perform the action with a fake `ApiClient` that throws `IOException`, assert outbox row, then switch to a recording fake and assert push body.
- Pure logic (aisle sort, quantity parser, eat-soon, due dates) table-driven.
- Compose UI tests only for sign-in and household switch.

## Checklist before PR
- [ ] `checkModuleGraph` passes
- [ ] Writes via outbox; no direct network for data
- [ ] Works with module flag off
- [ ] Offline test for shopping/pantry
- [ ] Models from `:contract`
- [ ] No new dependency, or justified
- [ ] Feature ID in branch/commit/PR
