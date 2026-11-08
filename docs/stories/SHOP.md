# Shopping stories

Source of truth: `docs/SPEC.md` §4.2. Offline lines are §6.5 (Shopping row). Module-off lines are §9 (rule X7 and the matrix). One story per feature ID. Where the feature is more than one of API, web, iOS, and Android, it is split with the suffix `-api`, `-web`, `-ios`, or `-android`. Order inside a feature is API, then web, then iOS, then Android.

An open question is unresolved in the spec. Do not pick an answer while implementing the story.

## SHOP-01-web — Multiple shopping lists

**User value:** The household can keep more than one list, name them, park one, and remove one.

**Acceptance criteria:**
- [ ] The user can create a list.
- [ ] The user can rename a list.
- [ ] The user can archive a list.
- [ ] The user can delete a list.
- [ ] The household has a default list.

**Offline:** §6.5 Shopping: readable offline, and every write works offline and is queued until sync.

**Module off:** Shopping off (X7): the shopping tab is hidden and a shopping-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `CORE-02-web`, `CORE-05-web`

**Skill:** `.claude/skills/react-web/SKILL.md`

**Open question:** CORE-02 creates the default list named Groceries. §4 does not say whether the user can pick a different list as the default afterwards.

## SHOP-01-ios — Multiple shopping lists

**User value:** The household can keep more than one list, name them, park one, and remove one.

**Acceptance criteria:**
- [ ] The user can create a list.
- [ ] The user can rename a list.
- [ ] The user can archive a list.
- [ ] The user can delete a list.
- [ ] The household has a default list.

**Offline:** §6.5 Shopping: readable offline, and every write works offline and is queued until sync.

**Module off:** Shopping off (X7): the shopping tab is hidden and a shopping-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `CORE-02-ios`, `CORE-05-ios`

**Skill:** `.claude/skills/swift-ios/SKILL.md`

**Open question:** CORE-02 creates the default list named Groceries. §4 does not say whether the user can pick a different list as the default afterwards.

## SHOP-01-android — Multiple shopping lists

**User value:** The household can keep more than one list, name them, park one, and remove one.

**Acceptance criteria:**
- [ ] The user can create a list.
- [ ] The user can rename a list.
- [ ] The user can archive a list.
- [ ] The user can delete a list.
- [ ] The household has a default list.

**Offline:** §6.5 Shopping: readable offline, and every write works offline and is queued until sync.

**Module off:** Shopping off (X7): the shopping tab is hidden and a shopping-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `CORE-02-android`, `CORE-05-android`

**Skill:** `.claude/skills/kotlin-android/SKILL.md`

**Open question:** CORE-02 creates the default list named Groceries. §4 does not say whether the user can pick a different list as the default afterwards.

## SHOP-02-web — Add an item with autocomplete

**User value:** Adding something the household has bought before takes a tap instead of retyping the name.

**Acceptance criteria:**
- [ ] Typing a name suggests matches from this household's item history.
- [ ] Matching uses `name_key`.
- [ ] The list shows the item's `name`, not the key.

**Offline:** §6.5 Shopping: readable offline, and every write works offline and is queued until sync.

**Module off:** Shopping off (X7): the shopping tab is hidden and a shopping-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `SHOP-01-web`

**Skill:** `.claude/skills/react-web/SKILL.md`

## SHOP-02-ios — Add an item with autocomplete

**User value:** Adding something the household has bought before takes a tap instead of retyping the name.

**Acceptance criteria:**
- [ ] Typing a name suggests matches from this household's item history.
- [ ] Matching uses `name_key`.
- [ ] The list shows the item's `name`, not the key.

**Offline:** §6.5 Shopping: readable offline, and every write works offline and is queued until sync.

**Module off:** Shopping off (X7): the shopping tab is hidden and a shopping-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `SHOP-01-ios`

**Skill:** `.claude/skills/swift-ios/SKILL.md`

## SHOP-02-android — Add an item with autocomplete

**User value:** Adding something the household has bought before takes a tap instead of retyping the name.

**Acceptance criteria:**
- [ ] Typing a name suggests matches from this household's item history.
- [ ] Matching uses `name_key`.
- [ ] The list shows the item's `name`, not the key.

**Offline:** §6.5 Shopping: readable offline, and every write works offline and is queued until sync.

**Module off:** Shopping off (X7): the shopping tab is hidden and a shopping-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `SHOP-01-android`

**Skill:** `.claude/skills/kotlin-android/SKILL.md`

## SHOP-03-web — Quantity, unit, and note

**User value:** A list line can say how much, in which unit, and with a note, and those details can be left blank.

**Acceptance criteria:**
- [ ] Quantity is optional.
- [ ] Unit is optional.
- [ ] Note is optional.
- [ ] The user can set or clear each one on an item.

