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
| `ListRow` | Leading control (checkbox/icon), title, subtitle (secondary), trailing (badge/qty/chevron). Swipe left: delete (with undo). Swipe right: contextual (check, +1, done) |
| `Badge` | Small chip: "from pantry", "for: Boiler service", "Low", "Eat soon", "Overdue" |
| `Sheet` | Bottom sheet for add/edit; drag to dismiss; primary button pinned at bottom |
| `QuickAddBar` | Sticky input at bottom of a list: text field + mic + barcode + add. Autocomplete above it |
| `Stepper` | − value + with long-press repeat; used for quantities |
| `EmptyState` | Illustration-free: one sentence of purpose, one primary button |
| `UndoToast` | 5 s, "Deleted Milk · Undo"; stacks newest on top |
| `OfflineBanner` | Only on screens that need network (core settings): "You're offline. Connect to change household settings." |
| `SearchField` | Debounced 150 ms, local only |
| `DatePicker` | Date-only for `expires_at`, `done_at`, `next_due` |
| `MoneyField` | Locale currency, decimal input, never spinner |
| `RRulePicker` | Presets: Weekly, Monthly, Every 3 months, Twice a year, Yearly, Custom (interval + unit). Shows "Next: 12 Mar 2027" |

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
- Layout: TopBar "Test Home ▾" · "Today" card: counts (eat soon, low, due tasks) each tapping into its module view; cards hidden for disabled modules · Activity feed (last 20, CORE-08) · footer links: Members, Settings, Recently deleted.
- States: `empty-activity` · `populated` · `all-modules-off` (shows only settings prompt).
- Interactions: tap count → module view; pull to refresh → `kick()`.
- Offline: works. · Gating: cards per module.

### B5. Household settings
- Route: `/household` · Purpose: name, modules, danger zone (CORE-05).
- Layout: name field (owner) · "Modules" section with three switches + one-line description each · currency picker · "Leave household" (member) / "Delete household" (owner, requires typing name).
- States: `owner` · `member` (read-only fields) · `offline` (banner, switches disabled) · `saving`.
- Interactions: toggle module → PATCH → tabs update immediately; leave → confirm sheet → B2 or switcher.
- Offline: read-only. · Gating: none.

### B6. Members & invites
- Route: `/household/members` · Purpose: CORE-03, CORE-04.
- Layout: member rows (avatar, name, role, "you") with overflow menu (owner: make owner, remove) · "Invite" button → sheet: code (large, copyable), share link button, expiry, uses left, "New code".
- States: `owner` · `member` (no actions) · `invite-sheet` · `offline`.
- Interactions: remove → undo toast 5 s then DELETE; share → system share sheet.
- Offline: read-only. · Gating: none.

### B7. Profile
- Route: `/profile` · Purpose: CORE-06.
- Layout: avatar (tap to change) · display name field · email (read-only) · notification preferences link (B9) · sign out.
- States: `idle` · `uploading-avatar` · `offline` (avatar upload queued, name edit allowed).
- Offline: name edit via outbox. · Gating: none.

### B8. Search
- Route: `/search` (TopBar magnifier) · Purpose: CORE-09.
- Layout: SearchField autofocus · results grouped by module (section headers with module colour) · each row navigates to the entity.
- States: `empty-query` (recent searches) · `no-results` · `results`.
- Offline: works. · Gating: sections per module.

### B9. Notification preferences
- Route: `/profile/notifications` · Purpose: CORE-12.
- Layout: switches per type (Eat soon, Low stock, Task due, Warranty, Activity) per household · "System notification settings" link.
- States: `idle` · `permission-denied` (banner explaining system settings).
- Offline: via outbox. · Gating: rows hidden for disabled modules.

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

### B12. Sync sheet
- Route: from SyncStatusChip · Purpose: transparency and recovery.
- Layout: status line · "Pending changes: N" · "Failed changes: M" with list (table, action, reason) and "Retry" / "Discard" · "Sync now".
- States: `idle` · `pending` · `failed`.
- Offline: works.

---

## Part C — Shopping screens

### C1. Lists overview
- Route: `/shopping` · Purpose: SHOP-01, SHOP-11.
- Layout: TopBar "Shopping" + SyncStatusChip + "+" · sections: Lists (rows: name, store chip, "3 of 12", pantry badge count), Templates, Archived (collapsed) · FAB "New list".
- States: `empty` (EmptyState "Make your first list") · `populated` · `offline`.
- Interactions: tap → C2; swipe left archive (undo); "+" → sheet: name, store picker, "Template" switch.
- Offline: full. · Gating: tab hidden when off.

### C2. List detail  ← the most used screen
- Route: `/shopping/:listId` · Purpose: SHOP-02…09, 12, 16, 17.
- Layout: TopBar list name (tap → rename), store chip (tap → store picker), overflow (Share, Clear checked, Template actions, Archive) · unchecked items sorted per SHOP-06/07, each `ListRow`: checkbox, name, qty+unit, note, badges (from pantry / for: task), last price · divider "Checked (n)" collapsible · checked items dimmed · `QuickAddBar` sticky at bottom.
- States: `empty` (QuickAddBar focused, hint "Try: 2 l milk") · `populated` · `all-checked` (celebration line + "Clear checked") · `offline` · `pending-sync` · `reordering` (drag handles visible).
- Interactions: tap checkbox → check with undo toast; tap row → C3; long-press → drag reorder (sets `manual_position`, teaches order); check a pantry-sourced item → inline prompt "Restock pantry? [Yes] [No]" (X2, only if pantry on); swipe left → delete with undo; add via text / mic (C6) / barcode (C7); pull to refresh.
- Offline: full. · Gating: pantry prompt and badges only if pantry on; task badge only if maintenance on.

### C3. Item detail / edit
- Route: sheet · Purpose: SHOP-03, SHOP-12.
- Layout: name (autocomplete) · Stepper qty + unit picker · note · price field with store name · "Last bought: 3 Nov, €1.20 at Test Market" · delete.
- States: `new` · `edit` · `pantry-sourced` (shows link to pantry item) · `task-sourced` (shows link to task).
- Offline: full.

### C4. Stores
- Route: `/shopping/stores` · Purpose: SHOP-05.
- Layout: rows (name, "learned 42 items") · "+".
- States: `empty` · `populated`.
- Interactions: tap → C5; swipe delete with undo.
- Offline: full.

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
