# AbodeNestly — Design Plan

Platform-neutral plans for every screen. Each client renders them with native idioms (SwiftUI, Material 3, web components in `ui/`) but keeps the same information, states and interactions, so stories, component tests and e2e journeys can reference one plan.

---

## Part A — Design system

### A1. Principles
1. **One-handed, in-store, in a hurry.** Primary actions are reachable with a thumb; text input is the last resort.
2. **Calm.** Few colours, generous spacing, no decoration that does not carry meaning.
3. **Never block.** No modal confirm dialogs for destructive actions; use undo. No spinners over content; show cached data with a status indicator.
4. **Same words everywhere.** Terminology table below is binding.

### A2. Terminology
| Term | Meaning | Don't say |
|---|---|---|
| Household | The shared account | Family, home, group |
| Member | A person in a household | User (in UI) |
| List | A shopping list | Cart, basket |
| Item | A line on a list or in the pantry | Product, entry |
| Check off | Mark a list item as bought | Complete, tick |
| Location | Fridge/freezer/pantry/custom | Storage, place |
| Eat soon | Items expiring within 7 days | Expiring |
| Low | Pantry item at/below threshold | Out of stock |
| Asset | A thing in the house that needs upkeep | Equipment, device |
| Task | A one-off or recurring maintenance job | Chore, reminder |
| Log entry | A record that a task was done | History item |

### A3. Colour tokens (light / dark)
| Token | Light | Dark | Use |
|---|---|---|---|
| `bg` | #FAF8F5 | #121110 | App background |
| `surface` | #FFFFFF | #1C1A18 | Cards, sheets |
| `surface-2` | #F1EDE7 | #262320 | Inset areas, chips |
| `text` | #1C1A18 | #F2EFEA | Primary text |
| `text-2` | #6B655E | #A8A197 | Secondary text |
| `line` | #E4DFD7 | #2E2B27 | Dividers |
| `accent` | #2F6B5A | #6FB79F | Primary actions, links (hearth green) |
| `accent-bg` | #E3F0EB | #1E3A32 | Selected, badges |
| `warn` | #B8741A | #E0A449 | Eat soon, low stock |
| `danger` | #B3402F | #E06A58 | Overdue, destructive |
| `shopping` | #2F6B5A | #6FB79F | Module colour |
| `pantry` | #B8741A | #E0A449 | Module colour |
| `maintenance` | #3F5E8C | #86A7D6 | Module colour |

Module colour is used only on the tab icon, screen title accent and badges, so a disabled module leaves no colour behind.

### A4. Typography
System fonts (SF Pro / Roboto / system-ui). Scale: Title 28/34 semibold, Heading 20/26 semibold, Body 17/22, Secondary 15/20, Caption 13/18. Dynamic Type / font scaling respected up to 200 %.

### A5. Spacing and shape
4-pt grid. Screen padding 16. Card radius 12, chip radius 999, sheet radius 20 top. Minimum touch target 48 × 48.

### A6. Shared components
| Component | Spec |
|---|---|
| `TopBar` | Title, optional back, up to 2 actions, `SyncStatusChip` on list screens |
| `SyncStatusChip` | `idle` hidden · `syncing` spinner 14 px + "Syncing" · `offline` cloud-off + "Offline" · `pending N` cloud-up + "N to sync". Tap opens Sync sheet |
| `Tabs` | Bottom tabs: Shopping, Pantry, Maintenance, Home (core). Hidden for disabled modules; if only one module is on, that tab is the start screen |
| `ListRow` | Leading control (checkbox/icon), title, subtitle (secondary), trailing (badge/qty/chevron). Swipe left: delete (with undo). Swipe right: contextual (check, +1, done). Android: `:ui` `ListRow` holds title, subtitle, trailing, tap and long-press; the swipe wraps it in the screen |
| `Badge` | Small chip: "from pantry", "for: Boiler service", "Low", "Eat soon", "Overdue" |
| `Sheet` | Bottom sheet for add/edit; drag to dismiss; primary button pinned at bottom |
| `QuickAddBar` | Sticky input at bottom of a list: text field + mic + barcode + add. Autocomplete above it. Android: `:ui` `QuickAddBar` (field, Add, stacked suggestions; mic and barcode arrive with SHOP-14 and SHOP-10) |
| `Stepper` | − value + with long-press repeat; used for quantities. Web: the value is an editable decimal field (0.5 kg works); iOS: `QuantityStepper`, same field on the decimal pad; Android: `QuantityStepper` in `:ui`, same field on the decimal keyboard, taps only (no long-press repeat); + on empty gives 1, − from 1 or below clears |
| `EmptyState` | Illustration-free: one sentence of purpose, one primary button (Android: `:ui` `EmptyState`) |
| `UndoToast` | 5 s, "Deleted Milk · Undo"; stacks newest on top |
| `OfflineBanner` | Only on screens that need network (core settings): "You're offline. Connect to change household settings." |
| `SearchField` | Debounced 150 ms, local only |
| `DatePicker` | Date-only for `expires_at`, `done_at`, `next_due` |
| `MoneyField` | Locale currency, decimal input, never spinner |
| `RRulePicker` | Presets: Weekly, Monthly, Every 3 months, Twice a year, Yearly, Custom (interval + unit). Shows "Next: 12 Mar 2027" |

_Web deviation (CORE-05-web): the tab bar also has an always-visible Profile tab after Home (B7 is the only in-shell route to sign-out), so with every module off the tabs are Home and Profile._

_Web deviation (CORE-03-web): `Sheet` dismisses via Close, Esc and backdrop tap only; drag-to-dismiss is not built yet. It traps focus and makes the page behind inert._

### A7. Screen plan format
Each screen below has: **Route**, **Purpose**, **Layout** (top → bottom), **States** (each must have a component test), **Interactions** (each must have a component test; primary one has e2e), **Offline**, **Module gating**.

---

## Part B — Core screens