**Offline:** §6.5 Shopping: readable offline, and every write works offline and is queued until sync.

**Module off:** Shopping off (X7): the shopping tab is hidden and a shopping-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `SHOP-01-web`

**Skill:** `.claude/skills/react-web/SKILL.md`

## SHOP-03-ios — Quantity, unit, and note

**User value:** A list line can say how much, in which unit, and with a note, and those details can be left blank.

**Acceptance criteria:**
- [ ] Quantity is optional.
- [ ] Unit is optional.
- [ ] Note is optional.
- [ ] The user can set or clear each one on an item.

**Offline:** §6.5 Shopping: readable offline, and every write works offline and is queued until sync.

**Module off:** Shopping off (X7): the shopping tab is hidden and a shopping-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `SHOP-01-ios`

**Skill:** `.claude/skills/swift-ios/SKILL.md`

## SHOP-03-android — Quantity, unit, and note

**User value:** A list line can say how much, in which unit, and with a note, and those details can be left blank.

**Acceptance criteria:**
- [ ] Quantity is optional.
- [ ] Unit is optional.
- [ ] Note is optional.
- [ ] The user can set or clear each one on an item.

**Offline:** §6.5 Shopping: readable offline, and every write works offline and is queued until sync.

**Module off:** Shopping off (X7): the shopping tab is hidden and a shopping-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `SHOP-01-android`

**Skill:** `.claude/skills/kotlin-android/SKILL.md`

## SHOP-04-web — Check off, undo, and clear

**User value:** In the aisle a person can tick an item, take it back within a few seconds, and clear the finished ones.

**Acceptance criteria:**
- [ ] Checking an item off marks it checked.
- [ ] Undo is available for 5 seconds.
- [ ] Checked items collapse to the bottom of the list.
- [ ] The user can clear checked items.

**Offline:** §6.5 Shopping: readable offline, and every write works offline and is queued until sync.

**Module off:** Shopping off (X7): the shopping tab is hidden and a shopping-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `SHOP-01-web`

**Skill:** `.claude/skills/react-web/SKILL.md`

**Open question:** §4 does not say whether the check-off mutation waits for the 5 second undo window before it is pushed.

## SHOP-04-ios — Check off, undo, and clear

**User value:** In the aisle a person can tick an item, take it back within a few seconds, and clear the finished ones.

**Acceptance criteria:**
- [ ] Checking an item off marks it checked.
- [ ] Undo is available for 5 seconds.
- [ ] Checked items collapse to the bottom of the list.
- [ ] The user can clear checked items.

**Offline:** §6.5 Shopping: readable offline, and every write works offline and is queued until sync.

**Module off:** Shopping off (X7): the shopping tab is hidden and a shopping-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `SHOP-01-ios`

**Skill:** `.claude/skills/swift-ios/SKILL.md`

**Open question:** §4 does not say whether the check-off mutation waits for the 5 second undo window before it is pushed.

## SHOP-04-android — Check off, undo, and clear

**User value:** In the aisle a person can tick an item, take it back within a few seconds, and clear the finished ones.

**Acceptance criteria:**
- [ ] Checking an item off marks it checked.
- [ ] Undo is available for 5 seconds.
- [ ] Checked items collapse to the bottom of the list.
- [ ] The user can clear checked items.

**Offline:** §6.5 Shopping: readable offline, and every write works offline and is queued until sync.

**Module off:** Shopping off (X7): the shopping tab is hidden and a shopping-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `SHOP-01-android`

**Skill:** `.claude/skills/kotlin-android/SKILL.md`

**Open question:** §4 does not say whether the check-off mutation waits for the 5 second undo window before it is pushed.

## SHOP-05-web — Stores

**User value:** The household can name the shops it uses and point a list at one of them.

**Acceptance criteria:**
- [ ] The user can create a store.
- [ ] The user can assign a list to a store.

**Offline:** §6.5 Shopping: readable offline, and every write works offline and is queued until sync.

**Module off:** Shopping off (X7): the shopping tab is hidden and a shopping-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `SHOP-01-web`

**Skill:** `.claude/skills/react-web/SKILL.md`

## SHOP-05-ios — Stores

**User value:** The household can name the shops it uses and point a list at one of them.

**Acceptance criteria:**
- [ ] The user can create a store.
- [ ] The user can assign a list to a store.

**Offline:** §6.5 Shopping: readable offline, and every write works offline and is queued until sync.

**Module off:** Shopping off (X7): the shopping tab is hidden and a shopping-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `SHOP-01-ios`

**Skill:** `.claude/skills/swift-ios/SKILL.md`

## SHOP-05-android — Stores

**User value:** The household can name the shops it uses and point a list at one of them.

**Acceptance criteria:**
- [ ] The user can create a store.
- [ ] The user can assign a list to a store.

**Offline:** §6.5 Shopping: readable offline, and every write works offline and is queued until sync.

**Module off:** Shopping off (X7): the shopping tab is hidden and a shopping-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `SHOP-01-android`

**Skill:** `.claude/skills/kotlin-android/SKILL.md`

## SHOP-06-api — Learn aisle order on check-off

**User value:** Each check-off at a store teaches the household the order they actually walk that shop.

**Acceptance criteria:**
- [ ] When an item is checked off and the list has a store, `store_item_order.mean_position` for that store and `name_key` is updated (X4).
- [ ] A list with no store does not change learned order.

**Offline:** §6.5 Shopping: writes are done offline and must still apply when the client pushes them later. Push is idempotent for 24 hours (§6.3).

**Module off:** Shopping off (X7): the check-off mutation is rejected with `module_disabled`, so nothing is learned. X4's own gate is "the list has a store".

**Dependencies:** none

**Skill:** `.claude/skills/sql-supabase/SKILL.md`

**Open question:** X4 runs in Postgres when the row is updated. An offline check-off arrives later via sync. Confirm the trigger still runs on that synced update. Store assignment is SHOP-05 on the clients; this story does not add a store API.

## SHOP-06-web — Learned aisle order

**User value:** The list sorts itself into the order the household usually walks that store, with new items at the bottom.

**Acceptance criteria:**
- [ ] Items are sorted by `store_item_order.mean_position` for the list's store.
- [ ] Items the store has not seen yet sort at the bottom.
- [ ] A list with no store does not use learned order.

**Offline:** §6.5 Shopping: readable offline, and every write works offline and is queued until sync.

**Module off:** Shopping off (X7): the shopping tab is hidden and a shopping-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error. Shopping only (§9 matrix) still includes aisle learning.

**Dependencies:** `SHOP-05-web`, `SHOP-06-api`, `SHOP-04-web`

**Skill:** `.claude/skills/react-web/SKILL.md`

## SHOP-06-ios — Learned aisle order

**User value:** The list sorts itself into the order the household usually walks that store, with new items at the bottom.

**Acceptance criteria:**
- [ ] Items are sorted by `store_item_order.mean_position` for the list's store.
- [ ] Items the store has not seen yet sort at the bottom.
- [ ] A list with no store does not use learned order.

**Offline:** §6.5 Shopping: readable offline, and every write works offline and is queued until sync.

**Module off:** Shopping off (X7): the shopping tab is hidden and a shopping-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error. Shopping only (§9 matrix) still includes aisle learning.

**Dependencies:** `SHOP-05-ios`, `SHOP-06-api`, `SHOP-04-ios`

**Skill:** `.claude/skills/swift-ios/SKILL.md`

## SHOP-06-android — Learned aisle order

**User value:** The list sorts itself into the order the household usually walks that store, with new items at the bottom.

**Acceptance criteria:**
- [ ] Items are sorted by `store_item_order.mean_position` for the list's store.
- [ ] Items the store has not seen yet sort at the bottom.
- [ ] A list with no store does not use learned order.

**Offline:** §6.5 Shopping: readable offline, and every write works offline and is queued until sync.

**Module off:** Shopping off (X7): the shopping tab is hidden and a shopping-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error. Shopping only (§9 matrix) still includes aisle learning.

**Dependencies:** `SHOP-05-android`, `SHOP-06-api`, `SHOP-04-android`

**Skill:** `.claude/skills/kotlin-android/SKILL.md`

## SHOP-07-web — Manual drag-to-reorder

**User value:** A person can drag the list into the order they want, and that drag also teaches the store.

**Acceptance criteria:**
- [ ] Dragging an item sets `manual_position`.
- [ ] The list displays that manual order.
- [ ] The drag also teaches the learned aisle order.

**Offline:** §6.5 Shopping: readable offline, and every write works offline and is queued until sync.

**Module off:** Shopping off (X7): the shopping tab is hidden and a shopping-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `SHOP-06-web`

**Skill:** `.claude/skills/react-web/SKILL.md`

**Open question:** X4 teaches order on check-off, not on drag. §4 says the drag also teaches learned order and does not say whether the client writes `store_item_order` or the server does.

## SHOP-07-ios — Manual drag-to-reorder

**User value:** A person can drag the list into the order they want, and that drag also teaches the store.

**Acceptance criteria:**
- [ ] Dragging an item sets `manual_position`.
- [ ] The list displays that manual order.
- [ ] The drag also teaches the learned aisle order.

**Offline:** §6.5 Shopping: readable offline, and every write works offline and is queued until sync.

**Module off:** Shopping off (X7): the shopping tab is hidden and a shopping-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `SHOP-06-ios`