### B1. Sign in
- Route: `/signin` · Purpose: get a session.
- Layout: wordmark · "Sign in to AbodeNestly" · email field + "Send magic link" · divider "or" · Sign in with Apple · Sign in with Google · legal line.
- States: `idle` · `sending` (button spinner) · `link-sent` (replaces form: "Check your email", resend after 30 s) · `error` (inline message under field) · `offline` (buttons disabled, banner).
- Interactions: submit email → `link-sent`; tap Apple/Google → provider flow → B2 or last household; deep link from magic link → session.
- Offline: not usable. · Gating: none.

### B2. Welcome (no household yet)
- Route: `/welcome` · Purpose: create or join.
- Layout: greeting with display name · card "Create a household" (name field + button) · card "Join with a code" (code field + button) · sign-out link.
- States: `idle` · `creating` · `joining` · `error-invalid-code` · `offline` (banner, buttons disabled).
- Interactions: create → B4 of new household with defaults seeded; join → B4.
- Offline: not usable. · Gating: none.

### B3. Household switcher
- Route: sheet from any TopBar title tap · Purpose: switch households (CORE-07).
- Layout: list of households (name, role, pending-sync count) · current one checked · "Create or join another" row.
- States: `one-household` (sheet still opens, shows create/join) · `many` · `switching` (progress while DB opens).
- Interactions: tap → closes DB, opens target DB, lands on same tab; long-press → B5.
- Offline: works (local DBs). · Gating: none.

### B4. Home tab
- Route: `/home` · Purpose: overview and entry to core settings.
- Layout: TopBar "Test Home ▾" · "Today" card: counts (eat soon, low, due tasks) each tapping into its module view; cards hidden for disabled modules · Activity feed (last 20 rows of the last 30 days, CORE-08) · footer links: Members, Settings, Recently deleted.
- States: `empty-activity` · `populated` · `all-modules-off` (shows only settings prompt).
- Interactions: tap count → module view; pull to refresh → `kick()`.
- Offline: works. · Gating: cards per module.

### B5. Household settings
- Route: `/household` · Purpose: name, modules, danger zone (CORE-05).
- Layout: name field (owner) · "Modules" section with three switches + one-line description each · currency picker · "Leave household" (any role; the last owner is refused by the DB) / "Delete household" (owner, requires typing name).
- States: `owner` · `member` (read-only fields) · `offline` (banner, switches disabled) · `saving`.
- Interactions: toggle module → PATCH → tabs update immediately; leave → confirm sheet → B2 or switcher.
- Deferred: "Delete household" (owner) waits until SPEC defines an endpoint and what "deleted" hides.
- Offline: read-only. · Gating: none. iOS: a `UserDefaults` snapshot (`abodenestly.household.<user_id>.<household_id>`) until iOS sync exists. Android: the offline snapshot is a DataStore entry keyed by user and household (`abodenestly.household.<user_id>.<household_id>`); Delete is still deferred; until X7/NavHost the entry point is a Settings button on Home.

### B6. Members & invites
- Route: `/household/members` · Purpose: CORE-03, CORE-04.
- Layout: member rows (avatar, name, role, "you") with overflow menu (owner: make owner, remove) · "Invite" button → sheet: code (large, copyable), share link button, expiry, uses left, "New code".
- States: `owner` · `member` (no actions) · `invite-sheet` · `offline`.
- Interactions: remove → undo toast 5 s then DELETE; make owner → immediate (online only); leave → undo toast 5 s then DELETE; last owner leave blocked with message; share → system share sheet.
- Offline: read-only. · Gating: none. Web: until web sync exists, the offline list is a localStorage snapshot of the last fetch (`abodenestly:members:<household_id>`). iOS: a `UserDefaults` snapshot (`abodenestly.members.<household_id>`) until iOS sync exists.
- Android (CORE-04): the overflow is a `DropdownMenu` labelled "More actions for <name>"; the 5 s undo toast is a Snackbar-styled surface (live region) timed by the view model; the offline list is a DataStore snapshot; avatars show initials only.

### B7. Profile
- Route: `/profile` · Purpose: CORE-06.
- Layout: avatar (tap to change) · display name field · email (read-only) · notification preferences link (B9) · sign out.
- States: `idle` · `uploading-avatar` · `offline` (avatar upload queued, name edit allowed).
- Offline: name edit via outbox. · Gating: none.
- _Web (CORE-06-web): until web sync exists, the outbox is a localStorage queue drained by a direct SDK update when online, and a queued avatar is held in memory only (lost if the tab reloads while offline)._
- _iOS (CORE-06-ios): reached from the Home tab's "Profile" link. Until iOS sync exists (SHOP-08), the outbox is a `UserDefaults` queue (`abodenestly.outbox.profile.<uid>`) drained by a direct update when online, and a picked photo (downscaled to ≤ 512 px JPEG) is kept on disk under `Application Support/pending-avatar/<uid>.jpg`, so it survives a relaunch. The email comes from the auth session, not the profile row._

### B8. Search
- Route: `/search` (TopBar magnifier) · Purpose: CORE-09.
- Layout: SearchField autofocus · results grouped by module (section headers with module colour) · each row navigates to the entity.
- States: `empty-query` (recent searches) · `no-results` · `results`.
- Offline: works. · Gating: sections per module.
- _Android (CORE-09-android): the magnifier is a TopBar action on the Home tab (content description "Search"), and Search is a flag over the shell like Members/Settings (system Back closes it). No entity screens exist yet, so a result row only switches to its module's tab (`SearchEvent.Open(SearchTarget{module, table, id, listId?})`); the typed target is what the entity screens will consume. Recent searches (last 8 per household, newest first, case-insensitive dedupe, recorded when a result is opened or a search with results is submitted) live in DataStore under `abodenestly.search.<household_id>`. The local tables for list_item, pantry_item, recipe, asset, maint_task, store, location and contractor are minimal (id, household_id, name/title, deleted_at, plus list_id on list_item) until the module stories widen them; the Room version is 3. The DAOs return the household's live rows and `Search.group` does the (locale-insensitive) name match._
- _iOS (CORE-09-ios): a result row only switches to its module's tab (`SearchTarget {module, table, id, listId?}`, the typed target the entity screens will consume). Recent searches (last 8 per household, newest first, case-insensitive dedupe, recorded when a result is tapped or Return is pressed with results) live in `UserDefaults` under `abodenestly.search.<householdId>`. The magnifier (accessibility label "Search") is a TopBar action that pushes Search onto the Home tab; it is hidden when no module flags are known for the household, i.e. offline with no cached household. Search reads minimal local `list_item`, `pantry_item`, `recipe`, `asset`, `maint_task` (`title`), `store`, `location`, `contractor` tables (migration v3), which the module stories widen._