**Skill:** `.claude/skills/swift-ios/SKILL.md`

**Open question:** X4 teaches order on check-off, not on drag. §4 says the drag also teaches learned order and does not say whether the client writes `store_item_order` or the server does.

## SHOP-07-android — Manual drag-to-reorder

**User value:** A person can drag the list into the order they want, and that drag also teaches the store.

**Acceptance criteria:**
- [ ] Dragging an item sets `manual_position`.
- [ ] The list displays that manual order.
- [ ] The drag also teaches the learned aisle order.

**Offline:** §6.5 Shopping: readable offline, and every write works offline and is queued until sync.

**Module off:** Shopping off (X7): the shopping tab is hidden and a shopping-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `SHOP-06-android`

**Skill:** `.claude/skills/kotlin-android/SKILL.md`

**Open question:** X4 teaches order on check-off, not on drag. §4 says the drag also teaches learned order and does not say whether the client writes `store_item_order` or the server does.

## SHOP-08-api — Queued sync for list operations

**User value:** A trip with no signal still reaches the household once the phone is online again.

**Acceptance criteria:**
- [ ] `POST /sync` applies the queued list-operation mutations in order, in one transaction.
- [ ] A mutation id that was already applied is accepted again without writing twice, for 24 hours (§6.3).
- [ ] `GET /sync` returns the changed rows above the cursor, including rows with `deleted_at` set, and a cursor the client stores.

**Offline:** §6.5 Shopping: writes are done offline and must still apply when the client pushes them later. Push is idempotent for 24 hours (§6.3).

**Module off:** With shopping off, a mutation for a shopping table is rejected with `module_disabled` (X7) and the rest of the push still follows §6.3.

**Dependencies:** `CORE-05-api`, `CORE-02-api`

**Skill:** `.claude/skills/node-backend/SKILL.md`

**Open question:** §7 defines one sync endpoint for every household table. This acceptance summary covers list operations. Which tables does this story register, and which later stories extend the same endpoint?

## SHOP-08-web — Full offline list with queued sync

**User value:** Every list operation still works in the shop with no signal, and catches up when the signal returns.

**Acceptance criteria:**
- [ ] Every shopping-list operation works with no network.
- [ ] The write hits the local database and the outbox in one local transaction (§6.1).
- [ ] When the network returns, the client pushes the outbox and then pulls.

**Offline:** §6.5 Shopping: readable offline, and every write works offline and is queued until sync.

**Module off:** Shopping off (X7): the shopping tab is hidden and a shopping-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `SHOP-08-api`, `CORE-07-web`, `SHOP-01-web`

**Skill:** `.claude/skills/react-web/SKILL.md`

**Open question:** §6.4 also pushes on foreground, every 5 minutes while in the foreground, on a Realtime event, and in the background (iOS `BGAppRefreshTask`, Android WorkManager every 15 minutes). §3 also names a service worker for the web offline shell. The acceptance summary only says that operations work offline and sync on reconnect. Which of those triggers belong to this story?

## SHOP-08-ios — Full offline list with queued sync

**User value:** Every list operation still works in the shop with no signal, and catches up when the signal returns.

**Acceptance criteria:**
- [ ] Every shopping-list operation works with no network.
- [ ] The write hits the local database and the outbox in one local transaction (§6.1).
- [ ] When the network returns, the client pushes the outbox and then pulls.

**Offline:** §6.5 Shopping: readable offline, and every write works offline and is queued until sync.

**Module off:** Shopping off (X7): the shopping tab is hidden and a shopping-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `SHOP-08-api`, `CORE-07-ios`, `SHOP-01-ios`

**Skill:** `.claude/skills/swift-ios/SKILL.md`

**Open question:** §6.4 also pushes on foreground, every 5 minutes while in the foreground, on a Realtime event, and in the background (iOS `BGAppRefreshTask`, Android WorkManager every 15 minutes). §3 also names a service worker for the web offline shell. The acceptance summary only says that operations work offline and sync on reconnect. Which of those triggers belong to this story?

## SHOP-08-android — Full offline list with queued sync

**User value:** Every list operation still works in the shop with no signal, and catches up when the signal returns.

**Acceptance criteria:**
- [ ] Every shopping-list operation works with no network.
- [ ] The write hits the local database and the outbox in one local transaction (§6.1).
- [ ] When the network returns, the client pushes the outbox and then pulls.

**Offline:** §6.5 Shopping: readable offline, and every write works offline and is queued until sync.

**Module off:** Shopping off (X7): the shopping tab is hidden and a shopping-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `SHOP-08-api`, `CORE-07-android`, `SHOP-01-android`

**Skill:** `.claude/skills/kotlin-android/SKILL.md`

**Open question:** §6.4 also pushes on foreground, every 5 minutes while in the foreground, on a Realtime event, and in the background (iOS `BGAppRefreshTask`, Android WorkManager every 15 minutes). §3 also names a service worker for the web offline shell. The acceptance summary only says that operations work offline and sync on reconnect. Which of those triggers belong to this story?

## SHOP-09-api — Realtime publication for check-offs

**User value:** When one person ticks milk, the others have a signal to refresh instead of waiting.

**Acceptance criteria:**
- [ ] A change to a shopping row used by check-off is published on Supabase Realtime for that household.

**Offline:** Realtime is an online signal. The rows it points at remain readable offline after a pull (§6.5).

**Module off:** Shopping off: check-offs are rejected (X7), so no check-off event is published for a new check-off.

**Dependencies:** `SHOP-08-api`

**Skill:** `.claude/skills/sql-supabase/SKILL.md`

**Open question:** §14 adds one publication covering every module's tables. Does this story publish only the shopping tables a check-off touches?

## SHOP-09-web — Realtime check-offs

**User value:** A check-off by someone else shows up on this device without a manual reload.

**Acceptance criteria:**
- [ ] The client subscribes to Realtime changes for the household.
- [ ] The event payload is ignored.
- [ ] The event triggers a pull, and the other person's check-off appears.

**Offline:** §6.5 Shopping: readable offline, and every write works offline and is queued until sync. The live update itself needs a network. Until then the local list is what §6.5 shows.

**Module off:** Shopping off (X7): the shopping tab is hidden and a shopping-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `SHOP-04-web`, `SHOP-08-web`, `SHOP-09-api`

**Skill:** `.claude/skills/react-web/SKILL.md`

## SHOP-09-ios — Realtime check-offs

**User value:** A check-off by someone else shows up on this device without a manual reload.

**Acceptance criteria:**
- [ ] The client subscribes to Realtime changes for the household.
- [ ] The event payload is ignored.
- [ ] The event triggers a pull, and the other person's check-off appears.

**Offline:** §6.5 Shopping: readable offline, and every write works offline and is queued until sync. The live update itself needs a network. Until then the local list is what §6.5 shows.

**Module off:** Shopping off (X7): the shopping tab is hidden and a shopping-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `SHOP-04-ios`, `SHOP-08-ios`, `SHOP-09-api`

**Skill:** `.claude/skills/swift-ios/SKILL.md`

## SHOP-09-android — Realtime check-offs

**User value:** A check-off by someone else shows up on this device without a manual reload.

**Acceptance criteria:**
- [ ] The client subscribes to Realtime changes for the household.
- [ ] The event payload is ignored.
- [ ] The event triggers a pull, and the other person's check-off appears.

**Offline:** §6.5 Shopping: readable offline, and every write works offline and is queued until sync. The live update itself needs a network. Until then the local list is what §6.5 shows.

**Module off:** Shopping off (X7): the shopping tab is hidden and a shopping-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `SHOP-04-android`, `SHOP-08-android`, `SHOP-09-api`

**Skill:** `.claude/skills/kotlin-android/SKILL.md`

## SHOP-10-api — Barcode product lookup

**User value:** A barcode the household has seen, or one Open Food Facts knows, comes back as a product name.

**Acceptance criteria:**
- [ ] `GET /products/{barcode}` returns the cached product when one exists.
- [ ] Otherwise it looks the barcode up in Open Food Facts and caches the product.
- [ ] An unknown barcode returns 404.

**Offline:** The lookup needs a network. Shopping list writes that use the returned name still follow §6.5 once the name is on the device.

**Module off:** The product cache is global (§11), not gated by the shopping flag. Adding the resulting list item is rejected when shopping is off (X7).

**Dependencies:** none

**Skill:** `.claude/skills/node-backend/SKILL.md`

## SHOP-10-web — Barcode scan to add

**User value:** A person can scan a barcode and add that product to the list.

**Acceptance criteria:**
- [ ] The camera scans a barcode.
- [ ] The client calls `GET /products/{barcode}`.
- [ ] The returned product name is used as the item name.

**Offline:** §6.5 Shopping: readable offline, and every write works offline and is queued until sync. The lookup itself needs a network (SHOP-10-api). Adding a typed name with no lookup is a normal offline list write.

**Module off:** Shopping off (X7): the shopping tab is hidden and a shopping-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `SHOP-10-api`, `SHOP-02-web`

**Skill:** `.claude/skills/react-web/SKILL.md`

**Open question:** §4 does not say what the screen does on a 404.

## SHOP-10-ios — Barcode scan to add

**User value:** A person can scan a barcode and add that product to the list.

**Acceptance criteria:**
- [ ] The camera scans a barcode.
- [ ] The client calls `GET /products/{barcode}`.
- [ ] The returned product name is used as the item name.