_Deviation (CORE-09): the module list stories do not exist yet, so a result row opens a placeholder at its DESIGN path on web (`/shopping/:listId?item=:id`, `/shopping/stores/:id`, `/pantry?item=:id`, `/pantry/settings`, `/pantry/recipes/:id`, `/maintenance/assets/:id`, `/maintenance/tasks/:id`, `/maintenance/contractors`) and switches to the module's tab on iOS and Android. Recent searches (last 8 per household, newest first) are stored on the device: `abodenestly:search:<householdId>` (web localStorage), `abodenestly.search.<householdId>` (iOS UserDefaults, Android DataStore). The magnifier is a TopBar action on every web tab screen once the household is ready (beside the profile name) and on Home on iOS and Android._

### B9. Notification preferences
- Route: `/profile/notifications` · Purpose: CORE-12.
- Layout: switches per type (Eat soon, Low stock, Task due, Warranty, Activity) per household · "System notification settings" link.
- States: `idle` · `permission-denied` (banner explaining system settings).
- Offline: via outbox. · Gating: rows hidden for disabled modules.
- Platform notes: **All:** one switch per type for the current household (`enabled` defaults to true: no row means on, rows are never deleted). Rows of a disabled module are hidden (Eat soon, Low stock need pantry; Task due, Warranty need maintenance); Activity always shows. A toggle is one local transaction (upsert the local `notification_pref` row + queue an outbox upsert) followed by `sync.kick()`. The outbox `row_id` is a UUID v5 (RFC 4122 URL namespace `6ba7b811-9dad-11d1-80b4-00c04fd430c8`, name `abodenestly:notification_pref:<user_id>:<household_id>:<type>`, all lowercase) because the table has no `id`; the same value on every platform, pinned by a golden-value test on each. `data` is `{user_id, household_id, type, enabled}`; SHOP-08-api keys the row off `data`. `notification_pref` is not in SPEC's `SyncTable` yet, so nothing drains this outbox row until SHOP-08-api accepts the table (assumption stated in the CORE-12 PR). Local tables: Dexie v5 `[user_id+household_id+type]`, GRDB `v5`, Room v5. **Web:** reached from the Profile link; the Shell TopBar owns the page title, so the screen shows an h2. Browsers have no deep link to their notification settings (platform deviation), so "System notification settings" is a button: while the permission is `default` it calls `Notification.requestPermission()`, otherwise it shows help text on where to change it in the browser's site settings; a browser without the Notification API gets a short "not supported" line and no banner. The banner is a `role="status"` paragraph when `Notification.permission === 'denied'`. **iOS:** pushed from Profile; the link opens `UIApplication.openNotificationSettingsURLString`; the permission is read from `UNUserNotificationCenter` on appear and whenever the scene becomes active. **Android:** reached from Profile (a flag over it with its own Back button, like Recently deleted); the permission is `NotificationManagerCompat.areNotificationsEnabled()`, re-read on resume; the link fires `Settings.ACTION_APP_NOTIFICATION_SETTINGS`.

### B10. Notification inbox
- Route: `/inbox` (bell in Home TopBar) · Purpose: in-app list of notifications.
- Layout: rows (icon by type, title, body, time), unread bold · "Mark all read".
- States: `empty` · `populated`.
- Offline: works. · Gating: none.

### B11. Recently deleted
- Route: `/deleted` · Purpose: CORE-10.
- Layout: segmented by module · rows with "Restore" and days left.
- States: `empty` · `populated`.
- Offline: restore via outbox (`upsert` with `deleted_at: null`). · Gating: segments per module.
- Platform notes: **All:** the module tables are those of CORE-09 search (`shopping_list` is out until SHOP-01). Days left = 30 − whole days since `deleted_at`; rows at 0 or below are hidden client-side because the purge sends no tombstone. Restore sends a partial `data` of `{"deleted_at": null}` so a newer rename is not overwritten. Entry: "Recently deleted" link after Settings in the B4 footer. **Web:** the Shell's TopBar owns the page title, so the screen shows an h2 heading; the outbox row keeps the SPEC §6.2 envelope in its `payload`. **iOS:** reached from the Home footer link "Recently deleted" (after Settings), pushed on the Home stack; segments are a `Picker(.segmented)`; Restore is one local transaction (row + outbox) followed by `sync.kick()`. **Android:** the screen has its own "Recently deleted" heading and the app's Back button (no TopBar owns the title); segments are a Material 3 `SingleChoiceSegmentedButtonRow`; a selected segment with no rows shows a one-line note. The outbox row is written directly by `DatabaseRegistry.restore` with `data` `{"deleted_at":null}` (no outbox writer exists before SHOP-08).