**Offline:** §6.5 Shopping: readable offline, and every write works offline and is queued until sync. The lookup itself needs a network (SHOP-10-api). Adding a typed name with no lookup is a normal offline list write.

**Module off:** Shopping off (X7): the shopping tab is hidden and a shopping-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `SHOP-10-api`, `SHOP-02-ios`

**Skill:** `.claude/skills/swift-ios/SKILL.md`

**Open question:** §4 does not say what the screen does on a 404.

## SHOP-10-android — Barcode scan to add

**User value:** A person can scan a barcode and add that product to the list.

**Acceptance criteria:**
- [ ] The camera scans a barcode.
- [ ] The client calls `GET /products/{barcode}`.
- [ ] The returned product name is used as the item name.

**Offline:** §6.5 Shopping: readable offline, and every write works offline and is queued until sync. The lookup itself needs a network (SHOP-10-api). Adding a typed name with no lookup is a normal offline list write.

**Module off:** Shopping off (X7): the shopping tab is hidden and a shopping-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `SHOP-10-api`, `SHOP-02-android`

**Skill:** `.claude/skills/kotlin-android/SKILL.md`

**Open question:** §4 does not say what the screen does on a 404.

## SHOP-11-web — Template lists

**User value:** The household can keep a template and pour all of its lines onto a real list in one tap.

**Acceptance criteria:**
- [ ] The user can mark a list as a template.
- [ ] "Add all to list" copies that template onto a list in one tap.

**Offline:** §6.5 Shopping: readable offline, and every write works offline and is queued until sync.

**Module off:** Shopping off (X7): the shopping tab is hidden and a shopping-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error. Shopping only (§9 matrix) still includes templates.

**Dependencies:** `SHOP-01-web`

**Skill:** `.claude/skills/react-web/SKILL.md`

## SHOP-11-ios — Template lists

**User value:** The household can keep a template and pour all of its lines onto a real list in one tap.

**Acceptance criteria:**
- [ ] The user can mark a list as a template.
- [ ] "Add all to list" copies that template onto a list in one tap.

**Offline:** §6.5 Shopping: readable offline, and every write works offline and is queued until sync.

**Module off:** Shopping off (X7): the shopping tab is hidden and a shopping-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error. Shopping only (§9 matrix) still includes templates.

**Dependencies:** `SHOP-01-ios`

**Skill:** `.claude/skills/swift-ios/SKILL.md`

## SHOP-11-android — Template lists

**User value:** The household can keep a template and pour all of its lines onto a real list in one tap.

**Acceptance criteria:**
- [ ] The user can mark a list as a template.
- [ ] "Add all to list" copies that template onto a list in one tap.

**Offline:** §6.5 Shopping: readable offline, and every write works offline and is queued until sync.

**Module off:** Shopping off (X7): the shopping tab is hidden and a shopping-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error. Shopping only (§9 matrix) still includes templates.

**Dependencies:** `SHOP-01-android`

**Skill:** `.claude/skills/kotlin-android/SKILL.md`

## SHOP-12-web — Price memory per store

**User value:** Checking something off can record what it cost at that store, and the next time the last price is sitting next to the item.

**Acceptance criteria:**
- [ ] On check-off the user can enter a price for the list's store.
- [ ] The last price for that store and item is shown next to the item.

**Offline:** §6.5 Shopping: readable offline, and every write works offline and is queued until sync.

**Module off:** Shopping off (X7): the shopping tab is hidden and a shopping-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error. Shopping only (§9 matrix) still includes prices.

**Dependencies:** `SHOP-04-web`, `SHOP-05-web`

**Skill:** `.claude/skills/react-web/SKILL.md`

## SHOP-12-ios — Price memory per store

**User value:** Checking something off can record what it cost at that store, and the next time the last price is sitting next to the item.

**Acceptance criteria:**
- [ ] On check-off the user can enter a price for the list's store.
- [ ] The last price for that store and item is shown next to the item.

**Offline:** §6.5 Shopping: readable offline, and every write works offline and is queued until sync.

**Module off:** Shopping off (X7): the shopping tab is hidden and a shopping-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error. Shopping only (§9 matrix) still includes prices.

**Dependencies:** `SHOP-04-ios`, `SHOP-05-ios`

**Skill:** `.claude/skills/swift-ios/SKILL.md`

## SHOP-12-android — Price memory per store

**User value:** Checking something off can record what it cost at that store, and the next time the last price is sitting next to the item.

**Acceptance criteria:**
- [ ] On check-off the user can enter a price for the list's store.
- [ ] The last price for that store and item is shown next to the item.

**Offline:** §6.5 Shopping: readable offline, and every write works offline and is queued until sync.

**Module off:** Shopping off (X7): the shopping tab is hidden and a shopping-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error. Shopping only (§9 matrix) still includes prices.

**Dependencies:** `SHOP-04-android`, `SHOP-05-android`

**Skill:** `.claude/skills/kotlin-android/SKILL.md`

## SHOP-13-api — Read-only share link

**User value:** The household can hand someone a link that shows the list and nothing else, and can take the link back.

**Acceptance criteria:**
- [ ] `POST /lists/{id}/share` returns a public URL.
- [ ] `DELETE /lists/{id}/share` revokes it.
- [ ] `GET /shared/{token}` is public and needs no auth.

**Offline:** Creating or revoking the link needs a network. §6.5 does not list share-link management as an offline shopping write. The list being shared is still readable offline by members.

**Module off:** Shopping off (X7): creating or revoking a share is rejected.

**Dependencies:** `CORE-02-api`

**Skill:** `.claude/skills/node-backend/SKILL.md`

**Open question:** §7 returns `{ list: ShoppingList, items: ListItem[] }`. §11 says the public path returns only the list name and the unchecked item names and quantities. Which payload is it? §4 does not say whether iOS and Android offer create and revoke, or only the web, so there is no native story yet. §9 does not say whether a token that already exists keeps serving after shopping is switched off.

## SHOP-13-web — Read-only share page

**User value:** Someone without an account can open the link and read the list.

**Acceptance criteria:**
- [ ] The public URL from SHOP-13-api renders the list with no sign-in.
- [ ] The page is read-only.

**Offline:** §6.5 is about the signed-in shopping client. This page is a public read and needs a network to load.

**Module off:** Shopping off: creating a new link is refused (SHOP-13-api).

**Dependencies:** `SHOP-13-api`

**Skill:** `.claude/skills/react-web/SKILL.md`

**Open question:** Same payload question as SHOP-13-api: full rows (§7) or only the list name and unchecked names and quantities (§11). §9 does not say whether a public URL keeps rendering after shopping is switched off.

## SHOP-14-web — Voice add

**User value:** A person can say what to buy, including a quantity and a unit, and the line lands on the list.

**Acceptance criteria:**
- [ ] Speech is captured with the platform speech-to-text.
- [ ] A phrase such as "2 litres milk" is parsed into name, quantity, and unit.
- [ ] The parsed item is added to the list.

**Offline:** §6.5 Shopping: readable offline, and every write works offline and is queued until sync. §4 does not say whether speech-to-text itself works offline.

**Module off:** Shopping off (X7): the shopping tab is hidden and a shopping-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `SHOP-03-web`

**Skill:** `.claude/skills/react-web/SKILL.md`

**Open question:** §4 does not say whether speech-to-text is required to work offline or may use an online recogniser.

## SHOP-14-ios — Voice add

**User value:** A person can say what to buy, including a quantity and a unit, and the line lands on the list.

**Acceptance criteria:**
- [ ] Speech is captured with the platform speech-to-text.
- [ ] A phrase such as "2 litres milk" is parsed into name, quantity, and unit.
- [ ] The parsed item is added to the list.

**Offline:** §6.5 Shopping: readable offline, and every write works offline and is queued until sync. §4 does not say whether speech-to-text itself works offline.

**Module off:** Shopping off (X7): the shopping tab is hidden and a shopping-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `SHOP-03-ios`

**Skill:** `.claude/skills/swift-ios/SKILL.md`

**Open question:** §4 does not say whether speech-to-text is required to work offline or may use an online recogniser.

## SHOP-14-android — Voice add

**User value:** A person can say what to buy, including a quantity and a unit, and the line lands on the list.

**Acceptance criteria:**
- [ ] Speech is captured with the platform speech-to-text.
- [ ] A phrase such as "2 litres milk" is parsed into name, quantity, and unit.
- [ ] The parsed item is added to the list.

**Offline:** §6.5 Shopping: readable offline, and every write works offline and is queued until sync. §4 does not say whether speech-to-text itself works offline.

**Module off:** Shopping off (X7): the shopping tab is hidden and a shopping-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `SHOP-03-android`

**Skill:** `.claude/skills/kotlin-android/SKILL.md`

**Open question:** §4 does not say whether speech-to-text is required to work offline or may use an online recogniser.

## SHOP-15-ios — Siri shortcut

**User value:** A person can tell Siri to add something to the shopping list.

**Acceptance criteria:**
- [ ] A Siri shortcut handles "Add X to my shopping list" by adding an item named X.

**Offline:** §6.5 Shopping: readable offline, and every write works offline and is queued until sync.

**Module off:** Shopping off (X7): the shopping tab is hidden and a shopping-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `SHOP-01-ios`

**Skill:** `.claude/skills/swift-ios/SKILL.md`

**Open question:** The feature names Siri and Google Assistant, so there is no web story. It does not say which household or which list is used when the user has several, or whether the add works while the app is not running.