### B12. Sync sheet
- Route: from SyncStatusChip · Purpose: transparency and recovery.
- Layout: status line · "Pending changes: N" · "Failed changes: M" with list (table, action, reason) and "Retry" / "Discard" · "Sync now".
- States: `idle` · `pending` · `failed`.
- Offline: works.
- Web: the chip sits in the shell's top bar on every screen (not only list screens) and the sheet lives in `web/src/sync/`, because it also shows core writes. Failed changes: Retry puts the change back in the queue; Discard removes it and the local row it created, then pulls the server's version.
- iOS: the chip sits in the global `TopBar` before the icon actions (it does not count toward their two) on every signed-in screen, and the sheet (`Core/Sync`) is a medium/large detent sheet, because it also shows core writes. Status line: "Up to date" · "Syncing…" · "Offline — changes will sync when you're back online". Failed rows read `<table> · save|delete|adjust · Couldn't save: <reason>`; Retry and Discard behave as on web, and there is no separate banner for failed changes.
- Android: same rules as iOS. The chip sits in the global `TopBar` before the action icons on every signed-in screen (a 48 dp target, cloud icons drawn in `:ui`), and the sheet (`:core`, `core/sync/`) is a Material 3 modal bottom sheet, because it also shows core writes. Status line and failed-row wording as on iOS; there is no separate banner for failed changes (the skill's earlier "dismissible banner" line was dropped). Background sync is a 15-minute `WorkManager` job plus one extra pass when the app leaves the foreground with writes queued.

---

## Part C — Shopping screens

### C1. Lists overview
- Route: `/shopping` · Purpose: SHOP-01, SHOP-11.
- Layout: TopBar "Shopping" + SyncStatusChip + "+" · sections: Lists (rows: name, store chip, "3 of 12", pantry badge count), Templates, Archived (collapsed) · FAB "New list".
- States: `empty` (EmptyState "Make your first list") · `populated` · `offline`.
- Interactions: tap → C2; swipe left archive (undo); "+" → sheet: name, store picker, "Template" switch.
- Offline: full. · Gating: tab hidden when off.
- Web (SHOP-01-web): the Shell bar owns the h1, so the screen shows an h2 "Shopping" with the "+" beside it. The sheet has no Template switch until SHOP-11, and rows have no pantry badge until SHOP-16. Archived rows offer Restore, and swipe left there deletes with the same 5 s undo; every swipe also exists as a button.
- iOS (SHOP-01-ios): the global `TopBar` keeps the household name, so the screen has its own "Shopping" heading with the "+" beside it, and a "New list" button at the bottom of a populated overview. Swipe left archives with a 5 s `UndoToast` (the write waits for the window; leaving the screen or sending the app to the background writes what is waiting; a write that cannot be stored shows "Could not save the change. Try again." under the heading). Archived is a collapsed disclosure group; its rows offer Restore and swipe-left Delete (same 5 s undo), and every swipe also exists as a VoiceOver action. Offline shows a note under the heading; nothing else changes. The new-list sheet has name and store (the picker only when the household has stores) and no Template switch until SHOP-11. After saving, the list opens its detail (C2). Lists and stores are read straight from the local database (`HouseholdDatabase.observeShoppingLists`).
- Android (SHOP-01-android): same rules as iOS. The global `TopBar` stays above, so the screen has its own "Shopping" heading with the "+" beside it, and an extended FAB "New list" on a populated overview. Swipe left (`SwipeToDismissBox`) archives with a 5 s `UndoToast` (the write waits for the window; leaving the screen or `ON_STOP` writes what is waiting; a write that cannot be stored shows "Could not save the change. Try again." under the heading). Archived is collapsed; its rows offer Restore (button, or swipe right) and swipe-left Delete (same 5 s undo). Every swipe is also a TalkBack custom action and an entry in the row's long-press menu. Offline shows an `OfflineBanner` under the heading and nothing else changes. The new-list sheet (`ModalBottomSheet`) has name, focused on open, and store (the picker only when the household has stores), and no Template switch until SHOP-11; Save is off while the name is blank. After saving, the list opens its detail (C2). There is no NavHost yet, so opening a list is a saved flag inside `ShoppingTab` with a back handler until the tab shell (X7). Lists, counts and stores are read from the local database (`DatabaseRegistry.observeListOverview`).

### C2. List detail  ← the most used screen
- Route: `/shopping/:listId` · Purpose: SHOP-02…09, 12, 16, 17.
- Layout: TopBar list name (tap → rename), store chip (tap → store picker), overflow (Share, Clear checked, Template actions, Archive) · unchecked items sorted per SHOP-06/07, each `ListRow`: checkbox, name, qty+unit, note, badges (from pantry / for: task), last price · divider "Checked (n)" collapsible · checked items dimmed · `QuickAddBar` sticky at bottom.
- States: `empty` (QuickAddBar focused, hint "Try: 2 l milk") · `populated` · `all-checked` (celebration line + "Clear checked") · `offline` · `pending-sync` · `reordering` (drag handles visible).
- Interactions: tap checkbox → check with undo toast; tap row → C3; long-press → drag reorder (sets `manual_position`, teaches order); check a pantry-sourced item → inline prompt "Restock pantry? [Yes] [No]" (X2, only if pantry on); swipe left → delete with undo; add via text / mic (C6) / barcode (C7); pull to refresh.
- Offline: full. · Gating: pantry prompt and badges only if pantry on; task badge only if maintenance on.
- Web (SHOP-02-web): the Shell bar owns the h1, so the screen shows an h2 with the list name, a back link "Shopping" and the store chip (no rename or swipe-delete until their stories). Items show name, qty+unit and note (SHOP-03-web), oldest first; tapping a row opens C3; checked items sit in a collapsed "Checked (n)", newest first. The `QuickAddBar` has a "Scan barcode" button since SHOP-10-web (opens C7) and no mic button until SHOP-14, and suggestions come from the household's own list items (live, or checked at some point). The empty hint is "Add your first item" until SHOP-14 parses quantities (then "Try: 2 l milk"). An unknown or deleted list shows "This list is gone".
- iOS (SHOP-02-ios): the global `TopBar` stays above, so the list name is the inline navigation title, with the store chip under it (a button since SHOP-05-ios). Items show name, qty+unit and note (SHOP-03-ios), oldest first, and tap opens C3; there is no swipe-delete yet. Items checked on another device sit in a collapsed "Checked (n)" group whose rows open C3 too. The `QuickAddBar` is pinned above the keyboard with suggestions stacked above the field (at most five, from the household's own list items); it has a "Scan barcode" button since SHOP-10-ios (opens C7) and no mic button until SHOP-14. The bar has a Details button (C3 `new`). Tapping a suggestion adds it; Return and Add add exactly the typed text, never an auto-picked suggestion. The field keeps focus after each add. The empty hint is "Add your first item". A write that cannot be stored shows "Could not save the change. Try again." under the title. An unknown or deleted list shows "This list is gone".
- Android (SHOP-02-android, SHOP-03-android): same rules as iOS. The global `TopBar` stays above, so the screen has a Back button, the list name as its title and the store chip under it (a button since SHOP-05-android). Items show name, qty+unit and note (SHOP-03-android), oldest first, and tapping a row opens C3 (read as one TalkBack node, "Milk, 2 l, lactose free"); there is no swipe-delete yet. Checked items sit in a collapsed "Checked (n)" group, newest first, dimmed and announced as "Checked" to TalkBack; their rows open C3 too. The `QuickAddBar` has a Details button (C3 `new`). A deleted item hides at once under a 5 s `UndoToast` stack above the bar. The `QuickAddBar` appears once the first local read has shown the list (never for a missing list) and is pinned at the bottom above the keyboard (`imePadding`) with suggestions stacked above the field (at most five, from the household's own list items); it has no mic or barcode button until SHOP-14 and SHOP-10. Tapping a suggestion adds it; Done on the keyboard and the Add button add exactly the typed text, never an auto-picked suggestion. The field keeps focus after each add. The empty hint is "Add your first item", and the empty state opens with the bar focused. Offline shows an `OfflineBanner` and adding still works. A write that cannot be stored shows "Could not save the change. Try again." under the title and keeps the text. An unknown or deleted list shows "This list is gone". Archived lists open and accept adds. Read from the local database (`DatabaseRegistry.observeListDetail`).
- Web (SHOP-04-web): each row has a checkbox. Checking writes after the 5-second undo window (nothing is written if Undo is tapped); until then the item stays in place, ticked and struck through, with a "Checked Milk · Undo" toast, and then moves to "Checked (n)". Unchecking (tap a ticked box in the group) writes at once with no toast. A "⋯" button (List actions) next to the list name appears when something is checked and holds only "Clear checked" until Share, Template and Archive land. `all-checked` shows "All done" and a "Clear checked" button. Clear checked soft-deletes the checked items after a 5-second undo ("Cleared 2 items · Undo").
- iOS (SHOP-04-ios): same rules as web. The checkbox sits beside each row (the row body still opens C3); the check-off is written when the 5-second window ends, and also when the person leaves the screen or backgrounds the app. The toolbar menu "List actions" (⋯) holds "Clear checked". Checking gives a success haptic. Unchecking a ticked box in "Checked (n)" writes at once.
- Android (SHOP-04-android): same rules as web and iOS. A checkbox sits beside each row (the row body still opens C3; TalkBack gets a "Check off" action on the row and the box reads "Check off Milk" / "Uncheck Milk"); the check-off is written when the 5-second window ends, and also when the person leaves the screen or stops the app, not on rotation. Until then the row shows ticked and struck through in place, with a "Checked Milk" toast in the same stack as the delete toast. A "List actions" (⋯) button next to the list name appears when something is checked and holds "Clear checked". Unchecking a ticked box in "Checked (n)" writes at once. There is no swipe-delete.
- Web (SHOP-05-web): the store chip is always a button: the store name, or a muted "Add store" when the list has none (or its store was deleted). It opens a picker with "No store", the household's stores, an inline "New store" field (creates and selects in one step) and a "Manage stores" link; Save writes only `store_id`, and only if the choice changed.
- iOS (SHOP-05-ios): same rules as web. The chip is a button (label "Store: Test Market", or "Add store" when empty so Voice Control matches the visible words; 44 pt hit area) that opens the picker as a sheet; "Manage stores" closes it and pushes Stores on the Shopping stack. A store just created in the picker stays selected before the observation catches up; a store deleted elsewhere while the sheet is open drops out and the selection falls back to the list's value.
- Android (SHOP-05-android): same rules as web. The chip is a button (content description "Store: Test Market" or "Store: none", visible text "Add store" when empty; 48 dp target) that opens the picker as a `ModalBottomSheet` with radio rows; "Manage stores" closes it and opens Stores above the list (Back returns to the list). Save writes `store_id` only when the choice changed. A store just created in the picker stays selected before the observation catches up; a store deleted elsewhere while the sheet is open drops out and the selection falls back to the list's value.
- Web (SHOP-09-web): a check-off or any other change by another member appears after a Realtime-triggered pull, with no reload; the event payload is ignored (SPEC §6.4). When Realtime is down nothing is shown, and the open, visible, online and 5-minute pulls keep the list current. The subscription covers every published household table (SPEC §6.4), and with Shopping off it leaves out the shopping tables only.
- iOS (SHOP-09-ios): the same as web. A check-off or any other change by another member appears after a Realtime-triggered pull, with no reload; the event payload is ignored (SPEC §6.4). When Realtime is down nothing is shown, and the foreground, reconnect and 5-minute pulls keep the list current. The subscription covers every published household table, and with Shopping off it leaves out the shopping tables only. It is dropped while the app is in the background and rejoined (with one catch-up pull) on return.
- Android (SHOP-09-android): the same as web and iOS. A check-off or any other change by another member appears after a Realtime-triggered pull, with no reload; the event payload is ignored (SPEC §6.4). When Realtime is down nothing is shown, and the foreground, reconnect and 5-minute pulls keep the list current. The subscription covers every published household table, and with Shopping off it leaves out the shopping tables only. It is dropped while the app is in the background and rejoined (with one catch-up pull) on return.

### C3. Item detail / edit
- Route: sheet · Purpose: SHOP-03, SHOP-12.
- Layout: name (autocomplete) · Stepper qty + unit picker · note · price field with store name · "Last bought: 3 Nov, €1.20 at Test Market" · delete.
- States: `new` · `edit` · `pantry-sourced` (shows link to pantry item) · `task-sourced` (shows link to task).
- Offline: full.
- Web (SHOP-03-web): name, Stepper quantity, unit and note only; no price or "Last bought" (SHOP-12), no name autocomplete in the sheet (the QuickAddBar owns it), no pantry or task states (SHOP-16/17). `new` opens from a "Details" button on the QuickAddBar, prefilled with the typed text; `edit` from tapping a row (checked rows too). Unit is free text with suggestions (pcs, g, kg, ml, l, pack, bottle, can, plus units already used in the household). Blank quantity, unit and note are stored as null; a quantity must be above 0 with at most 3 decimals (comma accepted). Saving writes only the changed columns. Delete has no confirmation, only the 5-second undo toast.
- iOS (SHOP-03-ios): same fields and rules as web. A sheet with medium and large detents, Cancel and Save in the toolbar (swiping it down is Cancel, nothing is written), the quantity as `QuantityStepper` (decimal pad), unit as free text with suggestion chips under it that narrow as you type, note of 2–4 lines. `new` opens from the Details button on the QuickAddBar, prefilled with the typed text; `edit` from tapping any row, checked ones too. Delete hides the row at once and shows the 5-second undo toast; the delete is written only after the window (or when the screen is left), so Undo writes nothing. A write that fails keeps the sheet open with the input kept.
- Android (SHOP-03-android): same fields and rules as web and iOS. A `ModalBottomSheet` (swiping it down, the scrim or Back is Cancel, nothing is written) with Cancel and Save buttons (Save is off while the name is blank or the quantity is invalid, with "Enter a number above 0" inline), the quantity as `QuantityStepper` (decimal keyboard, no long-press repeat on Android), unit as free text with suggestion chips under it that narrow as you type, a note of 2–4 lines, and in `edit` a "Delete item" button. `new` opens from the Details button on the QuickAddBar, prefilled with the typed text, and clears the bar when saved; `edit` from tapping any row, checked ones too. Delete hides the row at once and shows the 5-second undo toast; the delete is written when the window ends, or on `ON_STOP` and when the screen is left (not on a configuration change), so Undo writes nothing. A write that fails keeps the sheet open with the input and shows "Could not save the change. Try again."; if the edited row disappears (deleted on another device) the sheet closes.

### C4. Stores
- Route: `/shopping/stores` · Purpose: SHOP-05.
- Layout: rows (name, "learned 42 items") · "+".
- States: `empty` · `populated`.
- Interactions: tap → C5; swipe delete with undo.
- Offline: full.
- Web (SHOP-05-web): the Shell bar owns the h1, so the screen shows a back link "Shopping" and an h2 "Stores" with the "+" beside it. Reached from a "Stores" link next to "+" on Shopping and from "Manage stores" in the store picker. Rows show the name and "learned N items" only when N > 0. A row tap does nothing until SHOP-06-web adds C5. Swipe left or the Delete button soft-deletes after the 5 s undo; lists keep their `store_id`, and Recently deleted restores the store. Duplicate names (case-insensitive) and empty names are blocked inline.
- iOS (SHOP-05-ios): same rules as web. Pushed on the Shopping stack from a storefront button next to "+" on Shopping and from "Manage stores"; "Stores" is the inline navigation title with a "+" in the bar. Row tap does nothing until SHOP-06-ios. Swipe-left Delete with a 5 s undo, also written on leaving the screen or backgrounding the app.
- Android (SHOP-05-android): same rules as web. The global `TopBar` stays above, so the screen has a Back button and a "Stores" heading with the "+" beside it. Opened from a "Stores" text button beside "+" on Shopping (the store icon is not in the dependencies) and from "Manage stores"; Back returns to Shopping or to the open list. Row tap does nothing until SHOP-06-android. Swipe-left, the Delete button and the TalkBack action "Delete" soft-delete after a 5 s `UndoToast`; the delete is written when the window ends or on leaving the screen or the app, not on rotation. The "New store" sheet is a `ModalBottomSheet`.

### C5. Store detail (aisle order)
- Route: `/shopping/stores/:id` · Purpose: make SHOP-06 visible and editable.
- Layout: explanation line · items ordered by `mean_position` with drag handles · "Reset learning".
- States: `no-data` · `populated`.
- Interactions: drag → sets `mean_position` directly with `samples` reset to 5; reset → delete rows.
- Offline: full.

### C6. Voice add
- Route: sheet from mic · Purpose: SHOP-14.
- Layout: waveform/listening indicator · live transcript · parsed preview chips (qty, unit, name) · "Add" / "Try again".
- States: `listening` · `parsed` · `permission-denied` · `unsupported`.
- Offline: on-device recognition where available; otherwise `unsupported` with hint.

### C7. Barcode scanner (shared with pantry)
- Route: full-screen modal · Purpose: SHOP-10, PANT-09.
- Layout: camera preview with guide box · torch toggle · "Enter code manually" · result card (product name, brand, image) with "Add".
- States: `scanning` · `found` · `not-found` (name field to add manually, cached locally) · `permission-denied` · `offline` (scans still read; lookup queued, item added with barcode as name until resolved).
- Offline: partial as described.
- Web (SHOP-10-web): a full-screen dialog opened by the "Scan barcode" button on the list's `QuickAddBar`. It reads with the browser's `BarcodeDetector` (EAN-13, EAN-8, UPC-A, UPC-E, about every 200 ms) from the rear camera; no library is added. Where `BarcodeDetector` or the camera API is missing (Firefox, desktop Safari) it opens the `unsupported` variant of the permission-denied layout: "Scanning isn't available in this browser", with the manual code field focused. The Torch toggle (`aria-pressed`) shows only when the camera reports a torch. "Enter code manually" takes 8 to 14 digits ("Enter 8 to 14 digits" otherwise). `found` shows the image (only when there is one), name and brand; Add puts the product name and its barcode on the list and closes. `not-found` ("We don't know this product", also for an invalid code) focuses a name field, prefilled if this device named the code before; Add puts that name on the list with no barcode (the `list_item.barcode` foreign key needs a product row) and remembers it on this device (last 200). `offline` (no network, or the lookup failed with a network error or 502) adds the item at once with the code as its name and shows "Added <code>. We'll look it up when you're back online"; the lookup is queued on this device and, once it finds a product, renames the item and sets its barcode unless the person renamed it first. `permission-denied` shows "Camera access is off" with how to allow it, and manual entry.
- iOS (SHOP-10-ios): same rules as web; the wording says device and Settings where web says browser. A full-screen cover opened by the "Scan barcode" button (`barcode.viewfinder`) on the list's `QuickAddBar`. It reads with AVFoundation (`AVCaptureSession` + `AVCaptureMetadataOutput`: EAN-13, EAN-8, UPC-E; no new dependency; VisionKit is not used because it needs an A12 chip and does not run in the simulator). UPC-A arrives as EAN-13 with a leading 0, which is dropped so the 12-digit code matches what web and the API store. The camera, torch and permission sit behind a `ScannerDevice` protocol, so tests and previews use a fake. Camera permission is asked when the scanner opens (`NSCameraUsageDescription`: "AbodeNestly uses the camera to scan product barcodes."). Denied or restricted shows `permission-denied` ("Camera access is off", allow it in Settings); a device with no camera, including the simulator, shows `unsupported` ("Scanning isn't available on this device"); a busy or failed camera shows "Can't use the camera". "Enter code manually" (8 to 14 digits) is on offer in every state without a result card, and takes focus where there is no camera. The Torch button shows only when the camera has a torch. A failed lookup (401, 500) shows "Couldn't look that up. Try again." with Retry and a name field, and writes nothing. The name cache (last 200, newest last) and the queued lookups are saved per device in `UserDefaults`; the queue drains on household open, on reconnect and after a sync run, and resolves like web's.

### C8. Share list
- Route: sheet from overflow · Purpose: SHOP-13.
- Layout: switch "Share with a link" · link (copyable) · "Anyone with the link can view unchecked items" · "Stop sharing".
- States: `off` · `on` · `offline` (disabled, needs API).

### C9. Public shared list (web only)
- Route: `/shared/:token` · Purpose: SHOP-13 viewer.
- Layout: list name · household name omitted · unchecked items with qty · footer "Made with AbodeNestly".
- States: `loading` · `populated` · `empty` · `expired`.
- Offline: none.

---

## Part D — Pantry screens

### D1. Pantry overview
- Route: `/pantry` · Purpose: PANT-01, 02, 03, 06.
- Layout: TopBar "Pantry" + SyncStatusChip + "+" · summary chips: "Eat soon (3)" → D3, "Low (2)" → filtered view · location segmented control (All, Fridge, Freezer, Pantry, …) · SearchField · items grouped by category, `ListRow`: name, qty+unit with inline Stepper on swipe right, expiry caption coloured (warn ≤7 d, danger past), badges Low / Eat soon · `QuickAddBar`.
- States: `empty` (EmptyState "Add what's in your fridge") · `populated` · `filtered-empty` · `offline` · `pending-sync`.
- Interactions: tap → D2; swipe right reveals Stepper (−/+ writes delta `used`/`added`); swipe left delete (`discarded` movement + soft delete) with undo; quick add parses "2 kg chicken breast" and prompts for location and expiry (prefilled from category shelf days).
- Offline: full. · Gating: tab hidden when off.

### D2. Pantry item detail / edit
- Route: sheet · Purpose: PANT-02, 04, 07, 10.
- Layout: photo (tap to add) · name · Stepper qty + unit · location picker · category picker · added date · expiry DatePicker with "Suggest" (category shelf days) · "Low stock threshold" Stepper + "Add to shopping list automatically" switch (hidden if shopping off) · barcode · note · "History" section (movements: date, delta, reason, who) · "Usage: ~1.5 / week" · delete.
- States: `new` · `edit` · `low` (banner "Below threshold — on the Groceries list" when shopping on) · `expired`.
- Offline: full; photo upload queued.

### D3. Eat soon
- Route: `/pantry/eat-soon` · Purpose: PANT-05.
- Layout: rows sorted by expiry, grouped Today / Tomorrow / This week · each with "Used" and "Discard" quick actions.
- States: `empty` ("Nothing expiring this week") · `populated`.
- Offline: full.

### D4. Locations & categories
- Route: `/pantry/settings` · Purpose: PANT-01, PANT-08, PANT-13.
- Layout: Locations list (name, kind icon, item count) with "+" · Categories list (name, shelf days) with "+" · Unit conversions list (1 kg = 1000 g) with "+".
- States: `defaults-only` · `custom`.
- Offline: full.

### D5. Usage stats
- Route: `/pantry/usage` · Purpose: PANT-11.
- Layout: rows: name, per-week figure, last restocked, sparkline of last 8 weeks (simple bars, no chart library).
- States: `insufficient-data` (<2 weeks) · `populated`.
- Offline: computed locally from movements.

### D6. Recipes
- Route: `/pantry/recipes` · Purpose: PANT-12.
- Layout: segmented "All / Can make" · rows: name, "7 of 9 in stock" progress · "+".
- States: `empty` · `populated` · `can-make-empty`.
- Interactions: tap → D7.
- Offline: full.

### D7. Recipe detail
- Route: `/pantry/recipes/:id` · Purpose: PANT-12 + SHOP-11.
- Layout: name · ingredients with in-stock check marks and missing ones highlighted · instructions · "Add missing to list" (if shopping on) · edit.
- States: `all-in-stock` · `missing-some` · `edit`.
- Gating: "Add missing" only if shopping on.

---

## Part E — Maintenance screens

### E1. Maintenance dashboard
- Route: `/maintenance` · Purpose: MAINT-07.
- Layout: TopBar "Maintenance" + SyncStatusChip + "+" · "Overdue (2)" danger section · "Due this month" · "Upcoming" · quick links: Assets, Log, Contractors, Reports · house selector if more than one house.
- States: `empty` (EmptyState "Add your first asset or task") · `nothing-due` · `populated` · `offline`.
- Interactions: tap task → E3; swipe right "Done" → E4 sheet.
- Offline: full. · Gating: tab hidden when off.

### E2. Tasks list
- Route: `/maintenance/tasks` · Purpose: MAINT-03, 04, 15.
- Layout: filter chips (All, Mine, Overdue, Recurring, One-off, Season) · rows: title, asset chip, next due, assignee avatar · "+".
- States: `empty` · `populated` · `filtered-empty`.
- Offline: full.

### E3. Task detail
- Route: `/maintenance/tasks/:id` · Purpose: MAINT-03, 05, 16.
- Layout: title · asset link · schedule line ("Every 3 months · next 12 Mar") · assignee · description · "Consumables" list with "Send to shopping list" (if shopping on) · "Mark done" primary · history of log entries · edit.
- States: `recurring` · `one-off` · `overdue` (danger header) · `no-consumables`.
- Interactions: Mark done → E4; Send to list → API → toast "3 items added to Groceries".
- Offline: full except Send to list (needs API; button shows offline hint). · Gating: consumables section only if shopping on.

### E4. Mark done sheet
- Route: sheet · Purpose: MAINT-05.
- Layout: done date (default today) · who (default me) · contractor picker (+ new) · cost MoneyField · notes · photos · "Save" → shows "Next due: 12 Jun 2027".
- States: `new` · `saving` · `offline-queued` (next due computed locally with the same rrule logic, confirmed by server).
- Offline: full (log entry + task update via outbox).

### E5. Task edit
- Route: sheet · Purpose: MAINT-03, 04, 15.
- Layout: title · asset picker · RRulePicker (or "One-off" with date) · assignee picker · season picker · description · consumables editor (name, qty, unit rows) · delete.
- States: `new` · `edit`.
- Offline: full.

### E6. Log
- Route: `/maintenance/log` · Purpose: MAINT-06, 13.
- Layout: filter by asset/year · rows: date, title, asset, cost, who · "Export PDF" in TopBar.
- States: `empty` · `populated` · `exporting`.
- Offline: list works; export needs API.

### E7. Log entry detail
- Route: `/maintenance/log/:id` · Purpose: MAINT-06.
- Layout: task/asset links · date, who, contractor, cost · notes · photo grid · edit / delete.
- Offline: full.

### E8. Assets
- Route: `/maintenance/assets` · Purpose: MAINT-02.
- Layout: group by room · rows: name, kind icon, warranty caption (warn within 30 d) · "+" · "Scan QR".
- States: `empty` · `populated`.
- Offline: full.

### E9. Asset detail
- Route: `/maintenance/assets/:id` · Purpose: MAINT-02, 10, 11, 14.
- Layout: photo header · name, kind, brand, model, serial (copyable) · room · purchase date/price · warranty until with badge · attachments (manuals) · tasks for this asset · log entries with total cost this year · "QR label" → E10 · edit.
- States: `populated` · `warranty-expiring` · `warranty-expired` · `no-tasks`.
- Offline: full; attachment upload queued.

### E10. QR label
- Route: sheet · Purpose: MAINT-14.
- Layout: QR code large · asset name · "Print" / "Save image".
- Offline: generated locally.

### E11. Asset edit
- Route: sheet · Fields as E9 plus house picker.

### E12. House profile
- Route: `/maintenance/house` · Purpose: MAINT-01.
- Layout: name, address, build year, notes · list of houses if several · "+".
- Offline: full.

### E13. Contractors
- Route: `/maintenance/contractors` · Purpose: MAINT-09.
- Layout: rows: name, trade, phone (tap to call), email (tap to mail) · "+".
- Offline: full.

### E14. Checklist templates
- Route: `/maintenance/templates` · Purpose: MAINT-12.
- Layout: Global section (Spring, Autumn, …) · Household section · tap → items preview with "Apply to house" → creates tasks → toast.
- States: `global-only` · `with-custom` · `applying`.
- Offline: apply needs API; preview works.

### E15. Cost report
- Route: `/maintenance/reports` · Purpose: MAINT-10.
- Layout: year picker · total · by asset (bars, inline) · by month (bars) · "Export PDF".
- States: `no-data` · `populated`.
- Offline: computed locally from log; PDF needs API.

---

## Part F — Navigation map

```
Tabs: [Shopping C1] [Pantry D1] [Maintenance E1] [Home B4]
B4 → B3 (switcher) B5 B6 B7 B8 B10 B11
C1 → C2 → C3, C6, C7, C8 ; C1 → C4 → C5
D1 → D2, D3, D4, D5, D6 → D7 ; D1 → C7
E1 → E2 → E3 → E4, E5 ; E1 → E6 → E7 ; E1 → E8 → E9 → E10, E11 ; E1 → E12, E13, E14, E15
Any TopBar → B12 (sync)
Public (web): C9
```

## Part G — Accessibility checklist (every screen)
- All controls labelled; checkbox state announced; badges have text, not colour only.
- Focus order follows layout; sheets trap focus; escape/back closes.
- Contrast ≥ 4.5:1 for text, ≥ 3:1 for icons.
- Supports font scaling to 200 % without clipping; lists reflow.
- Reduced motion: no animated transitions beyond fades.