## SHOP-15-android — Google Assistant shortcut

**User value:** A person can tell Google Assistant to add something to the shopping list.

**Acceptance criteria:**
- [ ] A Google Assistant shortcut handles "Add X to my shopping list" by adding an item named X.

**Offline:** §6.5 Shopping: readable offline, and every write works offline and is queued until sync.

**Module off:** Shopping off (X7): the shopping tab is hidden and a shopping-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `SHOP-01-android`

**Skill:** `.claude/skills/kotlin-android/SKILL.md`

**Open question:** The feature names Siri and Google Assistant, so there is no web story. It does not say which household or which list is used when the user has several, or whether the add works while the app is not running.

## SHOP-16-web — Pantry-sourced items

**User value:** A line that came from the pantry is marked, and checking it off offers to put the stock back.

**Acceptance criteria:**
- [ ] An item with source pantry shows the badge "from pantry".
- [ ] Checking that item off offers "Restock pantry".

**Offline:** §6.5 Shopping: readable offline, and every write works offline and is queued until sync.

**Module off:** Shopping only (§9 matrix): no pantry badges, and check-off does not offer restock. Shopping off: the list is hidden (X7). The offer is shown only when pantry is on and the item source is pantry (X2).

**Dependencies:** `SHOP-04-web`, `PANT-14`

**Skill:** `.claude/skills/react-web/SKILL.md`

## SHOP-16-ios — Pantry-sourced items

**User value:** A line that came from the pantry is marked, and checking it off offers to put the stock back.

**Acceptance criteria:**
- [ ] An item with source pantry shows the badge "from pantry".
- [ ] Checking that item off offers "Restock pantry".

**Offline:** §6.5 Shopping: readable offline, and every write works offline and is queued until sync.

**Module off:** Shopping only (§9 matrix): no pantry badges, and check-off does not offer restock. Shopping off: the list is hidden (X7). The offer is shown only when pantry is on and the item source is pantry (X2).

**Dependencies:** `SHOP-04-ios`, `PANT-14`

**Skill:** `.claude/skills/swift-ios/SKILL.md`

## SHOP-16-android — Pantry-sourced items

**User value:** A line that came from the pantry is marked, and checking it off offers to put the stock back.

**Acceptance criteria:**
- [ ] An item with source pantry shows the badge "from pantry".
- [ ] Checking that item off offers "Restock pantry".

**Offline:** §6.5 Shopping: readable offline, and every write works offline and is queued until sync.

**Module off:** Shopping only (§9 matrix): no pantry badges, and check-off does not offer restock. Shopping off: the list is hidden (X7). The offer is shown only when pantry is on and the item source is pantry (X2).

**Dependencies:** `SHOP-04-android`, `PANT-14`

**Skill:** `.claude/skills/kotlin-android/SKILL.md`

## SHOP-17-web — Maintenance consumables on the list

**User value:** A line that was sent from a maintenance task says which job it is for.

**Acceptance criteria:**
- [ ] An item sourced from a task shows "for: " plus the task name, as in "for: Boiler service".

**Offline:** §6.5 Shopping: readable offline, and every write works offline and is queued until sync.

**Module off:** Shopping off: the list is hidden (X7). The label is for rows MAINT-16 inserts, and that action is hidden when shopping is off (§9, maintenance only).

**Dependencies:** `SHOP-01-web`, `MAINT-16-api`

**Skill:** `.claude/skills/react-web/SKILL.md`

## SHOP-17-ios — Maintenance consumables on the list

**User value:** A line that was sent from a maintenance task says which job it is for.

**Acceptance criteria:**
- [ ] An item sourced from a task shows "for: " plus the task name, as in "for: Boiler service".

**Offline:** §6.5 Shopping: readable offline, and every write works offline and is queued until sync.

**Module off:** Shopping off: the list is hidden (X7). The label is for rows MAINT-16 inserts, and that action is hidden when shopping is off (§9, maintenance only).

**Dependencies:** `SHOP-01-ios`, `MAINT-16-api`

**Skill:** `.claude/skills/swift-ios/SKILL.md`

## SHOP-17-android — Maintenance consumables on the list

**User value:** A line that was sent from a maintenance task says which job it is for.

**Acceptance criteria:**
- [ ] An item sourced from a task shows "for: " plus the task name, as in "for: Boiler service".

**Offline:** §6.5 Shopping: readable offline, and every write works offline and is queued until sync.

**Module off:** Shopping off: the list is hidden (X7). The label is for rows MAINT-16 inserts, and that action is hidden when shopping is off (§9, maintenance only).

**Dependencies:** `SHOP-01-android`, `MAINT-16-api`

**Skill:** `.claude/skills/kotlin-android/SKILL.md`
