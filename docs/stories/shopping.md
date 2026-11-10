# Shopping stories

One story per feature ID per layer that has work. Each body is `docs/STORY-TEMPLATE.md` verbatim. Order inside an ID is db, api, web, ios, android.

# SHOP-01-db — Shopping lists

## Summary
The household can keep more than one list, including one that is archived rather than destroyed.

## Spec references
SPEC §4 SHOP-01; §8 ShoppingList; TESTING §3 -db row

## Scope
In: shopping_list with name, archived_at, is_template, store_id, and the standard sync columns. Members can read and write; non-members cannot. The table itself is created by CORE-02-db (migration 20261108100100); this story adds only its pgTAP test and no new migration.
Out: The lists screen (SHOP-01 clients). Stores (SHOP-05). The Groceries seed (CORE-02-db). Skipped layers: -api — list CRUD is an outbox upsert, not an HTTP route. The default Groceries list is created by CORE-02-db.

## Acceptance criteria
- [ ] AC1 Given a member, when they insert a list, then the row is stored with their household id.
- [ ] AC2 Given a non-member, when they select that list, then they see nothing.
- [ ] AC3 Given a list, when archived_at is set, then the row remains and is distinguishable from a soft delete.

## Design
Screen: n/a — this layer has no DESIGN screen.
Deviations from the plan: none

## Offline behaviour
List writes are queued offline and applied under RLS when they sync (SPEC §6.5).

## Module-off behaviour
Shopping off (X7): the Shopping tab is hidden and a shopping-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: shopping_list  Endpoints: none  Types: ShoppingList
Schema change: yes — supabase/migrations/20261108110000_SHOP-01_shopping_list.sql  Contract change: no

## Tests required
Unit: n/a (database behaviour is asserted in pgTAP, TESTING §2)
Component: n/a (no UI)
Integration: supabase/tests/SHOP-01_lists.sql → AC1 member insert; AC2 non-member denied; AC3 archive keeps the row
E2E: n/a (covered by the client journeys for this ID)
Offline test: n/a (server-side)

## Dependencies
Blocked by: CORE-02-db  Blocks: SHOP-01-web, SHOP-01-ios, SHOP-01-android, SHOP-03-db, SHOP-05-db, SHOP-13-api, PANT-14-db, X3-api

## Skill
.claude/skills/sql-supabase/SKILL.md

## Open questions
none

# SHOP-01-web — Multiple lists

## Summary
The household can keep more than one list, name them, archive one, and delete one.

## Spec references
SPEC §4 SHOP-01; DESIGN C1; TESTING §3 client rows

## Scope
In: Lists overview (C1): sections for lists, templates, and archived, the new-list sheet, and archive with undo.
Out: List detail (SHOP-02 and later). Marking a template (SHOP-11). The default Groceries list (CORE-02). Skipped layers: -api — list CRUD is an outbox upsert, not an HTTP route. The default Groceries list is created by CORE-02-db.

## Acceptance criteria
- [ ] AC1 Given the household has no list beyond the empty case, when I open Shopping, then I see the empty state Make your first list.
- [ ] AC2 Given lists exist, when I open Shopping, then each row shows the name, the store chip, and the checked count.
- [ ] AC3 Given I swipe a list to archive, when I do not tap undo within 5 seconds, then archived_at is set and the list moves to Archived.
- [ ] AC4 Given I tap undo within 5 seconds, when the toast is up, then the list stays in Lists.
- [ ] AC5 Given I create a list from the sheet, when I save a name, then the list exists and opens.
- [ ] AC6 Given the device is offline, when I create a list, then it appears immediately and an outbox row exists.

## Design
Screen: DESIGN C1 — States: empty, populated, offline. Interactions: tap opens C2; swipe left archives with undo; + opens the new-list sheet.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Shopping: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Shopping off (X7): the Shopping tab is hidden and a shopping-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: shopping_list  Endpoints: none (outbox upsert)  Types: ShoppingList
Schema change: no  Contract change: no

## Tests required
Unit: web/src/shopping/Lists.test.ts → AC4 undo does not enqueue the archive; AC6 create enqueues one upsert while offline
Component: web/src/shopping/Lists.test.tsx → AC1 empty, AC2 populated, AC6 offline; tap, swipe archive, new-list save. Covers AC3, AC5.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: yes, TESTING §6 — web/e2e/SHOP-01-lists.offline.spec.ts

## Dependencies
Blocked by: SHOP-01-db, SHOP-08-web, CORE-02-web  Blocks: SHOP-02-web, SHOP-05-web, SHOP-11-web, SHOP-13-web, SHOP-17-web, MAINT-16-web

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
none

# SHOP-01-ios — Multiple lists

## Summary
The household can keep more than one list, name them, archive one, and delete one.

## Spec references
SPEC §4 SHOP-01; DESIGN C1; TESTING §3 client rows

## Scope
In: Lists overview (C1): sections for lists, templates, and archived, the new-list sheet, and archive with undo.
Out: List detail (SHOP-02 and later). Marking a template (SHOP-11). The default Groceries list (CORE-02). Skipped layers: -api — list CRUD is an outbox upsert, not an HTTP route. The default Groceries list is created by CORE-02-db.

## Acceptance criteria
- [ ] AC1 Given the household has no list beyond the empty case, when I open Shopping, then I see the empty state Make your first list.
- [ ] AC2 Given lists exist, when I open Shopping, then each row shows the name, the store chip, and the checked count.
- [ ] AC3 Given I swipe a list to archive, when I do not tap undo within 5 seconds, then archived_at is set and the list moves to Archived.
- [ ] AC4 Given I tap undo within 5 seconds, when the toast is up, then the list stays in Lists.
- [ ] AC5 Given I create a list from the sheet, when I save a name, then the list exists and opens.
- [ ] AC6 Given the device is offline, when I create a list, then it appears immediately and an outbox row exists.

## Design
Screen: DESIGN C1 — States: empty, populated, offline. Interactions: tap opens C2; swipe left archives with undo; + opens the new-list sheet.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Shopping: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Shopping off (X7): the Shopping tab is hidden and a shopping-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: shopping_list  Endpoints: none (outbox upsert)  Types: ShoppingList
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/shopping/Tests/ListsTests.swift → AC4 undo does not enqueue the archive; AC6 create enqueues one upsert while offline
Component: apps/ios/AbodeNestlyTests/ListsViewTests.swift → AC1 empty, AC2 populated, AC6 offline; tap, swipe archive, new-list save. Covers AC3, AC5.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: yes, TESTING §6 — URLProtocol throws, then kick(); ListsOfflineTests.swift covers the same steps as the web file

## Dependencies
Blocked by: SHOP-01-db, SHOP-08-ios, CORE-02-ios  Blocks: SHOP-02-ios, SHOP-05-ios, SHOP-11-ios, SHOP-13-ios, SHOP-17-ios, MAINT-16-ios

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
none

# SHOP-01-android — Multiple lists

## Summary
The household can keep more than one list, name them, archive one, and delete one.

## Spec references
SPEC §4 SHOP-01; DESIGN C1; TESTING §3 client rows

## Scope
In: Lists overview (C1): sections for lists, templates, and archived, the new-list sheet, and archive with undo.
Out: List detail (SHOP-02 and later). Marking a template (SHOP-11). The default Groceries list (CORE-02). Skipped layers: -api — list CRUD is an outbox upsert, not an HTTP route. The default Groceries list is created by CORE-02-db.

## Acceptance criteria
- [ ] AC1 Given the household has no list beyond the empty case, when I open Shopping, then I see the empty state Make your first list.
- [ ] AC2 Given lists exist, when I open Shopping, then each row shows the name, the store chip, and the checked count.
- [ ] AC3 Given I swipe a list to archive, when I do not tap undo within 5 seconds, then archived_at is set and the list moves to Archived.
- [ ] AC4 Given I tap undo within 5 seconds, when the toast is up, then the list stays in Lists.
- [ ] AC5 Given I create a list from the sheet, when I save a name, then the list exists and opens.
- [ ] AC6 Given the device is offline, when I create a list, then it appears immediately and an outbox row exists.

## Design
Screen: DESIGN C1 — States: empty, populated, offline. Interactions: tap opens C2; swipe left archives with undo; + opens the new-list sheet.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Shopping: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Shopping off (X7): the Shopping tab is hidden and a shopping-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: shopping_list  Endpoints: none (outbox upsert)  Types: ShoppingList
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/shopping/src/test/java/app/abodenestly/shopping/ListsTest.kt → AC4 undo does not enqueue the archive; AC6 create enqueues one upsert while offline
Component: apps/android/shopping/src/test/java/app/abodenestly/shopping/ListsComposeTest.kt → AC1 empty, AC2 populated, AC6 offline; tap, swipe archive, new-list save. Covers AC3, AC5.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: yes, TESTING §6 — Fake ApiClient throws IOException, then kick(); ListsOfflineTest.kt

## Dependencies
Blocked by: SHOP-01-db, SHOP-08-android, CORE-02-android  Blocks: SHOP-02-android, SHOP-05-android, SHOP-11-android, SHOP-13-android, SHOP-17-android, MAINT-16-android

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
none

# SHOP-02-web — Add an item with autocomplete

## Summary
Adding something the household has bought before takes a suggestion instead of retyping the name.

## Spec references
SPEC §4 SHOP-02; §5 name_key; DESIGN C2 QuickAddBar; TESTING §8 E2E-SHOP-1

## Scope
In: Quick add on list detail: autocomplete from this household's item history using name_key, while the row shows name.
Out: Quantity, unit, and note (SHOP-03). Barcode and voice (SHOP-10, SHOP-14). Skipped layers: -db, -api — suggestions are a local query on name_key over rows already synced. No new table and no endpoint.

## Acceptance criteria
- [ ] AC1 Given the household has bought Milk before, when I type mi in Quick add, then Milk is suggested.
- [ ] AC2 Given I tap the suggestion, when the item is added, then the row's visible name is Milk and the match used name_key.
- [ ] AC3 Given I type a name with no history, when I add it, then the item is created with that name and no suggestion was required.
- [ ] AC4 Given the device is offline, when I add from a suggestion, then the item appears and an outbox upsert exists.

## Design
Screen: DESIGN C2 — States: empty, populated, offline. Interactions: add via the text field in QuickAddBar; autocomplete sits above it.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Shopping: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Shopping off (X7): the Shopping tab is hidden and a shopping-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: list_item  Endpoints: none  Types: ListItem
Schema change: no  Contract change: no

## Tests required
Unit: web/src/shopping/QuickAdd.test.ts → AC1 and AC2 suggestions match name_key and the inserted name is the display name
Component: web/src/shopping/QuickAdd.test.tsx → AC3 add with no suggestion, AC4 offline; submitting the text field. States empty, populated, offline.
Integration: n/a (no API handler in this story)
E2E: E2E-SHOP-1 web/e2e/QuickAdd.spec.ts
Offline test: yes, TESTING §6 — web/e2e/SHOP-02-quick-add.offline.spec.ts

## Dependencies
Blocked by: SHOP-01-web, SHOP-08-web  Blocks: SHOP-03-web, SHOP-04-web, SHOP-10-web

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
none

# SHOP-02-ios — Add an item with autocomplete

## Summary
Adding something the household has bought before takes a suggestion instead of retyping the name.

## Spec references
SPEC §4 SHOP-02; §5 name_key; DESIGN C2 QuickAddBar; TESTING §8 E2E-SHOP-1

## Scope
In: Quick add on list detail: autocomplete from this household's item history using name_key, while the row shows name.
Out: Quantity, unit, and note (SHOP-03). Barcode and voice (SHOP-10, SHOP-14). Skipped layers: -db, -api — suggestions are a local query on name_key over rows already synced. No new table and no endpoint.

## Acceptance criteria
- [ ] AC1 Given the household has bought Milk before, when I type mi in Quick add, then Milk is suggested.
- [ ] AC2 Given I tap the suggestion, when the item is added, then the row's visible name is Milk and the match used name_key.
- [ ] AC3 Given I type a name with no history, when I add it, then the item is created with that name and no suggestion was required.
- [ ] AC4 Given the device is offline, when I add from a suggestion, then the item appears and an outbox upsert exists.

## Design
Screen: DESIGN C2 — States: empty, populated, offline. Interactions: add via the text field in QuickAddBar; autocomplete sits above it.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Shopping: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Shopping off (X7): the Shopping tab is hidden and a shopping-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: list_item  Endpoints: none  Types: ListItem
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/shopping/Tests/QuickAddTests.swift → AC1 and AC2 suggestions match name_key and the inserted name is the display name
Component: apps/ios/AbodeNestlyTests/QuickAddViewTests.swift → AC3 add with no suggestion, AC4 offline; submitting the text field. States empty, populated, offline.
Integration: n/a (no API handler in this story)
E2E: E2E-SHOP-1 apps/ios/AbodeNestlyUITests/QuickAddUITests.swift
Offline test: yes, TESTING §6 — URLProtocol throws, then kick(); QuickAddOfflineTests.swift covers the same steps as the web file

## Dependencies
Blocked by: SHOP-01-ios, SHOP-08-ios  Blocks: SHOP-03-ios, SHOP-04-ios, SHOP-10-ios, SHOP-15-ios

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
none

# SHOP-02-android — Add an item with autocomplete

## Summary
Adding something the household has bought before takes a suggestion instead of retyping the name.

## Spec references
SPEC §4 SHOP-02; §5 name_key; DESIGN C2 QuickAddBar; TESTING §8 E2E-SHOP-1

## Scope
In: Quick add on list detail: autocomplete from this household's item history using name_key, while the row shows name.
Out: Quantity, unit, and note (SHOP-03). Barcode and voice (SHOP-10, SHOP-14). Skipped layers: -db, -api — suggestions are a local query on name_key over rows already synced. No new table and no endpoint.

## Acceptance criteria
- [ ] AC1 Given the household has bought Milk before, when I type mi in Quick add, then Milk is suggested.
- [ ] AC2 Given I tap the suggestion, when the item is added, then the row's visible name is Milk and the match used name_key.
- [ ] AC3 Given I type a name with no history, when I add it, then the item is created with that name and no suggestion was required.
- [ ] AC4 Given the device is offline, when I add from a suggestion, then the item appears and an outbox upsert exists.

## Design
Screen: DESIGN C2 — States: empty, populated, offline. Interactions: add via the text field in QuickAddBar; autocomplete sits above it.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Shopping: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Shopping off (X7): the Shopping tab is hidden and a shopping-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: list_item  Endpoints: none  Types: ListItem
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/shopping/src/test/java/app/abodenestly/shopping/QuickAddTest.kt → AC1 and AC2 suggestions match name_key and the inserted name is the display name
Component: apps/android/shopping/src/test/java/app/abodenestly/shopping/QuickAddComposeTest.kt → AC3 add with no suggestion, AC4 offline; submitting the text field. States empty, populated, offline.
Integration: n/a (no API handler in this story)
E2E: E2E-SHOP-1 apps/android/shopping/src/androidTest/java/app/abodenestly/shopping/QuickAddE2ETest.kt
Offline test: yes, TESTING §6 — Fake ApiClient throws IOException, then kick(); QuickAddOfflineTest.kt

## Dependencies
Blocked by: SHOP-01-android, SHOP-08-android  Blocks: SHOP-03-android, SHOP-04-android, SHOP-10-android, SHOP-15-android

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
none

# SHOP-03-db — List item quantity, unit, and note

## Summary
A list line can store how much, in which unit, and a note, and those can be left empty.

## Spec references
SPEC §4 SHOP-03; §8 ListItem; TESTING §3 -db row

## Scope
In: list_item.quantity, unit, and note are nullable. Members can write them.
Out: The edit sheet (SHOP-03 clients). Checking off (SHOP-04). Skipped layers: -api — quantity, unit, and note are columns on list_item, created with that table in the list-item migration under SHOP-02's dependency SHOP-01. No endpoint.

## Acceptance criteria
- [ ] AC1 Given a member, when they insert an item with quantity, unit, and note all null, then the row is stored.
- [ ] AC2 Given a member, when they set quantity 2, unit l, and a note, then those values round-trip.
- [ ] AC3 Given a non-member, when they update the item, then the update is rejected.

## Design
Screen: n/a — this layer has no DESIGN screen.
Deviations from the plan: none

## Offline behaviour
Edits sync from the offline outbox (SPEC §6.5).

## Module-off behaviour
Shopping off (X7): the Shopping tab is hidden and a shopping-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: list_item  Endpoints: none  Types: ListItem
Schema change: yes — supabase/migrations/20261108110100_SHOP-03_list_item.sql  Contract change: no

## Tests required
Unit: n/a (database behaviour is asserted in pgTAP, TESTING §2)
Component: n/a (no UI)
Integration: supabase/tests/SHOP-03_list_item.sql → AC1 nulls allowed; AC2 values round-trip; AC3 non-member denied
E2E: n/a (covered by the client journeys for this ID)
Offline test: n/a (server-side)

## Dependencies
Blocked by: SHOP-01-db  Blocks: SHOP-03-web, SHOP-03-ios, SHOP-03-android, SHOP-07-web, SHOP-07-ios, SHOP-07-android, SHOP-09-db

## Skill
.claude/skills/sql-supabase/SKILL.md

## Open questions
none

# SHOP-03-web — Quantity, unit, and note

## Summary
A person can say how much, in which unit, and add a note, and can leave any of those blank.

## Spec references
SPEC §4 SHOP-03; DESIGN C3; TESTING §3 client rows

## Scope
In: Item sheet (C3) fields for name, stepper quantity, unit, and note, for new and edit.
Out: Price (SHOP-12). Autocomplete (SHOP-02). Pantry and task links on the same sheet (SHOP-16, SHOP-17). Skipped layers: -api — quantity, unit, and note are columns on list_item, created with that table in the list-item migration under SHOP-02's dependency SHOP-01. No endpoint.

## Acceptance criteria
- [ ] AC1 Given I add an item with no quantity, when I save, then quantity, unit, and note are empty and the item exists.
- [ ] AC2 Given I set quantity, unit, and note, when I save, then the list row shows the quantity and unit and the note.
- [ ] AC3 Given I clear the note, when I save, then the note is gone and the quantity remains.
- [ ] AC4 Given the device is offline, when I save, then the change is queued.

## Design
Screen: DESIGN C3 — States: new, edit. Interactions: edit name, stepper, unit, note, delete.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Shopping: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Shopping off (X7): the Shopping tab is hidden and a shopping-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: list_item  Endpoints: none  Types: ListItem
Schema change: no  Contract change: no

## Tests required
Unit: web/src/shopping/ItemSheet.test.ts → AC1 a save with blanks sends nulls; AC4 offline enqueues one upsert
Component: web/src/shopping/ItemSheet.test.tsx → AC2 edit shows qty, unit, and note; AC3 clearing the note. States new and edit. Interactions: stepper, unit, note, delete.
Integration: n/a (no API handler in this story)
E2E: E2E-SHOP-1 web/e2e/ItemSheet.spec.ts
Offline test: yes, TESTING §6 — web/e2e/SHOP-03-item-sheet.offline.spec.ts

## Dependencies
Blocked by: SHOP-03-db, SHOP-02-web  Blocks: SHOP-14-web

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
none

# SHOP-03-ios — Quantity, unit, and note

## Summary
A person can say how much, in which unit, and add a note, and can leave any of those blank.

## Spec references
SPEC §4 SHOP-03; DESIGN C3; TESTING §3 client rows

## Scope
In: Item sheet (C3) fields for name, stepper quantity, unit, and note, for new and edit.
Out: Price (SHOP-12). Autocomplete (SHOP-02). Pantry and task links on the same sheet (SHOP-16, SHOP-17). Skipped layers: -api — quantity, unit, and note are columns on list_item, created with that table in the list-item migration under SHOP-02's dependency SHOP-01. No endpoint.

## Acceptance criteria
- [ ] AC1 Given I add an item with no quantity, when I save, then quantity, unit, and note are empty and the item exists.
- [ ] AC2 Given I set quantity, unit, and note, when I save, then the list row shows the quantity and unit and the note.
- [ ] AC3 Given I clear the note, when I save, then the note is gone and the quantity remains.
- [ ] AC4 Given the device is offline, when I save, then the change is queued.

## Design
Screen: DESIGN C3 — States: new, edit. Interactions: edit name, stepper, unit, note, delete.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Shopping: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Shopping off (X7): the Shopping tab is hidden and a shopping-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: list_item  Endpoints: none  Types: ListItem
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/shopping/Tests/ItemSheetTests.swift → AC1 a save with blanks sends nulls; AC4 offline enqueues one upsert
Component: apps/ios/AbodeNestlyTests/ItemSheetViewTests.swift → AC2 edit shows qty, unit, and note; AC3 clearing the note. States new and edit. Interactions: stepper, unit, note, delete.
Integration: n/a (no API handler in this story)
E2E: E2E-SHOP-1 apps/ios/AbodeNestlyUITests/ItemSheetUITests.swift
Offline test: yes, TESTING §6 — URLProtocol throws, then kick(); ItemSheetOfflineTests.swift covers the same steps as the web file

## Dependencies
Blocked by: SHOP-03-db, SHOP-02-ios  Blocks: SHOP-14-ios

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
none

# SHOP-03-android — Quantity, unit, and note

## Summary
A person can say how much, in which unit, and add a note, and can leave any of those blank.

## Spec references
SPEC §4 SHOP-03; DESIGN C3; TESTING §3 client rows

## Scope
In: Item sheet (C3) fields for name, stepper quantity, unit, and note, for new and edit.
Out: Price (SHOP-12). Autocomplete (SHOP-02). Pantry and task links on the same sheet (SHOP-16, SHOP-17). Skipped layers: -api — quantity, unit, and note are columns on list_item, created with that table in the list-item migration under SHOP-02's dependency SHOP-01. No endpoint.

## Acceptance criteria
- [ ] AC1 Given I add an item with no quantity, when I save, then quantity, unit, and note are empty and the item exists.
- [ ] AC2 Given I set quantity, unit, and note, when I save, then the list row shows the quantity and unit and the note.
- [ ] AC3 Given I clear the note, when I save, then the note is gone and the quantity remains.
- [ ] AC4 Given the device is offline, when I save, then the change is queued.

## Design
Screen: DESIGN C3 — States: new, edit. Interactions: edit name, stepper, unit, note, delete.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Shopping: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Shopping off (X7): the Shopping tab is hidden and a shopping-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: list_item  Endpoints: none  Types: ListItem
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/shopping/src/test/java/app/abodenestly/shopping/ItemSheetTest.kt → AC1 a save with blanks sends nulls; AC4 offline enqueues one upsert
Component: apps/android/shopping/src/test/java/app/abodenestly/shopping/ItemSheetComposeTest.kt → AC2 edit shows qty, unit, and note; AC3 clearing the note. States new and edit. Interactions: stepper, unit, note, delete.
Integration: n/a (no API handler in this story)
E2E: E2E-SHOP-1 apps/android/shopping/src/androidTest/java/app/abodenestly/shopping/ItemSheetE2ETest.kt
Offline test: yes, TESTING §6 — Fake ApiClient throws IOException, then kick(); ItemSheetOfflineTest.kt

## Dependencies
Blocked by: SHOP-03-db, SHOP-02-android  Blocks: SHOP-14-android

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
none

# SHOP-04-web — Check off, undo, and clear

## Summary
In the aisle a person can check an item off, take it back for a few seconds, and clear the finished ones.

## Spec references
SPEC §4 SHOP-04; DESIGN C2; TESTING §8 E2E-SHOP-1

## Scope
In: Checkbox, undo toast, checked items collapsed under Checked (n), and Clear checked.
Out: The restock prompt (X2, SHOP-16). Price entry (SHOP-12). Aisle learning (X4). Skipped layers: -db, -api — check-off is a list_item update. Learning on check-off is X4-db. The 5 second undo stays on the client.

## Acceptance criteria
- [ ] AC1 Given an unchecked item, when I tap its checkbox, then it is checked and an undo toast is shown.
- [ ] AC2 Given I tap Undo within 5 seconds, when the toast is up, then the item is unchecked and no check-off mutation is pushed.
- [ ] AC3 Given I do not tap Undo, when 5 seconds pass, then the item sits in the Checked group and the outbox has the check-off.
- [ ] AC4 Given every item is checked, when I look at the list, then I see the all-checked state and Clear checked.
- [ ] AC5 Given I tap Clear checked, when it runs, then the checked items are soft-deleted.
- [ ] AC6 Given the device is offline, when I check an item off and the undo window passes, then the outbox has the mutation.

## Design
Screen: DESIGN C2 — States: populated, all-checked, offline. Interactions: tap checkbox checks off with undo toast; Clear checked in the overflow.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Shopping: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08). The check-off mutation waits for the 5 second undo window before it is appended to the outbox.

## Module-off behaviour
Shopping off (X7): the Shopping tab is hidden and a shopping-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: list_item  Endpoints: none  Types: ListItem
Schema change: no  Contract change: no

## Tests required
Unit: web/src/shopping/CheckOff.test.ts → AC2 undo writes nothing to the outbox; AC3 after 5 s one upsert has checked true; AC5 clear sets deleted_at
Component: web/src/shopping/CheckOff.test.tsx → AC1 populated checkbox, AC4 all-checked, AC6 offline; checkbox tap, undo, clear checked
Integration: n/a (no API handler in this story)
E2E: E2E-SHOP-1 web/e2e/CheckOff.spec.ts
Offline test: yes, TESTING §6 — web/e2e/SHOP-04-check-off.offline.spec.ts

## Dependencies
Blocked by: SHOP-02-web, SHOP-08-web  Blocks: SHOP-09-web, SHOP-12-web, SHOP-16-web

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
§4 does not say whether the mutation waits out the undo window. Proposed default: the outbox append happens when the toast expires, so undo never reaches the server.

# SHOP-04-ios — Check off, undo, and clear

## Summary
In the aisle a person can check an item off, take it back for a few seconds, and clear the finished ones.

## Spec references
SPEC §4 SHOP-04; DESIGN C2; TESTING §8 E2E-SHOP-1

## Scope
In: Checkbox, undo toast, checked items collapsed under Checked (n), and Clear checked.
Out: The restock prompt (X2, SHOP-16). Price entry (SHOP-12). Aisle learning (X4). Skipped layers: -db, -api — check-off is a list_item update. Learning on check-off is X4-db. The 5 second undo stays on the client.

## Acceptance criteria
- [ ] AC1 Given an unchecked item, when I tap its checkbox, then it is checked and an undo toast is shown.
- [ ] AC2 Given I tap Undo within 5 seconds, when the toast is up, then the item is unchecked and no check-off mutation is pushed.
- [ ] AC3 Given I do not tap Undo, when 5 seconds pass, then the item sits in the Checked group and the outbox has the check-off.
- [ ] AC4 Given every item is checked, when I look at the list, then I see the all-checked state and Clear checked.
- [ ] AC5 Given I tap Clear checked, when it runs, then the checked items are soft-deleted.
- [ ] AC6 Given the device is offline, when I check an item off and the undo window passes, then the outbox has the mutation.

## Design
Screen: DESIGN C2 — States: populated, all-checked, offline. Interactions: tap checkbox checks off with undo toast; Clear checked in the overflow.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Shopping: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08). The check-off mutation waits for the 5 second undo window before it is appended to the outbox.

## Module-off behaviour
Shopping off (X7): the Shopping tab is hidden and a shopping-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: list_item  Endpoints: none  Types: ListItem
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/shopping/Tests/CheckOffTests.swift → AC2 undo writes nothing to the outbox; AC3 after 5 s one upsert has checked true; AC5 clear sets deleted_at
Component: apps/ios/AbodeNestlyTests/CheckOffViewTests.swift → AC1 populated checkbox, AC4 all-checked, AC6 offline; checkbox tap, undo, clear checked
Integration: n/a (no API handler in this story)
E2E: E2E-SHOP-1 apps/ios/AbodeNestlyUITests/CheckOffUITests.swift
Offline test: yes, TESTING §6 — URLProtocol throws, then kick(); CheckOffOfflineTests.swift covers the same steps as the web file

## Dependencies
Blocked by: SHOP-02-ios, SHOP-08-ios  Blocks: SHOP-09-ios, SHOP-12-ios, SHOP-16-ios

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
§4 does not say whether the mutation waits out the undo window. Proposed default: the outbox append happens when the toast expires, so undo never reaches the server.

# SHOP-04-android — Check off, undo, and clear

## Summary
In the aisle a person can check an item off, take it back for a few seconds, and clear the finished ones.

## Spec references
SPEC §4 SHOP-04; DESIGN C2; TESTING §8 E2E-SHOP-1

## Scope
In: Checkbox, undo toast, checked items collapsed under Checked (n), and Clear checked.
Out: The restock prompt (X2, SHOP-16). Price entry (SHOP-12). Aisle learning (X4). Skipped layers: -db, -api — check-off is a list_item update. Learning on check-off is X4-db. The 5 second undo stays on the client.

## Acceptance criteria
- [ ] AC1 Given an unchecked item, when I tap its checkbox, then it is checked and an undo toast is shown.
- [ ] AC2 Given I tap Undo within 5 seconds, when the toast is up, then the item is unchecked and no check-off mutation is pushed.
- [ ] AC3 Given I do not tap Undo, when 5 seconds pass, then the item sits in the Checked group and the outbox has the check-off.
- [ ] AC4 Given every item is checked, when I look at the list, then I see the all-checked state and Clear checked.
- [ ] AC5 Given I tap Clear checked, when it runs, then the checked items are soft-deleted.
- [ ] AC6 Given the device is offline, when I check an item off and the undo window passes, then the outbox has the mutation.

## Design
Screen: DESIGN C2 — States: populated, all-checked, offline. Interactions: tap checkbox checks off with undo toast; Clear checked in the overflow.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Shopping: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08). The check-off mutation waits for the 5 second undo window before it is appended to the outbox.

## Module-off behaviour
Shopping off (X7): the Shopping tab is hidden and a shopping-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: list_item  Endpoints: none  Types: ListItem
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/shopping/src/test/java/app/abodenestly/shopping/CheckOffTest.kt → AC2 undo writes nothing to the outbox; AC3 after 5 s one upsert has checked true; AC5 clear sets deleted_at
Component: apps/android/shopping/src/test/java/app/abodenestly/shopping/CheckOffComposeTest.kt → AC1 populated checkbox, AC4 all-checked, AC6 offline; checkbox tap, undo, clear checked
Integration: n/a (no API handler in this story)
E2E: E2E-SHOP-1 apps/android/shopping/src/androidTest/java/app/abodenestly/shopping/CheckOffE2ETest.kt
Offline test: yes, TESTING §6 — Fake ApiClient throws IOException, then kick(); CheckOffOfflineTest.kt

## Dependencies
Blocked by: SHOP-02-android, SHOP-08-android  Blocks: SHOP-09-android, SHOP-12-android, SHOP-16-android

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
§4 does not say whether the mutation waits out the undo window. Proposed default: the outbox append happens when the toast expires, so undo never reaches the server.

# SHOP-05-db — Stores

## Summary
The household can name the shops it uses and point a list at one of them.

## Spec references
SPEC §4 SHOP-05; §8 Store; TESTING §3 -db row

## Scope
In: store table, and the foreign key from shopping_list.store_id (a plain uuid column since CORE-02-db) to it.
Out: The stores screens (SHOP-05 clients). Aisle learning, which needs a store (X4). Skipped layers: -api — stores are outbox CRUD. §7 lists no store endpoint.

## Acceptance criteria
- [ ] AC1 Given a member, when they insert a store, then the row is stored.
- [ ] AC2 Given a list, when store_id points at that store, then the reference is valid.
- [ ] AC3 Given a non-member, when they select the store, then they see nothing.

## Design
Screen: n/a — this layer has no DESIGN screen.
Deviations from the plan: none

## Offline behaviour
Store writes sync from the offline outbox (SPEC §6.5).

## Module-off behaviour
Shopping off (X7): the Shopping tab is hidden and a shopping-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: store, shopping_list  Endpoints: none  Types: Store, ShoppingList
Schema change: yes — supabase/migrations/20261108110200_SHOP-05_store.sql  Contract change: no

## Tests required
Unit: n/a (database behaviour is asserted in pgTAP, TESTING §2)
Component: n/a (no UI)
Integration: supabase/tests/SHOP-05_store.sql → AC1 insert; AC2 list references the store; AC3 non-member denied
E2E: n/a (covered by the client journeys for this ID)
Offline test: n/a (server-side)

## Dependencies
Blocked by: SHOP-01-db  Blocks: SHOP-05-web, SHOP-05-ios, SHOP-05-android, SHOP-06-db, SHOP-12-db

## Skill
.claude/skills/sql-supabase/SKILL.md

## Open questions
none

# SHOP-05-web — Stores

## Summary
A person can name a shop and assign the list they are standing in to that shop.

## Spec references
SPEC §4 SHOP-05; DESIGN C4; TESTING §3 client rows

## Scope
In: Stores list (C4) and assigning a store from the list's store chip.
Out: The aisle-order screen (SHOP-06, C5). Creating lists (SHOP-01). Skipped layers: -api — stores are outbox CRUD. §7 lists no store endpoint.

## Acceptance criteria
- [ ] AC1 Given there are no stores, when I open Stores, then I see the empty state.
- [ ] AC2 Given I add Test Market, when I save, then the row shows the name.
- [ ] AC3 Given I pick Test Market on a list, when I confirm, then the list's store chip reads Test Market.
- [ ] AC4 Given the device is offline, when I create a store, then it appears and an outbox row exists.

## Design
Screen: DESIGN C4 — States: empty, populated. Interactions: tap opens C5; swipe deletes with undo; + adds a store.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Shopping: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Shopping off (X7): the Shopping tab is hidden and a shopping-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: store, shopping_list  Endpoints: none  Types: Store, ShoppingList
Schema change: no  Contract change: no

## Tests required
Unit: web/src/shopping/Stores.test.ts → AC4 offline create enqueues one store upsert
Component: web/src/shopping/Stores.test.tsx → AC1 empty, AC2 populated; add, swipe delete, and assigning a store from the list chip covers AC3
Integration: n/a (no API handler in this story)
E2E: E2E-SHOP-3 web/e2e/Stores.spec.ts
Offline test: yes, TESTING §6 — web/e2e/SHOP-05-stores.offline.spec.ts

## Dependencies
Blocked by: SHOP-05-db, SHOP-01-web  Blocks: SHOP-06-web, SHOP-12-web

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
none

# SHOP-05-ios — Stores

## Summary
A person can name a shop and assign the list they are standing in to that shop.

## Spec references
SPEC §4 SHOP-05; DESIGN C4; TESTING §3 client rows

## Scope
In: Stores list (C4) and assigning a store from the list's store chip.
Out: The aisle-order screen (SHOP-06, C5). Creating lists (SHOP-01). Skipped layers: -api — stores are outbox CRUD. §7 lists no store endpoint.

## Acceptance criteria
- [ ] AC1 Given there are no stores, when I open Stores, then I see the empty state.
- [ ] AC2 Given I add Test Market, when I save, then the row shows the name.
- [ ] AC3 Given I pick Test Market on a list, when I confirm, then the list's store chip reads Test Market.
- [ ] AC4 Given the device is offline, when I create a store, then it appears and an outbox row exists.

## Design
Screen: DESIGN C4 — States: empty, populated. Interactions: tap opens C5; swipe deletes with undo; + adds a store.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Shopping: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Shopping off (X7): the Shopping tab is hidden and a shopping-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: store, shopping_list  Endpoints: none  Types: Store, ShoppingList
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/shopping/Tests/StoresTests.swift → AC4 offline create enqueues one store upsert
Component: apps/ios/AbodeNestlyTests/StoresViewTests.swift → AC1 empty, AC2 populated; add, swipe delete, and assigning a store from the list chip covers AC3
Integration: n/a (no API handler in this story)
E2E: E2E-SHOP-3 apps/ios/AbodeNestlyUITests/StoresUITests.swift
Offline test: yes, TESTING §6 — URLProtocol throws, then kick(); StoresOfflineTests.swift covers the same steps as the web file

## Dependencies
Blocked by: SHOP-05-db, SHOP-01-ios  Blocks: SHOP-06-ios, SHOP-12-ios

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
none

# SHOP-05-android — Stores

## Summary
A person can name a shop and assign the list they are standing in to that shop.

## Spec references
SPEC §4 SHOP-05; DESIGN C4; TESTING §3 client rows

## Scope
In: Stores list (C4) and assigning a store from the list's store chip.
Out: The aisle-order screen (SHOP-06, C5). Creating lists (SHOP-01). Skipped layers: -api — stores are outbox CRUD. §7 lists no store endpoint.

## Acceptance criteria
- [ ] AC1 Given there are no stores, when I open Stores, then I see the empty state.
- [ ] AC2 Given I add Test Market, when I save, then the row shows the name.
- [ ] AC3 Given I pick Test Market on a list, when I confirm, then the list's store chip reads Test Market.
- [ ] AC4 Given the device is offline, when I create a store, then it appears and an outbox row exists.

## Design
Screen: DESIGN C4 — States: empty, populated. Interactions: tap opens C5; swipe deletes with undo; + adds a store.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Shopping: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Shopping off (X7): the Shopping tab is hidden and a shopping-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: store, shopping_list  Endpoints: none  Types: Store, ShoppingList
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/shopping/src/test/java/app/abodenestly/shopping/StoresTest.kt → AC4 offline create enqueues one store upsert
Component: apps/android/shopping/src/test/java/app/abodenestly/shopping/StoresComposeTest.kt → AC1 empty, AC2 populated; add, swipe delete, and assigning a store from the list chip covers AC3
Integration: n/a (no API handler in this story)
E2E: E2E-SHOP-3 apps/android/shopping/src/androidTest/java/app/abodenestly/shopping/StoresE2ETest.kt
Offline test: yes, TESTING §6 — Fake ApiClient throws IOException, then kick(); StoresOfflineTest.kt

## Dependencies
Blocked by: SHOP-05-db, SHOP-01-android  Blocks: SHOP-06-android, SHOP-12-android

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
none

# SHOP-06-db — Learned order rows

## Summary
Each store can remember a position for an item the household has checked off there.

## Spec references
SPEC §4 SHOP-06; §8 StoreItemOrder; TESTING §3 -db row

## Scope
In: store_item_order keyed by store and name_key, with mean_position and samples. The check-off trigger that updates it is X4-db.
Out: Sorting the list (SHOP-06 clients). The trigger (X4-db). Skipped layers: -api — sorting is local. The running mean is written by X4-db when a check-off syncs.

## Acceptance criteria
- [ ] AC1 Given a member, when they upsert an order row, then mean_position and samples are stored.
- [ ] AC2 Given a non-member, when they select the row, then they see nothing.
- [ ] AC3 Given two name keys at one store, when they are read, then both rows exist and neither collides.

## Design
Screen: n/a — this layer has no DESIGN screen.
Deviations from the plan: none

## Offline behaviour
The rows sync so a list can be sorted offline (SPEC §6.5).

## Module-off behaviour
Shopping off (X7): the Shopping tab is hidden and a shopping-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: store_item_order  Endpoints: none  Types: StoreItemOrder
Schema change: yes — supabase/migrations/20261108110300_SHOP-06_store_item_order.sql  Contract change: no

## Tests required
Unit: n/a (database behaviour is asserted in pgTAP, TESTING §2)
Component: n/a (no UI)
Integration: supabase/tests/SHOP-06_order.sql → AC1 upsert; AC2 non-member denied; AC3 two keys coexist
E2E: n/a (covered by the client journeys for this ID)
Offline test: n/a (server-side)

## Dependencies
Blocked by: SHOP-05-db  Blocks: SHOP-06-web, SHOP-06-ios, SHOP-06-android, X4-db

## Skill
.claude/skills/sql-supabase/SKILL.md

## Open questions
none

# SHOP-06-web — Learned aisle order

## Summary
The list sorts itself into the order the household usually walks that store, with new items at the bottom.

## Spec references
SPEC §4 SHOP-06; DESIGN C2, C5; TESTING §8 E2E-SHOP-3

## Scope
In: Sort on list detail by mean_position, unseen items last, and the store detail (C5) that shows and resets that learning.
Out: Manual drag on the list (SHOP-07). The learning trigger (X4). Skipped layers: -api — sorting is local. The running mean is written by X4-db when a check-off syncs.

## Acceptance criteria
- [ ] AC1 Given the list has a store and two items have mean positions, when I open the list, then they appear in ascending mean_position.
- [ ] AC2 Given an item has no order row for that store, when I open the list, then it sorts at the bottom.
- [ ] AC3 Given the list has no store, when I open it, then learned order is not applied.
- [ ] AC4 Given I open the store and drag an item, when I drop it, then mean_position becomes the new index and samples is 5.
- [ ] AC5 Given I tap Reset learning, when it completes, then that store's order rows are deleted.
- [ ] AC6 Given the device is offline, when I open the list, then the last synced order is what I see.

## Design
Screen: DESIGN C2 and C5 — States: C2 populated and offline; C5 no-data and populated. Interactions: C5 drag sets mean_position and resets samples to 5; C5 reset deletes the rows.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Shopping: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Shopping off (X7): the Shopping tab is hidden and a shopping-table mutation is rejected with module_disabled. This screen is not reachable and must not error. Shopping only (the §9 matrix) still includes aisle learning.

## Data and contract
Tables: store_item_order, list_item, shopping_list  Endpoints: none  Types: StoreItemOrder, ListItem
Schema change: no  Contract change: no

## Tests required
Unit: web/src/shopping/AisleOrder.test.ts → AC1 sort by mean_position; AC2 unseen last; AC3 no store keeps insertion order; AC4 drag writes samples 5
Component: web/src/shopping/AisleOrder.test.tsx → AC5 reset on C5 no-data and populated; AC6 offline list. Interactions: drag on C5, reset.
Integration: n/a (no API handler in this story)
E2E: E2E-SHOP-3 web/e2e/AisleOrder.spec.ts
Offline test: yes, TESTING §6 — web/e2e/SHOP-06-aisle.offline.spec.ts

## Dependencies
Blocked by: SHOP-06-db, SHOP-05-web, X4-db  Blocks: SHOP-07-web

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
none

# SHOP-06-ios — Learned aisle order

## Summary
The list sorts itself into the order the household usually walks that store, with new items at the bottom.

## Spec references
SPEC §4 SHOP-06; DESIGN C2, C5; TESTING §8 E2E-SHOP-3

## Scope
In: Sort on list detail by mean_position, unseen items last, and the store detail (C5) that shows and resets that learning.
Out: Manual drag on the list (SHOP-07). The learning trigger (X4). Skipped layers: -api — sorting is local. The running mean is written by X4-db when a check-off syncs.

## Acceptance criteria
- [ ] AC1 Given the list has a store and two items have mean positions, when I open the list, then they appear in ascending mean_position.
- [ ] AC2 Given an item has no order row for that store, when I open the list, then it sorts at the bottom.
- [ ] AC3 Given the list has no store, when I open it, then learned order is not applied.
- [ ] AC4 Given I open the store and drag an item, when I drop it, then mean_position becomes the new index and samples is 5.
- [ ] AC5 Given I tap Reset learning, when it completes, then that store's order rows are deleted.
- [ ] AC6 Given the device is offline, when I open the list, then the last synced order is what I see.

## Design
Screen: DESIGN C2 and C5 — States: C2 populated and offline; C5 no-data and populated. Interactions: C5 drag sets mean_position and resets samples to 5; C5 reset deletes the rows.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Shopping: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Shopping off (X7): the Shopping tab is hidden and a shopping-table mutation is rejected with module_disabled. This screen is not reachable and must not error. Shopping only (the §9 matrix) still includes aisle learning.

## Data and contract
Tables: store_item_order, list_item, shopping_list  Endpoints: none  Types: StoreItemOrder, ListItem
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/shopping/Tests/AisleOrderTests.swift → AC1 sort by mean_position; AC2 unseen last; AC3 no store keeps insertion order; AC4 drag writes samples 5
Component: apps/ios/AbodeNestlyTests/AisleOrderViewTests.swift → AC5 reset on C5 no-data and populated; AC6 offline list. Interactions: drag on C5, reset.
Integration: n/a (no API handler in this story)
E2E: E2E-SHOP-3 apps/ios/AbodeNestlyUITests/AisleOrderUITests.swift
Offline test: yes, TESTING §6 — URLProtocol throws, then kick(); AisleOrderOfflineTests.swift covers the same steps as the web file

## Dependencies
Blocked by: SHOP-06-db, SHOP-05-ios, X4-db  Blocks: SHOP-07-ios

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
none

# SHOP-06-android — Learned aisle order

## Summary
The list sorts itself into the order the household usually walks that store, with new items at the bottom.

## Spec references
SPEC §4 SHOP-06; DESIGN C2, C5; TESTING §8 E2E-SHOP-3

## Scope
In: Sort on list detail by mean_position, unseen items last, and the store detail (C5) that shows and resets that learning.
Out: Manual drag on the list (SHOP-07). The learning trigger (X4). Skipped layers: -api — sorting is local. The running mean is written by X4-db when a check-off syncs.

## Acceptance criteria
- [ ] AC1 Given the list has a store and two items have mean positions, when I open the list, then they appear in ascending mean_position.
- [ ] AC2 Given an item has no order row for that store, when I open the list, then it sorts at the bottom.
- [ ] AC3 Given the list has no store, when I open it, then learned order is not applied.
- [ ] AC4 Given I open the store and drag an item, when I drop it, then mean_position becomes the new index and samples is 5.
- [ ] AC5 Given I tap Reset learning, when it completes, then that store's order rows are deleted.
- [ ] AC6 Given the device is offline, when I open the list, then the last synced order is what I see.

## Design
Screen: DESIGN C2 and C5 — States: C2 populated and offline; C5 no-data and populated. Interactions: C5 drag sets mean_position and resets samples to 5; C5 reset deletes the rows.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Shopping: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Shopping off (X7): the Shopping tab is hidden and a shopping-table mutation is rejected with module_disabled. This screen is not reachable and must not error. Shopping only (the §9 matrix) still includes aisle learning.

## Data and contract
Tables: store_item_order, list_item, shopping_list  Endpoints: none  Types: StoreItemOrder, ListItem
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/shopping/src/test/java/app/abodenestly/shopping/AisleOrderTest.kt → AC1 sort by mean_position; AC2 unseen last; AC3 no store keeps insertion order; AC4 drag writes samples 5
Component: apps/android/shopping/src/test/java/app/abodenestly/shopping/AisleOrderComposeTest.kt → AC5 reset on C5 no-data and populated; AC6 offline list. Interactions: drag on C5, reset.
Integration: n/a (no API handler in this story)
E2E: E2E-SHOP-3 apps/android/shopping/src/androidTest/java/app/abodenestly/shopping/AisleOrderE2ETest.kt
Offline test: yes, TESTING §6 — Fake ApiClient throws IOException, then kick(); AisleOrderOfflineTest.kt

## Dependencies
Blocked by: SHOP-06-db, SHOP-05-android, X4-db  Blocks: SHOP-07-android

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
none

# SHOP-07-web — Drag to reorder

## Summary
A person can drag the list into the order they want, and that drag also teaches the store.

## Spec references
SPEC §4 SHOP-07; DESIGN C2 reordering; TESTING §3 client rows

## Scope
In: Long-press drag on the list, which sets manual_position and teaches the store order.
Out: The learned sort (SHOP-06). Check-off learning (X4). Skipped layers: -db, -api — manual_position is a list_item column from SHOP-03-db. Teaching the store reuses store_item_order from SHOP-06-db.

## Acceptance criteria
- [ ] AC1 Given I long-press an item, when the gesture starts, then the reordering state shows drag handles.
- [ ] AC2 Given I drop the item in a new place, when the gesture ends, then manual_position matches that place.
- [ ] AC3 Given the list has a store, when I drop the item, then the store order for that name is taught.
- [ ] AC4 Given the device is offline, when I drop the item, then both writes are queued.

## Design
Screen: DESIGN C2 — States: reordering, populated, offline. Interactions: long-press drag sets manual_position and teaches order.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Shopping: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Shopping off (X7): the Shopping tab is hidden and a shopping-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: list_item, store_item_order  Endpoints: none  Types: ListItem, StoreItemOrder
Schema change: no  Contract change: no

## Tests required
Unit: web/src/shopping/Reorder.test.ts → AC2 manual_position equals the drop index; AC3 a store_item_order upsert is queued when the list has a store
Component: web/src/shopping/Reorder.test.tsx → AC1 reordering, AC4 offline; long-press and drop
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: yes, TESTING §6 — web/e2e/SHOP-07-reorder.offline.spec.ts

## Dependencies
Blocked by: SHOP-06-web, SHOP-03-db  Blocks: none

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
X4 teaches on check-off, while DESIGN C2 says the drag also teaches and DESIGN C5 sets samples to 5. Proposed default: the drag upserts store_item_order with mean_position equal to the drop index and samples 5, the same rule as C5.

# SHOP-07-ios — Drag to reorder

## Summary
A person can drag the list into the order they want, and that drag also teaches the store.

## Spec references
SPEC §4 SHOP-07; DESIGN C2 reordering; TESTING §3 client rows

## Scope
In: Long-press drag on the list, which sets manual_position and teaches the store order.
Out: The learned sort (SHOP-06). Check-off learning (X4). Skipped layers: -db, -api — manual_position is a list_item column from SHOP-03-db. Teaching the store reuses store_item_order from SHOP-06-db.

## Acceptance criteria
- [ ] AC1 Given I long-press an item, when the gesture starts, then the reordering state shows drag handles.
- [ ] AC2 Given I drop the item in a new place, when the gesture ends, then manual_position matches that place.
- [ ] AC3 Given the list has a store, when I drop the item, then the store order for that name is taught.
- [ ] AC4 Given the device is offline, when I drop the item, then both writes are queued.

## Design
Screen: DESIGN C2 — States: reordering, populated, offline. Interactions: long-press drag sets manual_position and teaches order.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Shopping: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Shopping off (X7): the Shopping tab is hidden and a shopping-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: list_item, store_item_order  Endpoints: none  Types: ListItem, StoreItemOrder
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/shopping/Tests/ReorderTests.swift → AC2 manual_position equals the drop index; AC3 a store_item_order upsert is queued when the list has a store
Component: apps/ios/AbodeNestlyTests/ReorderViewTests.swift → AC1 reordering, AC4 offline; long-press and drop
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: yes, TESTING §6 — URLProtocol throws, then kick(); ReorderOfflineTests.swift covers the same steps as the web file

## Dependencies
Blocked by: SHOP-06-ios, SHOP-03-db  Blocks: none

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
X4 teaches on check-off, while DESIGN C2 says the drag also teaches and DESIGN C5 sets samples to 5. Proposed default: the drag upserts store_item_order with mean_position equal to the drop index and samples 5, the same rule as C5.

# SHOP-07-android — Drag to reorder

## Summary
A person can drag the list into the order they want, and that drag also teaches the store.

## Spec references
SPEC §4 SHOP-07; DESIGN C2 reordering; TESTING §3 client rows

## Scope
In: Long-press drag on the list, which sets manual_position and teaches the store order.
Out: The learned sort (SHOP-06). Check-off learning (X4). Skipped layers: -db, -api — manual_position is a list_item column from SHOP-03-db. Teaching the store reuses store_item_order from SHOP-06-db.

## Acceptance criteria
- [ ] AC1 Given I long-press an item, when the gesture starts, then the reordering state shows drag handles.
- [ ] AC2 Given I drop the item in a new place, when the gesture ends, then manual_position matches that place.
- [ ] AC3 Given the list has a store, when I drop the item, then the store order for that name is taught.
- [ ] AC4 Given the device is offline, when I drop the item, then both writes are queued.

## Design
Screen: DESIGN C2 — States: reordering, populated, offline. Interactions: long-press drag sets manual_position and teaches order.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Shopping: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Shopping off (X7): the Shopping tab is hidden and a shopping-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: list_item, store_item_order  Endpoints: none  Types: ListItem, StoreItemOrder
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/shopping/src/test/java/app/abodenestly/shopping/ReorderTest.kt → AC2 manual_position equals the drop index; AC3 a store_item_order upsert is queued when the list has a store
Component: apps/android/shopping/src/test/java/app/abodenestly/shopping/ReorderComposeTest.kt → AC1 reordering, AC4 offline; long-press and drop
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: yes, TESTING §6 — Fake ApiClient throws IOException, then kick(); ReorderOfflineTest.kt

## Dependencies
Blocked by: SHOP-06-android, SHOP-03-db  Blocks: none

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
X4 teaches on check-off, while DESIGN C2 says the drag also teaches and DESIGN C5 sets samples to 5. Proposed default: the drag upserts store_item_order with mean_position equal to the drop index and samples 5, the same rule as C5.

# SHOP-08-db — Remember applied sync mutations

## Summary
A phone that retries a push does not apply the same edit twice.

## Spec references
SPEC §4 SHOP-08; §6.3 applied ids remembered for 24 hours; TESTING §3 -db row

## Scope
In: sync_applied stores mutation ids for 24 hours. A replay of the same id is a no-op.
Out: The sync handlers (SHOP-08-api).

## Acceptance criteria
- [ ] AC1 Given a mutation id is recorded, when the same id is checked, then it is treated as already applied.
- [ ] AC2 Given the record is older than 24 hours, when it is checked, then it is no longer applied.
- [ ] AC3 Given two different ids, when both are recorded, then each is remembered on its own.

## Design
Screen: n/a — this layer has no DESIGN screen.
Deviations from the plan: none

## Offline behaviour
This is the server side of an offline queue (SPEC §6.5 Shopping).

## Module-off behaviour
The table is not household-scoped. Module rejection is X7-api, which runs before an id is recorded.

## Data and contract
Tables: sync_applied  Endpoints: none  Types: none
Schema change: yes — supabase/migrations/20261108110400_SHOP-08_sync_applied.sql  Contract change: no

## Tests required
Unit: n/a (database behaviour is asserted in pgTAP, TESTING §2)
Component: n/a (no UI)
Integration: supabase/tests/SHOP-08_applied.sql → AC1 id remembered; AC2 expired id forgotten; AC3 ids are independent
E2E: n/a (covered by the client journeys for this ID)
Offline test: n/a (server-side)

## Dependencies
Blocked by: none  Blocks: SHOP-08-api, SHOP-08-web, SHOP-08-ios, SHOP-08-android

## Skill
.claude/skills/sql-supabase/SKILL.md

## Open questions
none

# SHOP-08-api — Push and pull sync

## Summary
Edits made with no signal still reach the household once the device is online again.

## Spec references
SPEC §4 SHOP-08; §6.2, §6.3; §7 GET /sync and POST /sync; TESTING §3 -api row

## Scope
In: GET /sync pages changes above a cursor, including soft-deleted rows. POST /sync applies mutations in order in one transaction and is idempotent for 24 hours.
Out: The outbox on each client (SHOP-08 clients). Module rejection (X7-api). The delta branch for pantry quantity (PANT-03-api).

## Acceptance criteria
- [ ] AC1 Given a cursor, when GET /sync runs, then the response has rows with a greater sync_seq, a new cursor, and has_more.
- [ ] AC2 Given no JWT, when either route is called, then the response is 401.
- [ ] AC3 Given a mutation whose table is not in the allow-list, when it is pushed, then that mutation is rejected with unknown_table and earlier mutations in the batch stay applied.
- [ ] AC4 Given the same mutation id is pushed twice, when the second push arrives inside 24 hours, then it is applied once.

## Design
Screen: n/a — this layer has no DESIGN screen.
Deviations from the plan: none

## Offline behaviour
The endpoint exists so a shopping write done offline is accepted later (SPEC §6.5).

## Module-off behaviour
A mutation for a disabled module is rejected with module_disabled (X7). Other mutations in the batch still follow §6.3.

## Data and contract
Tables: every household table in the §8 SyncTable list  Endpoints: GET /sync; POST /sync  Types: SyncPullResponse, SyncPushRequest, Mutation
Schema change: no  Contract change: yes

## Tests required
Unit: api/test/SHOP-08-sync.test.ts → AC3 unknown_table rejected
Component: n/a (no UI)
Integration: api/test/SHOP-08-sync.int.test.ts → AC1 pull page; AC2 401; AC4 idempotent replay. Contract check covers both routes.
E2E: n/a (client journeys cover the user path)
Offline test: n/a (server-side; queued client writes are covered by the client offline test)

## Dependencies
Blocked by: SHOP-08-db, CORE-05-db  Blocks: SHOP-08-web, SHOP-08-ios, SHOP-08-android, PANT-03-api, X6-api, X7-api

## Skill
.claude/skills/node-backend/SKILL.md

## Open questions
§7 defines one sync endpoint for every household table, and §4 SHOP-08 names list operations. Proposed default: this story registers every table in the §8 SyncTable list, and later stories only add tables that are missing from that list.

# SHOP-08-web — Offline lists and the sync sheet

## Summary
Every list operation still works with no signal, and the household catches up when the signal returns.

## Spec references
SPEC §4 SHOP-08; §6.1, §6.4; DESIGN B12, C1, C2; TESTING §6, §8 E2E-SHOP-2

## Scope
In: The outbox around list writes, the sync loop on reconnect, and the sync sheet (B12).
Out: Individual list screens. Background schedulers are included here because §6.4 defines how reconnect sync runs.

## Acceptance criteria
- [ ] AC1 Given the device is offline, when I add a list item, then the local row exists and the outbox has one mutation, in one local transaction.
- [ ] AC2 Given the network returns, when kick() runs, then the outbox is pushed and then a pull runs.
- [ ] AC3 Given a push was rejected, when I open the sync sheet, then the failed state lists the mutation and offers Retry and Discard.
- [ ] AC4 Given there are pending mutations, when I look at the chip, then it reads N to sync.
- [ ] AC5 Given I tap Sync now, when the sheet handles it, then kick() runs.

## Design
Screen: DESIGN B12 — States: idle, pending, failed. Interactions: Retry, Discard, Sync now.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Shopping: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08). On iOS the loop also schedules BGAppRefreshTask, and on Android a WorkManager job every 15 minutes (§6.4).

## Module-off behaviour
Shopping off (X7): the Shopping tab is hidden and a shopping-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: outbox, list_item, shopping_list  Endpoints: GET /sync; POST /sync  Types: Mutation, SyncPullResponse, SyncPushResponse
Schema change: no  Contract change: no

## Tests required
Unit: web/src/shopping/SyncSheet.test.ts → AC1 one local transaction writes the row and the outbox; AC2 reconnect calls push then pull
Component: web/src/shopping/SyncSheet.test.tsx → AC3 failed with Retry and Discard, AC4 pending chip, AC5 Sync now. States idle, pending, failed.
Integration: n/a (no API handler in this story)
E2E: E2E-SHOP-2 web/e2e/SyncSheet.spec.ts
Offline test: yes, TESTING §6 — web/e2e/SHOP-08-sync.offline.spec.ts

## Dependencies
Blocked by: SHOP-08-db, SHOP-08-api  Blocks: SHOP-01-web, SHOP-02-web, SHOP-04-web, SHOP-09-web, PANT-01-web, PANT-03-web

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
§3 also names a service worker for the web offline shell, and §6.4 syncs on foreground, every 5 minutes, and on Realtime as well as on reconnect. Proposed default: this story includes those triggers plus the platform background job; the Realtime subscription itself is SHOP-09.

# SHOP-08-ios — Offline lists and the sync sheet

## Summary
Every list operation still works with no signal, and the household catches up when the signal returns.

## Spec references
SPEC §4 SHOP-08; §6.1, §6.4; DESIGN B12, C1, C2; TESTING §6, §8 E2E-SHOP-2

## Scope
In: The outbox around list writes, the sync loop on reconnect, and the sync sheet (B12).
Out: Individual list screens. Background schedulers are included here because §6.4 defines how reconnect sync runs.

## Acceptance criteria
- [ ] AC1 Given the device is offline, when I add a list item, then the local row exists and the outbox has one mutation, in one local transaction.
- [ ] AC2 Given the network returns, when kick() runs, then the outbox is pushed and then a pull runs.
- [ ] AC3 Given a push was rejected, when I open the sync sheet, then the failed state lists the mutation and offers Retry and Discard.
- [ ] AC4 Given there are pending mutations, when I look at the chip, then it reads N to sync.
- [ ] AC5 Given I tap Sync now, when the sheet handles it, then kick() runs.

## Design
Screen: DESIGN B12 — States: idle, pending, failed. Interactions: Retry, Discard, Sync now.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Shopping: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08). On iOS the loop also schedules BGAppRefreshTask, and on Android a WorkManager job every 15 minutes (§6.4).

## Module-off behaviour
Shopping off (X7): the Shopping tab is hidden and a shopping-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: outbox, list_item, shopping_list  Endpoints: GET /sync; POST /sync  Types: Mutation, SyncPullResponse, SyncPushResponse
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/shopping/Tests/SyncSheetTests.swift → AC1 one local transaction writes the row and the outbox; AC2 reconnect calls push then pull
Component: apps/ios/AbodeNestlyTests/SyncSheetViewTests.swift → AC3 failed with Retry and Discard, AC4 pending chip, AC5 Sync now. States idle, pending, failed.
Integration: n/a (no API handler in this story)
E2E: E2E-SHOP-2 apps/ios/AbodeNestlyUITests/SyncSheetUITests.swift
Offline test: yes, TESTING §6 — URLProtocol throws, then kick(); SyncSheetOfflineTests.swift covers the same steps as the web file

## Dependencies
Blocked by: SHOP-08-db, SHOP-08-api  Blocks: SHOP-01-ios, SHOP-02-ios, SHOP-04-ios, SHOP-09-ios, SHOP-15-ios, PANT-01-ios, PANT-03-ios

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
§3 also names a service worker for the web offline shell, and §6.4 syncs on foreground, every 5 minutes, and on Realtime as well as on reconnect. Proposed default: this story includes those triggers plus the platform background job; the Realtime subscription itself is SHOP-09.

# SHOP-08-android — Offline lists and the sync sheet

## Summary
Every list operation still works with no signal, and the household catches up when the signal returns.

## Spec references
SPEC §4 SHOP-08; §6.1, §6.4; DESIGN B12, C1, C2; TESTING §6, §8 E2E-SHOP-2

## Scope
In: The outbox around list writes, the sync loop on reconnect, and the sync sheet (B12).
Out: Individual list screens. Background schedulers are included here because §6.4 defines how reconnect sync runs.

## Acceptance criteria
- [ ] AC1 Given the device is offline, when I add a list item, then the local row exists and the outbox has one mutation, in one local transaction.
- [ ] AC2 Given the network returns, when kick() runs, then the outbox is pushed and then a pull runs.
- [ ] AC3 Given a push was rejected, when I open the sync sheet, then the failed state lists the mutation and offers Retry and Discard.
- [ ] AC4 Given there are pending mutations, when I look at the chip, then it reads N to sync.
- [ ] AC5 Given I tap Sync now, when the sheet handles it, then kick() runs.

## Design
Screen: DESIGN B12 — States: idle, pending, failed. Interactions: Retry, Discard, Sync now.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Shopping: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08). On iOS the loop also schedules BGAppRefreshTask, and on Android a WorkManager job every 15 minutes (§6.4).

## Module-off behaviour
Shopping off (X7): the Shopping tab is hidden and a shopping-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: outbox, list_item, shopping_list  Endpoints: GET /sync; POST /sync  Types: Mutation, SyncPullResponse, SyncPushResponse
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/shopping/src/test/java/app/abodenestly/shopping/SyncSheetTest.kt → AC1 one local transaction writes the row and the outbox; AC2 reconnect calls push then pull
Component: apps/android/shopping/src/test/java/app/abodenestly/shopping/SyncSheetComposeTest.kt → AC3 failed with Retry and Discard, AC4 pending chip, AC5 Sync now. States idle, pending, failed.
Integration: n/a (no API handler in this story)
E2E: E2E-SHOP-2 apps/android/shopping/src/androidTest/java/app/abodenestly/shopping/SyncSheetE2ETest.kt
Offline test: yes, TESTING §6 — Fake ApiClient throws IOException, then kick(); SyncSheetOfflineTest.kt

## Dependencies
Blocked by: SHOP-08-db, SHOP-08-api  Blocks: SHOP-01-android, SHOP-02-android, SHOP-04-android, SHOP-09-android, SHOP-15-android, PANT-01-android, PANT-03-android

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
§3 also names a service worker for the web offline shell, and §6.4 syncs on foreground, every 5 minutes, and on Realtime as well as on reconnect. Proposed default: this story includes those triggers plus the platform background job; the Realtime subscription itself is SHOP-09.

# SHOP-09-db — Publish list changes

## Summary
When one person checks an item off, the others get a signal to pull.

## Spec references
SPEC §4 SHOP-09; §6.4; §14 realtime publication; TESTING §3 -db row

## Scope
In: list_item, shopping_list, and store_item_order are in the supabase_realtime publication.
Out: The client subscription (SHOP-09 clients). Skipped layers: -api — Realtime is Supabase, not an AbodeNestly route. The publication is the database story.

## Acceptance criteria
- [ ] AC1 Given list_item is published, when a member updates a row, then a realtime event is produced for that household.
- [ ] AC2 Given a table that is not published, when it changes, then this story does not require an event from it.
- [ ] AC3 Given the publication is applied twice, when the second migration runs, then it does not error.

## Design
Screen: n/a — this layer has no DESIGN screen.
Deviations from the plan: none

## Offline behaviour
The event is online. The rows it points at stay readable offline after a pull.

## Module-off behaviour
Shopping off: the check-off is rejected (X7), so no new check-off event is produced.

## Data and contract
Tables: list_item, shopping_list, store_item_order  Endpoints: none  Types: none
Schema change: yes — supabase/migrations/20261108110500_SHOP-09_realtime.sql  Contract change: no

## Tests required
Unit: n/a (database behaviour is asserted in pgTAP, TESTING §2)
Component: n/a (no UI)
Integration: supabase/tests/SHOP-09_realtime.sql → AC1 list_item is a member of the publication; AC2 an unrelated table is not required; AC3 re-adding is idempotent
E2E: n/a (covered by the client journeys for this ID)
Offline test: n/a (server-side)

## Dependencies
Blocked by: SHOP-03-db  Blocks: SHOP-09-web, SHOP-09-ios, SHOP-09-android

## Skill
.claude/skills/sql-supabase/SKILL.md

## Open questions
none

# SHOP-09-web — Realtime check-offs

## Summary
A check-off by someone else shows up here without a manual reload.

## Spec references
SPEC §4 SHOP-09; §6.4; DESIGN C2; TESTING §3 client rows

## Scope
In: Subscribe to household realtime changes, ignore the payload, and pull.
Out: The check-off gesture (SHOP-04). The sync loop (SHOP-08). Skipped layers: -api — Realtime is Supabase, not an AbodeNestly route. The publication is the database story.

## Acceptance criteria
- [ ] AC1 Given I am subscribed, when a realtime event arrives, then the payload is ignored and a pull starts.
- [ ] AC2 Given another member checked off Milk, when the pull finishes, then Milk is checked on my list.
- [ ] AC3 Given the device is offline, when I look at the list, then I see my local rows and no error is raised about realtime.
- [ ] AC4 Given shopping is off, when the app is open, then it does not subscribe for shopping changes.

## Design
Screen: DESIGN C2 — States: populated, offline, pending-sync. Interactions: a remote check-off appears after the pull.
Deviations from the plan: none

## Offline behaviour
The live update needs a network. Until it arrives, the local list is the offline list (SPEC §6.5).

## Module-off behaviour
Shopping off (X7): the Shopping tab is hidden and a shopping-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: list_item  Endpoints: GET /sync  Types: ListItem
Schema change: no  Contract change: no

## Tests required
Unit: web/src/shopping/Realtime.test.ts → AC1 the handler calls kick and does not read the payload; AC4 no subscription when shopping is off
Component: web/src/shopping/Realtime.test.tsx → AC2 populated list updates after a fake pull; AC3 offline does not throw. Interaction: incoming event.
Integration: n/a (no API handler in this story)
E2E: E2E-SHOP-2 web/e2e/Realtime.spec.ts
Offline test: yes, TESTING §6 — web/e2e/SHOP-09-realtime.offline.spec.ts

## Dependencies
Blocked by: SHOP-09-db, SHOP-08-web, SHOP-04-web  Blocks: none

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
none

# SHOP-09-ios — Realtime check-offs

## Summary
A check-off by someone else shows up here without a manual reload.

## Spec references
SPEC §4 SHOP-09; §6.4; DESIGN C2; TESTING §3 client rows

## Scope
In: Subscribe to household realtime changes, ignore the payload, and pull.
Out: The check-off gesture (SHOP-04). The sync loop (SHOP-08). Skipped layers: -api — Realtime is Supabase, not an AbodeNestly route. The publication is the database story.

## Acceptance criteria
- [ ] AC1 Given I am subscribed, when a realtime event arrives, then the payload is ignored and a pull starts.
- [ ] AC2 Given another member checked off Milk, when the pull finishes, then Milk is checked on my list.
- [ ] AC3 Given the device is offline, when I look at the list, then I see my local rows and no error is raised about realtime.
- [ ] AC4 Given shopping is off, when the app is open, then it does not subscribe for shopping changes.

## Design
Screen: DESIGN C2 — States: populated, offline, pending-sync. Interactions: a remote check-off appears after the pull.
Deviations from the plan: none

## Offline behaviour
The live update needs a network. Until it arrives, the local list is the offline list (SPEC §6.5).

## Module-off behaviour
Shopping off (X7): the Shopping tab is hidden and a shopping-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: list_item  Endpoints: GET /sync  Types: ListItem
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/shopping/Tests/RealtimeTests.swift → AC1 the handler calls kick and does not read the payload; AC4 no subscription when shopping is off
Component: apps/ios/AbodeNestlyTests/RealtimeViewTests.swift → AC2 populated list updates after a fake pull; AC3 offline does not throw. Interaction: incoming event.
Integration: n/a (no API handler in this story)
E2E: E2E-SHOP-2 apps/ios/AbodeNestlyUITests/RealtimeUITests.swift
Offline test: yes, TESTING §6 — URLProtocol throws, then kick(); RealtimeOfflineTests.swift covers the same steps as the web file

## Dependencies
Blocked by: SHOP-09-db, SHOP-08-ios, SHOP-04-ios  Blocks: none

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
none

# SHOP-09-android — Realtime check-offs

## Summary
A check-off by someone else shows up here without a manual reload.

## Spec references
SPEC §4 SHOP-09; §6.4; DESIGN C2; TESTING §3 client rows

## Scope
In: Subscribe to household realtime changes, ignore the payload, and pull.
Out: The check-off gesture (SHOP-04). The sync loop (SHOP-08). Skipped layers: -api — Realtime is Supabase, not an AbodeNestly route. The publication is the database story.

## Acceptance criteria
- [ ] AC1 Given I am subscribed, when a realtime event arrives, then the payload is ignored and a pull starts.
- [ ] AC2 Given another member checked off Milk, when the pull finishes, then Milk is checked on my list.
- [ ] AC3 Given the device is offline, when I look at the list, then I see my local rows and no error is raised about realtime.
- [ ] AC4 Given shopping is off, when the app is open, then it does not subscribe for shopping changes.

## Design
Screen: DESIGN C2 — States: populated, offline, pending-sync. Interactions: a remote check-off appears after the pull.
Deviations from the plan: none

## Offline behaviour
The live update needs a network. Until it arrives, the local list is the offline list (SPEC §6.5).

## Module-off behaviour
Shopping off (X7): the Shopping tab is hidden and a shopping-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: list_item  Endpoints: GET /sync  Types: ListItem
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/shopping/src/test/java/app/abodenestly/shopping/RealtimeTest.kt → AC1 the handler calls kick and does not read the payload; AC4 no subscription when shopping is off
Component: apps/android/shopping/src/test/java/app/abodenestly/shopping/RealtimeComposeTest.kt → AC2 populated list updates after a fake pull; AC3 offline does not throw. Interaction: incoming event.
Integration: n/a (no API handler in this story)
E2E: E2E-SHOP-2 apps/android/shopping/src/androidTest/java/app/abodenestly/shopping/RealtimeE2ETest.kt
Offline test: yes, TESTING §6 — Fake ApiClient throws IOException, then kick(); RealtimeOfflineTest.kt

## Dependencies
Blocked by: SHOP-09-db, SHOP-08-android, SHOP-04-android  Blocks: none

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
none

# SHOP-10-db — Barcode product cache

## Summary
A barcode the household has already looked up can be answered without calling Open Food Facts again.

## Spec references
SPEC §4 SHOP-10; §11 product readable by authenticated users, written by the service role; TESTING §3 -db row

## Scope
In: product table, select for authenticated users, no insert for the authenticated role.
Out: The lookup handler (SHOP-10-api).

## Acceptance criteria
- [ ] AC1 Given an authenticated user, when they select a product, then the row is visible.
- [ ] AC2 Given an authenticated user, when they insert a product, then RLS rejects it.
- [ ] AC3 Given the service role, when it upserts a barcode, then the row is stored.

## Design
Screen: n/a — this layer has no DESIGN screen.
Deviations from the plan: none

## Offline behaviour
The cache is server-side. The list item the user adds afterwards follows SPEC §6.5.

## Module-off behaviour
The product cache is global and is not gated by the shopping flag (§11).

## Data and contract
Tables: product  Endpoints: none  Types: Product
Schema change: yes — supabase/migrations/20261108110600_SHOP-10_product.sql  Contract change: no

## Tests required
Unit: n/a (database behaviour is asserted in pgTAP, TESTING §2)
Component: n/a (no UI)
Integration: supabase/tests/SHOP-10_product.sql → AC1 authenticated select; AC2 authenticated insert denied; AC3 service role upsert
E2E: n/a (covered by the client journeys for this ID)
Offline test: n/a (server-side)

## Dependencies
Blocked by: none  Blocks: SHOP-10-api, SHOP-10-web, SHOP-10-ios, SHOP-10-android

## Skill
.claude/skills/sql-supabase/SKILL.md

## Open questions
none

# SHOP-10-api — Look up a barcode

## Summary
A scanned barcode comes back as a product name, from the cache or from Open Food Facts.

## Spec references
SPEC §4 SHOP-10; §7 GET /products/{barcode}; TESTING §3 -api row

## Scope
In: GET /products/{barcode} returns the cached Product or fetches Open Food Facts, caches it, and returns it. Unknown barcodes are 404.
Out: The cache table (SHOP-10-db). The scanner (SHOP-10 clients).

## Acceptance criteria
- [ ] AC1 Given the barcode is cached, when GET runs, then the cached Product is returned and Open Food Facts is not called.
- [ ] AC2 Given the barcode is not cached and Open Food Facts knows it, when GET runs, then the Product is returned and the cache row exists.
- [ ] AC3 Given nobody knows the barcode, when GET runs, then the response is 404.
- [ ] AC4 Given no JWT, when GET runs, then the response is 401.

## Design
Screen: n/a — this layer has no DESIGN screen.
Deviations from the plan: none

## Offline behaviour
The lookup needs a network. Adding the resulting list item is an ordinary offline shopping write once the name is on the device.

## Module-off behaviour
The lookup is not gated by the shopping flag. Adding the list item is rejected when shopping is off (X7).

## Data and contract
Tables: product  Endpoints: GET /products/{barcode}  Types: Product
Schema change: no  Contract change: yes

## Tests required
Unit: api/test/SHOP-10-product.test.ts → AC3 404 when the upstream misses
Component: n/a (no UI)
Integration: api/test/SHOP-10-product.int.test.ts → AC1 cache hit; AC2 cache fill; AC4 401. Contract check covers the route.
E2E: n/a (client journeys cover the user path)
Offline test: n/a (server-side; queued client writes are covered by the client offline test)

## Dependencies
Blocked by: SHOP-10-db  Blocks: SHOP-10-web, SHOP-10-ios, SHOP-10-android, PANT-09-web, PANT-09-ios, PANT-09-android

## Skill
.claude/skills/node-backend/SKILL.md

## Open questions
none

# SHOP-10-web — Barcode scan to add

## Summary
A person can scan a barcode and add that product to the list.

## Spec references
SPEC §4 SHOP-10; DESIGN C7; TESTING §3 client rows

## Scope
In: The scanner (C7) used to add a list item: camera, torch, manual code, and the result card.
Out: The same scanner for the pantry (PANT-09). The product endpoint (SHOP-10-api).

## Acceptance criteria
- [ ] AC1 Given the camera is allowed, when I open the scanner, then I see the scanning state and the guide box.
- [ ] AC2 Given the lookup returns a product, when the card shows, then I see the name, brand, and image, and Add puts that name on the list.
- [ ] AC3 Given the lookup returns 404, when the card shows, then I am in not-found and I can type a name.
- [ ] AC4 Given the device is offline, when I scan a code, then the item is added with the barcode as its name and the lookup is queued.
- [ ] AC5 Given camera permission is denied, when I open the scanner, then I see permission-denied and I can still enter a code.

## Design
Screen: DESIGN C7 — States: scanning, found, not-found, permission-denied, offline. Interactions: torch, enter code manually, Add.
Deviations from the plan: none

## Offline behaviour
DESIGN C7: a scan still reads offline, the lookup is queued, and the item is added with the barcode as its name until the lookup resolves. SPEC §6.5 then keeps that list edit.

## Module-off behaviour
Shopping off (X7): the Shopping tab is hidden and a shopping-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: list_item, product  Endpoints: GET /products/{barcode}  Types: Product, ListItem
Schema change: no  Contract change: no

## Tests required
Unit: web/src/shopping/Barcode.test.ts → AC4 offline add uses the barcode as the name and queues the lookup
Component: web/src/shopping/Barcode.test.tsx → AC1 scanning, AC2 found, AC3 not-found, AC5 permission-denied, AC4 offline; torch, manual code, Add
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: yes, TESTING §6 — web/e2e/SHOP-10-barcode.offline.spec.ts

## Dependencies
Blocked by: SHOP-10-db, SHOP-10-api, SHOP-02-web  Blocks: none

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
none

# SHOP-10-ios — Barcode scan to add

## Summary
A person can scan a barcode and add that product to the list.

## Spec references
SPEC §4 SHOP-10; DESIGN C7; TESTING §3 client rows

## Scope
In: The scanner (C7) used to add a list item: camera, torch, manual code, and the result card.
Out: The same scanner for the pantry (PANT-09). The product endpoint (SHOP-10-api).

## Acceptance criteria
- [ ] AC1 Given the camera is allowed, when I open the scanner, then I see the scanning state and the guide box.
- [ ] AC2 Given the lookup returns a product, when the card shows, then I see the name, brand, and image, and Add puts that name on the list.
- [ ] AC3 Given the lookup returns 404, when the card shows, then I am in not-found and I can type a name.
- [ ] AC4 Given the device is offline, when I scan a code, then the item is added with the barcode as its name and the lookup is queued.
- [ ] AC5 Given camera permission is denied, when I open the scanner, then I see permission-denied and I can still enter a code.

## Design
Screen: DESIGN C7 — States: scanning, found, not-found, permission-denied, offline. Interactions: torch, enter code manually, Add.
Deviations from the plan: none

## Offline behaviour
DESIGN C7: a scan still reads offline, the lookup is queued, and the item is added with the barcode as its name until the lookup resolves. SPEC §6.5 then keeps that list edit.

## Module-off behaviour
Shopping off (X7): the Shopping tab is hidden and a shopping-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: list_item, product  Endpoints: GET /products/{barcode}  Types: Product, ListItem
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/shopping/Tests/BarcodeTests.swift → AC4 offline add uses the barcode as the name and queues the lookup
Component: apps/ios/AbodeNestlyTests/BarcodeViewTests.swift → AC1 scanning, AC2 found, AC3 not-found, AC5 permission-denied, AC4 offline; torch, manual code, Add
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: yes, TESTING §6 — URLProtocol throws, then kick(); BarcodeOfflineTests.swift covers the same steps as the web file

## Dependencies
Blocked by: SHOP-10-db, SHOP-10-api, SHOP-02-ios  Blocks: none

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
none

# SHOP-10-android — Barcode scan to add

## Summary
A person can scan a barcode and add that product to the list.

## Spec references
SPEC §4 SHOP-10; DESIGN C7; TESTING §3 client rows

## Scope
In: The scanner (C7) used to add a list item: camera, torch, manual code, and the result card.
Out: The same scanner for the pantry (PANT-09). The product endpoint (SHOP-10-api).

## Acceptance criteria
- [ ] AC1 Given the camera is allowed, when I open the scanner, then I see the scanning state and the guide box.
- [ ] AC2 Given the lookup returns a product, when the card shows, then I see the name, brand, and image, and Add puts that name on the list.
- [ ] AC3 Given the lookup returns 404, when the card shows, then I am in not-found and I can type a name.
- [ ] AC4 Given the device is offline, when I scan a code, then the item is added with the barcode as its name and the lookup is queued.
- [ ] AC5 Given camera permission is denied, when I open the scanner, then I see permission-denied and I can still enter a code.

## Design
Screen: DESIGN C7 — States: scanning, found, not-found, permission-denied, offline. Interactions: torch, enter code manually, Add.
Deviations from the plan: none

## Offline behaviour
DESIGN C7: a scan still reads offline, the lookup is queued, and the item is added with the barcode as its name until the lookup resolves. SPEC §6.5 then keeps that list edit.

## Module-off behaviour
Shopping off (X7): the Shopping tab is hidden and a shopping-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: list_item, product  Endpoints: GET /products/{barcode}  Types: Product, ListItem
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/shopping/src/test/java/app/abodenestly/shopping/BarcodeTest.kt → AC4 offline add uses the barcode as the name and queues the lookup
Component: apps/android/shopping/src/test/java/app/abodenestly/shopping/BarcodeComposeTest.kt → AC1 scanning, AC2 found, AC3 not-found, AC5 permission-denied, AC4 offline; torch, manual code, Add
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: yes, TESTING §6 — Fake ApiClient throws IOException, then kick(); BarcodeOfflineTest.kt

## Dependencies
Blocked by: SHOP-10-db, SHOP-10-api, SHOP-02-android  Blocks: none

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
none

# SHOP-11-web — Template lists

## Summary
The household can keep a template and pour all of its lines onto a real list in one tap.

## Spec references
SPEC §4 SHOP-11; DESIGN C1; TESTING §3 client rows

## Scope
In: The Template switch on the new-list sheet, the Templates section, and Add all to list.
Out: Ordinary lists (SHOP-01). Skipped layers: -db, -api — is_template is a column on shopping_list from SHOP-01-db. Copying items is a local write.

## Acceptance criteria
- [ ] AC1 Given I turn on Template when I create a list, when I save, then the list appears under Templates and not under Lists.
- [ ] AC2 Given a template has items, when I tap Add all to list and pick a list, then that list gains those items in one action.
- [ ] AC3 Given the template is empty, when I tap Add all, then the target list is unchanged and I am told there was nothing to add.
- [ ] AC4 Given the device is offline, when I add all, then the new items are queued.

## Design
Screen: DESIGN C1 — States: populated, empty, offline. Interactions: Template switch on the new-list sheet; Add all to list.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Shopping: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Shopping off (X7): the Shopping tab is hidden and a shopping-table mutation is rejected with module_disabled. This screen is not reachable and must not error. Shopping only (the §9 matrix) still includes templates.

## Data and contract
Tables: shopping_list, list_item  Endpoints: none  Types: ShoppingList, ListItem
Schema change: no  Contract change: no

## Tests required
Unit: web/src/shopping/Templates.test.ts → AC2 one tap enqueues one upsert per template item and none for the template itself
Component: web/src/shopping/Templates.test.tsx → AC1 template section, AC3 empty template, AC4 offline; Template switch and Add all
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: yes, TESTING §6 — web/e2e/SHOP-11-templates.offline.spec.ts

## Dependencies
Blocked by: SHOP-01-web  Blocks: none

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
none

# SHOP-11-ios — Template lists

## Summary
The household can keep a template and pour all of its lines onto a real list in one tap.

## Spec references
SPEC §4 SHOP-11; DESIGN C1; TESTING §3 client rows

## Scope
In: The Template switch on the new-list sheet, the Templates section, and Add all to list.
Out: Ordinary lists (SHOP-01). Skipped layers: -db, -api — is_template is a column on shopping_list from SHOP-01-db. Copying items is a local write.

## Acceptance criteria
- [ ] AC1 Given I turn on Template when I create a list, when I save, then the list appears under Templates and not under Lists.
- [ ] AC2 Given a template has items, when I tap Add all to list and pick a list, then that list gains those items in one action.
- [ ] AC3 Given the template is empty, when I tap Add all, then the target list is unchanged and I am told there was nothing to add.
- [ ] AC4 Given the device is offline, when I add all, then the new items are queued.

## Design
Screen: DESIGN C1 — States: populated, empty, offline. Interactions: Template switch on the new-list sheet; Add all to list.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Shopping: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Shopping off (X7): the Shopping tab is hidden and a shopping-table mutation is rejected with module_disabled. This screen is not reachable and must not error. Shopping only (the §9 matrix) still includes templates.

## Data and contract
Tables: shopping_list, list_item  Endpoints: none  Types: ShoppingList, ListItem
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/shopping/Tests/TemplatesTests.swift → AC2 one tap enqueues one upsert per template item and none for the template itself
Component: apps/ios/AbodeNestlyTests/TemplatesViewTests.swift → AC1 template section, AC3 empty template, AC4 offline; Template switch and Add all
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: yes, TESTING §6 — URLProtocol throws, then kick(); TemplatesOfflineTests.swift covers the same steps as the web file

## Dependencies
Blocked by: SHOP-01-ios  Blocks: none

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
none

# SHOP-11-android — Template lists

## Summary
The household can keep a template and pour all of its lines onto a real list in one tap.

## Spec references
SPEC §4 SHOP-11; DESIGN C1; TESTING §3 client rows

## Scope
In: The Template switch on the new-list sheet, the Templates section, and Add all to list.
Out: Ordinary lists (SHOP-01). Skipped layers: -db, -api — is_template is a column on shopping_list from SHOP-01-db. Copying items is a local write.

## Acceptance criteria
- [ ] AC1 Given I turn on Template when I create a list, when I save, then the list appears under Templates and not under Lists.
- [ ] AC2 Given a template has items, when I tap Add all to list and pick a list, then that list gains those items in one action.
- [ ] AC3 Given the template is empty, when I tap Add all, then the target list is unchanged and I am told there was nothing to add.
- [ ] AC4 Given the device is offline, when I add all, then the new items are queued.

## Design
Screen: DESIGN C1 — States: populated, empty, offline. Interactions: Template switch on the new-list sheet; Add all to list.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Shopping: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Shopping off (X7): the Shopping tab is hidden and a shopping-table mutation is rejected with module_disabled. This screen is not reachable and must not error. Shopping only (the §9 matrix) still includes templates.

## Data and contract
Tables: shopping_list, list_item  Endpoints: none  Types: ShoppingList, ListItem
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/shopping/src/test/java/app/abodenestly/shopping/TemplatesTest.kt → AC2 one tap enqueues one upsert per template item and none for the template itself
Component: apps/android/shopping/src/test/java/app/abodenestly/shopping/TemplatesComposeTest.kt → AC1 template section, AC3 empty template, AC4 offline; Template switch and Add all
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: yes, TESTING §6 — Fake ApiClient throws IOException, then kick(); TemplatesOfflineTest.kt

## Dependencies
Blocked by: SHOP-01-android  Blocks: none

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
none

# SHOP-12-db — Price memory

## Summary
The household can remember what an item cost at a store.

## Spec references
SPEC §4 SHOP-12; §8 ItemPrice; TESTING §3 -db row

## Scope
In: item_price rows for a store and a name_key. Price is numeric, never float.
Out: Entering and showing the price (SHOP-12 clients). Skipped layers: -api — prices are outbox rows. §7 has no price endpoint.

## Acceptance criteria
- [ ] AC1 Given a member, when they insert a price, then the numeric price and seen_at are stored.
- [ ] AC2 Given two prices for the same store and name, when they are read, then both rows exist and the newer seen_at is distinguishable.
- [ ] AC3 Given a non-member, when they select the price, then they see nothing.

## Design
Screen: n/a — this layer has no DESIGN screen.
Deviations from the plan: none

## Offline behaviour
Price writes sync from the offline outbox (SPEC §6.5).

## Module-off behaviour
Shopping off (X7): the Shopping tab is hidden and a shopping-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: item_price  Endpoints: none  Types: ItemPrice
Schema change: yes — supabase/migrations/20261108110700_SHOP-12_item_price.sql  Contract change: no

## Tests required
Unit: n/a (database behaviour is asserted in pgTAP, TESTING §2)
Component: n/a (no UI)
Integration: supabase/tests/SHOP-12_price.sql → AC1 numeric price stored; AC2 two observations kept; AC3 non-member denied
E2E: n/a (covered by the client journeys for this ID)
Offline test: n/a (server-side)

## Dependencies
Blocked by: SHOP-05-db  Blocks: SHOP-12-web, SHOP-12-ios, SHOP-12-android

## Skill
.claude/skills/sql-supabase/SKILL.md

## Open questions
none

# SHOP-12-web — Price memory per store

## Summary
Checking something off can record what it cost at that store, and next time the last price sits beside the item.

## Spec references
SPEC §4 SHOP-12; DESIGN C2, C3; TESTING §3 client rows

## Scope
In: The price field on the item sheet and the last-price caption on the list row.
Out: Check-off (SHOP-04). Stores (SHOP-05). Skipped layers: -api — prices are outbox rows. §7 has no price endpoint.

## Acceptance criteria
- [ ] AC1 Given the list has a store, when I enter a price on check-off, then an item_price row is queued for that store and name.
- [ ] AC2 Given a last price exists, when I view the list, then the row shows that price and the store name.
- [ ] AC3 Given the list has no store, when I open the item, then the price field is not offered.
- [ ] AC4 Given the device is offline, when I enter a price, then it is queued and the caption updates locally.

## Design
Screen: DESIGN C3 — States: edit, new. Interactions: enter a price; the list row shows the last price.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Shopping: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Shopping off (X7): the Shopping tab is hidden and a shopping-table mutation is rejected with module_disabled. This screen is not reachable and must not error. Shopping only (the §9 matrix) still includes prices.

## Data and contract
Tables: item_price  Endpoints: none  Types: ItemPrice
Schema change: no  Contract change: no

## Tests required
Unit: web/src/shopping/Price.test.ts → AC1 the queued price is a decimal for that store and name_key; AC3 no store hides the field
Component: web/src/shopping/Price.test.tsx → AC2 the caption renders the last price; AC4 offline. States new and edit.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: yes, TESTING §6 — web/e2e/SHOP-12-price.offline.spec.ts

## Dependencies
Blocked by: SHOP-12-db, SHOP-04-web, SHOP-05-web  Blocks: none

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
§8 ItemPrice.price is a number and §4 does not name the currency. Proposed default: format the caption with household.currency from CORE-05, and store the amount as a decimal.

# SHOP-12-ios — Price memory per store

## Summary
Checking something off can record what it cost at that store, and next time the last price sits beside the item.

## Spec references
SPEC §4 SHOP-12; DESIGN C2, C3; TESTING §3 client rows

## Scope
In: The price field on the item sheet and the last-price caption on the list row.
Out: Check-off (SHOP-04). Stores (SHOP-05). Skipped layers: -api — prices are outbox rows. §7 has no price endpoint.

## Acceptance criteria
- [ ] AC1 Given the list has a store, when I enter a price on check-off, then an item_price row is queued for that store and name.
- [ ] AC2 Given a last price exists, when I view the list, then the row shows that price and the store name.
- [ ] AC3 Given the list has no store, when I open the item, then the price field is not offered.
- [ ] AC4 Given the device is offline, when I enter a price, then it is queued and the caption updates locally.

## Design
Screen: DESIGN C3 — States: edit, new. Interactions: enter a price; the list row shows the last price.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Shopping: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Shopping off (X7): the Shopping tab is hidden and a shopping-table mutation is rejected with module_disabled. This screen is not reachable and must not error. Shopping only (the §9 matrix) still includes prices.

## Data and contract
Tables: item_price  Endpoints: none  Types: ItemPrice
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/shopping/Tests/PriceTests.swift → AC1 the queued price is a decimal for that store and name_key; AC3 no store hides the field
Component: apps/ios/AbodeNestlyTests/PriceViewTests.swift → AC2 the caption renders the last price; AC4 offline. States new and edit.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: yes, TESTING §6 — URLProtocol throws, then kick(); PriceOfflineTests.swift covers the same steps as the web file

## Dependencies
Blocked by: SHOP-12-db, SHOP-04-ios, SHOP-05-ios  Blocks: none

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
§8 ItemPrice.price is a number and §4 does not name the currency. Proposed default: format the caption with household.currency from CORE-05, and store the amount as a decimal.

# SHOP-12-android — Price memory per store

## Summary
Checking something off can record what it cost at that store, and next time the last price sits beside the item.

## Spec references
SPEC §4 SHOP-12; DESIGN C2, C3; TESTING §3 client rows

## Scope
In: The price field on the item sheet and the last-price caption on the list row.
Out: Check-off (SHOP-04). Stores (SHOP-05). Skipped layers: -api — prices are outbox rows. §7 has no price endpoint.

## Acceptance criteria
- [ ] AC1 Given the list has a store, when I enter a price on check-off, then an item_price row is queued for that store and name.
- [ ] AC2 Given a last price exists, when I view the list, then the row shows that price and the store name.
- [ ] AC3 Given the list has no store, when I open the item, then the price field is not offered.
- [ ] AC4 Given the device is offline, when I enter a price, then it is queued and the caption updates locally.

## Design
Screen: DESIGN C3 — States: edit, new. Interactions: enter a price; the list row shows the last price.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Shopping: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Shopping off (X7): the Shopping tab is hidden and a shopping-table mutation is rejected with module_disabled. This screen is not reachable and must not error. Shopping only (the §9 matrix) still includes prices.

## Data and contract
Tables: item_price  Endpoints: none  Types: ItemPrice
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/shopping/src/test/java/app/abodenestly/shopping/PriceTest.kt → AC1 the queued price is a decimal for that store and name_key; AC3 no store hides the field
Component: apps/android/shopping/src/test/java/app/abodenestly/shopping/PriceComposeTest.kt → AC2 the caption renders the last price; AC4 offline. States new and edit.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: yes, TESTING §6 — Fake ApiClient throws IOException, then kick(); PriceOfflineTest.kt

## Dependencies
Blocked by: SHOP-12-db, SHOP-04-android, SHOP-05-android  Blocks: none

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
§8 ItemPrice.price is a number and §4 does not name the currency. Proposed default: format the caption with household.currency from CORE-05, and store the amount as a decimal.

# SHOP-13-api — Read-only share link

## Summary
The household can hand someone a link that shows the list and nothing else, and can take the link back.

## Spec references
SPEC §4 SHOP-13; §7 share routes; §11 public payload; TESTING §3 -api row, §8 E2E-SHOP-4

## Scope
In: POST and DELETE /lists/{id}/share, and public GET /shared/{token}.
Out: The share sheet (SHOP-13 clients) and the public page (SHOP-13-web).

## Acceptance criteria
- [ ] AC1 Given a member, when they POST share, then the response is a public URL.
- [ ] AC2 Given no JWT, when they POST or DELETE, then the response is 401. GET /shared/{token} stays public.
- [ ] AC3 Given an unknown token, when GET /shared/{token} runs, then the response is 404.
- [ ] AC4 Given a live token, when GET runs, then the body has the list name and the unchecked items' names and quantities, and no other household data.

## Design
Screen: n/a — this layer has no DESIGN screen.
Deviations from the plan: none

## Offline behaviour
Creating or revoking the link needs a network. SPEC §6.5 does not list share management as an offline write.

## Module-off behaviour
Shopping off: creating or revoking a share is rejected with module_disabled.

## Data and contract
Tables: shopping_list, list_item  Endpoints: POST /lists/{id}/share; DELETE /lists/{id}/share; GET /shared/{token}  Types: ShoppingList, ListItem
Schema change: no  Contract change: yes

## Tests required
Unit: api/test/SHOP-13-share.test.ts → AC4 the public mapper drops checked items and household ids
Component: n/a (no UI)
Integration: api/test/SHOP-13-share.int.test.ts → AC1 URL returned; AC2 401 on POST; AC3 unknown token 404. Contract check covers the three routes.
E2E: n/a (client journeys cover the user path)
Offline test: n/a (server-side; queued client writes are covered by the client offline test)

## Dependencies
Blocked by: SHOP-01-db  Blocks: SHOP-13-ios, SHOP-13-android, SHOP-13-web

## Skill
.claude/skills/node-backend/SKILL.md

## Open questions
§7 returns the full list and items. §11 returns only the list name and unchecked names and quantities. Proposed default: the public body follows §11. §9 does not say whether an existing token keeps serving after shopping is switched off. Proposed default: GET /shared/{token} returns 404 when shopping is off.

# SHOP-13-ios — Share a list

## Summary
A person can turn on a link for the list they are looking at, copy it, and turn it off.

## Spec references
SPEC §4 SHOP-13; DESIGN C8; TESTING §8 E2E-SHOP-4

## Scope
In: The share sheet (C8) on web, iOS, and Android.
Out: The public page is SHOP-13-web only. There is no SHOP-13-db.

## Acceptance criteria
- [ ] AC1 Given sharing is off, when I open the sheet, then the switch is off.
- [ ] AC2 Given I turn the switch on, when the request succeeds, then I see a copyable link and the line about unchecked items.
- [ ] AC3 Given sharing is on, when I tap Stop sharing, then DELETE runs and the switch returns to off.
- [ ] AC4 Given the device is offline, when I open the sheet, then the switch is disabled.

## Design
Screen: DESIGN C8 — States: off, on, offline. Interactions: toggle the switch; copy the link; Stop sharing.
Deviations from the plan: none

## Offline behaviour
The sheet needs a network (DESIGN C8). The list itself stays readable offline.

## Module-off behaviour
Shopping off (X7): the Shopping tab is hidden and a shopping-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: shopping_list  Endpoints: POST /lists/{id}/share; DELETE /lists/{id}/share  Types: ShoppingList
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/shopping/Tests/ShareSheetTests.swift → AC4 offline disables the switch and does not call the API
Component: apps/ios/AbodeNestlyTests/ShareSheetViewTests.swift → AC1 off, AC2 on, AC3 stop sharing; toggle and copy
Integration: n/a (no API handler in this story)
E2E: E2E-SHOP-4 apps/ios/AbodeNestlyUITests/ShareSheetUITests.swift
Offline test: n/a (the sheet is online-only; list edits keep their own offline tests)

## Dependencies
Blocked by: SHOP-13-api, SHOP-01-ios  Blocks: none

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
none

# SHOP-13-android — Share a list

## Summary
A person can turn on a link for the list they are looking at, copy it, and turn it off.

## Spec references
SPEC §4 SHOP-13; DESIGN C8; TESTING §8 E2E-SHOP-4

## Scope
In: The share sheet (C8) on web, iOS, and Android.
Out: The public page is SHOP-13-web only. There is no SHOP-13-db.

## Acceptance criteria
- [ ] AC1 Given sharing is off, when I open the sheet, then the switch is off.
- [ ] AC2 Given I turn the switch on, when the request succeeds, then I see a copyable link and the line about unchecked items.
- [ ] AC3 Given sharing is on, when I tap Stop sharing, then DELETE runs and the switch returns to off.
- [ ] AC4 Given the device is offline, when I open the sheet, then the switch is disabled.

## Design
Screen: DESIGN C8 — States: off, on, offline. Interactions: toggle the switch; copy the link; Stop sharing.
Deviations from the plan: none

## Offline behaviour
The sheet needs a network (DESIGN C8). The list itself stays readable offline.

## Module-off behaviour
Shopping off (X7): the Shopping tab is hidden and a shopping-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: shopping_list  Endpoints: POST /lists/{id}/share; DELETE /lists/{id}/share  Types: ShoppingList
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/shopping/src/test/java/app/abodenestly/shopping/ShareSheetTest.kt → AC4 offline disables the switch and does not call the API
Component: apps/android/shopping/src/test/java/app/abodenestly/shopping/ShareSheetComposeTest.kt → AC1 off, AC2 on, AC3 stop sharing; toggle and copy
Integration: n/a (no API handler in this story)
E2E: E2E-SHOP-4 apps/android/shopping/src/androidTest/java/app/abodenestly/shopping/ShareSheetE2ETest.kt
Offline test: n/a (the sheet is online-only; list edits keep their own offline tests)

## Dependencies
Blocked by: SHOP-13-api, SHOP-01-android  Blocks: none

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
none

# SHOP-13-web — Share a list and open the public page

## Summary
A member can turn on a read-only link, and someone without an account can open it and see the unchecked items.

## Spec references
SPEC §4 SHOP-13; §11; DESIGN C8, C9; TESTING §8 E2E-SHOP-4

## Scope
In: The share sheet (C8) and the public page (C9) at /shared/:token.
Out: iOS and Android get the sheet only (SHOP-13-ios, SHOP-13-android). There is no SHOP-13-db.

## Acceptance criteria
- [ ] AC1 Given sharing is off, when I open the sheet, then the switch is off.
- [ ] AC2 Given I turn the switch on, when the request succeeds, then I see a copyable link.
- [ ] AC3 Given sharing is on, when I tap Stop sharing, then the switch returns to off.
- [ ] AC4 Given the browser is offline, when I open the sheet, then the switch is disabled.
- [ ] AC5 Given a live token, when I open /shared/:token signed out, then I see the list name and the unchecked items with quantities, and no edit control.
- [ ] AC6 Given the token is unknown, when the page loads, then I see the expired state.

## Design
Screen: DESIGN C8 — States: off, on, offline. Interactions: toggle, copy, Stop sharing. DESIGN C9 — States: loading, populated, empty, expired. Interactions: load the public page.
Deviations from the plan: none

## Offline behaviour
The sheet and the public page need a network (DESIGN C8, C9). The member's own list stays readable offline.

## Module-off behaviour
Shopping off: the sheet is not reachable (X7). The public page shows expired when the API returns 404, which is the default on SHOP-13-api.

## Data and contract
Tables: shopping_list, list_item  Endpoints: POST /lists/{id}/share; DELETE /lists/{id}/share; GET /shared/{token}  Types: ShoppingList, ListItem
Schema change: no  Contract change: no

## Tests required
Unit: web/src/shopping/shared/mapPublicList.test.ts → AC5 drops checked items and household fields
Component: web/src/shopping/ShareSheet.test.tsx → AC1 off, AC2 on, AC3 stop, AC4 offline; web/src/shopping/shared/SharedList.test.tsx → AC5 populated, AC6 expired, plus loading and empty
Integration: n/a (the routes are covered by SHOP-13-api)
E2E: E2E-SHOP-4 web/e2e/SHOP-13-shared.spec.ts → AC5 a signed-out visitor sees unchecked items
Offline test: n/a (the sheet and the public page are online-only)

## Dependencies
Blocked by: SHOP-13-api, SHOP-01-web  Blocks: none

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
none

# SHOP-14-web — Voice add

## Summary
A person can say what to buy, including a quantity and a unit, and the line lands on the list.

## Spec references
SPEC §4 SHOP-14; DESIGN C6; TESTING §3 client rows

## Scope
In: The voice sheet (C6): listening, the parsed chips, Add, and Try again.
Out: Quick add text (SHOP-02). Quantity fields (SHOP-03). Skipped layers: -db, -api — speech-to-text is on device. Parsing and the list insert use list_item from SHOP-03.

## Acceptance criteria
- [ ] AC1 Given speech recognition is available, when I finish saying "2 litres milk", then the parsed state shows quantity 2, unit litres, and name milk.
- [ ] AC2 Given the parsed chips are right, when I tap Add, then that item is on the list.
- [ ] AC3 Given I tap Try again, when listening restarts, then the previous chips are cleared.
- [ ] AC4 Given permission is denied, when I open the sheet, then I see permission-denied.
- [ ] AC5 Given recognition is unavailable, when I open the sheet, then I see unsupported and I am pointed back at the keyboard.

## Design
Screen: DESIGN C6 — States: listening, parsed, permission-denied, unsupported. Interactions: Add, Try again.
Deviations from the plan: none

## Offline behaviour
The list insert follows SPEC §6.5. DESIGN C6 uses on-device recognition where it exists; otherwise the sheet is unsupported. Recognition itself is not required to work offline.

## Module-off behaviour
Shopping off (X7): the Shopping tab is hidden and a shopping-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: list_item  Endpoints: none  Types: ListItem
Schema change: no  Contract change: no

## Tests required
Unit: web/src/shopping/VoiceAdd.test.ts → AC1 the parser maps "2 litres milk" to quantity 2, unit litres, name milk
Component: web/src/shopping/VoiceAdd.test.tsx → AC2 parsed Add, AC3 Try again, AC4 permission-denied, AC5 unsupported, listening
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: yes, TESTING §6 — web/e2e/SHOP-14-voice.offline.spec.ts

## Dependencies
Blocked by: SHOP-03-web  Blocks: none

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
§4 does not say whether recognition must work offline. Proposed default: use on-device recognition when the platform has it, and otherwise show unsupported, matching DESIGN C6.

# SHOP-14-ios — Voice add

## Summary
A person can say what to buy, including a quantity and a unit, and the line lands on the list.

## Spec references
SPEC §4 SHOP-14; DESIGN C6; TESTING §3 client rows

## Scope
In: The voice sheet (C6): listening, the parsed chips, Add, and Try again.
Out: Quick add text (SHOP-02). Quantity fields (SHOP-03). Skipped layers: -db, -api — speech-to-text is on device. Parsing and the list insert use list_item from SHOP-03.

## Acceptance criteria
- [ ] AC1 Given speech recognition is available, when I finish saying "2 litres milk", then the parsed state shows quantity 2, unit litres, and name milk.
- [ ] AC2 Given the parsed chips are right, when I tap Add, then that item is on the list.
- [ ] AC3 Given I tap Try again, when listening restarts, then the previous chips are cleared.
- [ ] AC4 Given permission is denied, when I open the sheet, then I see permission-denied.
- [ ] AC5 Given recognition is unavailable, when I open the sheet, then I see unsupported and I am pointed back at the keyboard.

## Design
Screen: DESIGN C6 — States: listening, parsed, permission-denied, unsupported. Interactions: Add, Try again.
Deviations from the plan: none

## Offline behaviour
The list insert follows SPEC §6.5. DESIGN C6 uses on-device recognition where it exists; otherwise the sheet is unsupported. Recognition itself is not required to work offline.

## Module-off behaviour
Shopping off (X7): the Shopping tab is hidden and a shopping-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: list_item  Endpoints: none  Types: ListItem
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/shopping/Tests/VoiceAddTests.swift → AC1 the parser maps "2 litres milk" to quantity 2, unit litres, name milk
Component: apps/ios/AbodeNestlyTests/VoiceAddViewTests.swift → AC2 parsed Add, AC3 Try again, AC4 permission-denied, AC5 unsupported, listening
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: yes, TESTING §6 — URLProtocol throws, then kick(); VoiceAddOfflineTests.swift covers the same steps as the web file

## Dependencies
Blocked by: SHOP-03-ios  Blocks: none

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
§4 does not say whether recognition must work offline. Proposed default: use on-device recognition when the platform has it, and otherwise show unsupported, matching DESIGN C6.

# SHOP-14-android — Voice add

## Summary
A person can say what to buy, including a quantity and a unit, and the line lands on the list.

## Spec references
SPEC §4 SHOP-14; DESIGN C6; TESTING §3 client rows

## Scope
In: The voice sheet (C6): listening, the parsed chips, Add, and Try again.
Out: Quick add text (SHOP-02). Quantity fields (SHOP-03). Skipped layers: -db, -api — speech-to-text is on device. Parsing and the list insert use list_item from SHOP-03.

## Acceptance criteria
- [ ] AC1 Given speech recognition is available, when I finish saying "2 litres milk", then the parsed state shows quantity 2, unit litres, and name milk.
- [ ] AC2 Given the parsed chips are right, when I tap Add, then that item is on the list.
- [ ] AC3 Given I tap Try again, when listening restarts, then the previous chips are cleared.
- [ ] AC4 Given permission is denied, when I open the sheet, then I see permission-denied.
- [ ] AC5 Given recognition is unavailable, when I open the sheet, then I see unsupported and I am pointed back at the keyboard.

## Design
Screen: DESIGN C6 — States: listening, parsed, permission-denied, unsupported. Interactions: Add, Try again.
Deviations from the plan: none

## Offline behaviour
The list insert follows SPEC §6.5. DESIGN C6 uses on-device recognition where it exists; otherwise the sheet is unsupported. Recognition itself is not required to work offline.

## Module-off behaviour
Shopping off (X7): the Shopping tab is hidden and a shopping-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: list_item  Endpoints: none  Types: ListItem
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/shopping/src/test/java/app/abodenestly/shopping/VoiceAddTest.kt → AC1 the parser maps "2 litres milk" to quantity 2, unit litres, name milk
Component: apps/android/shopping/src/test/java/app/abodenestly/shopping/VoiceAddComposeTest.kt → AC2 parsed Add, AC3 Try again, AC4 permission-denied, AC5 unsupported, listening
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: yes, TESTING §6 — Fake ApiClient throws IOException, then kick(); VoiceAddOfflineTest.kt

## Dependencies
Blocked by: SHOP-03-android  Blocks: none

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
§4 does not say whether recognition must work offline. Proposed default: use on-device recognition when the platform has it, and otherwise show unsupported, matching DESIGN C6.

# SHOP-15-ios — Add to the shopping list by voice assistant

## Summary
A person can tell the assistant to add something to the shopping list without opening the app first.

## Spec references
SPEC §4 SHOP-15; TESTING §3 -ios and -android rows

## Scope
In: iOS App Intent and Android assistant shortcut that add an item named X.
Out: The in-app voice sheet (SHOP-14). Choosing among households is called out below. Skipped layers: -db, -api, -web — the feature names Siri and Google Assistant. There is no web assistant and no endpoint; the shortcut writes the same list item as SHOP-02.

## Acceptance criteria
- [ ] AC1 Given the shortcut is installed, when the assistant receives "Add milk to my shopping list", then an item named milk is added.
- [ ] AC2 Given the phrase has no item, when the shortcut runs, then nothing is added and the assistant reports that.
- [ ] AC3 Given the device is offline, when the shortcut runs, then the item is written to the local database and the outbox.
- [ ] AC4 Given shopping is off for the target household, when the shortcut runs, then no item is added.

## Design
Screen: n/a — there is no DESIGN screen for the assistant. The list row it creates is DESIGN C2.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Shopping: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Shopping off (X7): the Shopping tab is hidden and a shopping-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: list_item  Endpoints: none  Types: ListItem
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/shopping/Tests/AssistantShortcutTests.swift → AC1 the phrase yields one list_item named milk; AC2 empty phrase writes nothing; AC4 shopping off writes nothing
Component: apps/ios/AbodeNestlyTests/AssistantShortcutViewTests.swift → n/a (no screen; the shortcut is covered by the unit test). AC3 is the offline test.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: yes, TESTING §6 — URLProtocol throws, then kick(); AssistantShortcutOfflineTests.swift covers the same steps as the web file

## Dependencies
Blocked by: SHOP-02-ios, SHOP-08-ios  Blocks: none

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
§4 does not say which household or which list receives the item. Proposed default: the last opened household and its Groceries list, which is the default list from CORE-02.

# SHOP-15-android — Add to the shopping list by voice assistant

## Summary
A person can tell the assistant to add something to the shopping list without opening the app first.

## Spec references
SPEC §4 SHOP-15; TESTING §3 -ios and -android rows

## Scope
In: iOS App Intent and Android assistant shortcut that add an item named X.
Out: The in-app voice sheet (SHOP-14). Choosing among households is called out below. Skipped layers: -db, -api, -web — the feature names Siri and Google Assistant. There is no web assistant and no endpoint; the shortcut writes the same list item as SHOP-02.

## Acceptance criteria
- [ ] AC1 Given the shortcut is installed, when the assistant receives "Add milk to my shopping list", then an item named milk is added.
- [ ] AC2 Given the phrase has no item, when the shortcut runs, then nothing is added and the assistant reports that.
- [ ] AC3 Given the device is offline, when the shortcut runs, then the item is written to the local database and the outbox.
- [ ] AC4 Given shopping is off for the target household, when the shortcut runs, then no item is added.

## Design
Screen: n/a — there is no DESIGN screen for the assistant. The list row it creates is DESIGN C2.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Shopping: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Shopping off (X7): the Shopping tab is hidden and a shopping-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: list_item  Endpoints: none  Types: ListItem
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/shopping/src/test/java/app/abodenestly/shopping/AssistantShortcutTest.kt → AC1 the phrase yields one list_item named milk; AC2 empty phrase writes nothing; AC4 shopping off writes nothing
Component: apps/android/shopping/src/test/java/app/abodenestly/shopping/AssistantShortcutComposeTest.kt → n/a (no screen; the shortcut is covered by the unit test). AC3 is the offline test.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: yes, TESTING §6 — Fake ApiClient throws IOException, then kick(); AssistantShortcutOfflineTest.kt

## Dependencies
Blocked by: SHOP-02-android, SHOP-08-android  Blocks: none

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
§4 does not say which household or which list receives the item. Proposed default: the last opened household and its Groceries list, which is the default list from CORE-02.

# SHOP-16-web — Pantry-sourced items

## Summary
A line that came from the pantry is marked, and checking it off offers to put the stock back.

## Spec references
SPEC §4 SHOP-16; §9 X2; DESIGN C2; TESTING §8 E2E-PANT-2

## Scope
In: The from pantry badge and the Restock pantry prompt on check-off.
Out: Inserting the line (X1). Creating the movement when the person taps Yes (X2, which covers PANT-15). Skipped layers: -db, -api — the badge and the prompt are client UI. The movement they confirm is X2. The row is inserted by X1-db.

## Acceptance criteria
- [ ] AC1 Given a list item has source pantry, when I view the list, then the row shows the badge from pantry.
- [ ] AC2 Given I check that item off and pantry is on, when the check lands, then I am asked Restock pantry? with Yes and No.
- [ ] AC3 Given I tap No, when the prompt closes, then no pantry movement is written.
- [ ] AC4 Given pantry is off, when I check the item off, then there is no badge and no prompt.
- [ ] AC5 Given the device is offline, when I view the badge, then it still renders from the local row.

## Design
Screen: DESIGN C2 — States: populated, offline. Interactions: check a pantry-sourced item shows Restock pantry? Yes and No, only if pantry is on.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Shopping: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Shopping only (the §9 matrix): no pantry badges and no restock prompt. Shopping off: the list is hidden (X7).

## Data and contract
Tables: list_item  Endpoints: none  Types: ListItem
Schema change: no  Contract change: no

## Tests required
Unit: web/src/shopping/PantryBadge.test.ts → AC3 No does not enqueue a pantry delta; AC4 pantry off hides the badge
Component: web/src/shopping/PantryBadge.test.tsx → AC1 badge text, AC2 the prompt, AC5 offline. Interaction: Yes hands off to X2, No dismisses.
Integration: n/a (no API handler in this story)
E2E: E2E-PANT-2 web/e2e/PantryBadge.spec.ts
Offline test: yes, TESTING §6 — web/e2e/SHOP-16-badge.offline.spec.ts

## Dependencies
Blocked by: X1-db, SHOP-04-web  Blocks: PANT-15-web, X2-web

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
none

# SHOP-16-ios — Pantry-sourced items

## Summary
A line that came from the pantry is marked, and checking it off offers to put the stock back.

## Spec references
SPEC §4 SHOP-16; §9 X2; DESIGN C2; TESTING §8 E2E-PANT-2

## Scope
In: The from pantry badge and the Restock pantry prompt on check-off.
Out: Inserting the line (X1). Creating the movement when the person taps Yes (X2, which covers PANT-15). Skipped layers: -db, -api — the badge and the prompt are client UI. The movement they confirm is X2. The row is inserted by X1-db.

## Acceptance criteria
- [ ] AC1 Given a list item has source pantry, when I view the list, then the row shows the badge from pantry.
- [ ] AC2 Given I check that item off and pantry is on, when the check lands, then I am asked Restock pantry? with Yes and No.
- [ ] AC3 Given I tap No, when the prompt closes, then no pantry movement is written.
- [ ] AC4 Given pantry is off, when I check the item off, then there is no badge and no prompt.
- [ ] AC5 Given the device is offline, when I view the badge, then it still renders from the local row.

## Design
Screen: DESIGN C2 — States: populated, offline. Interactions: check a pantry-sourced item shows Restock pantry? Yes and No, only if pantry is on.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Shopping: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Shopping only (the §9 matrix): no pantry badges and no restock prompt. Shopping off: the list is hidden (X7).

## Data and contract
Tables: list_item  Endpoints: none  Types: ListItem
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/shopping/Tests/PantryBadgeTests.swift → AC3 No does not enqueue a pantry delta; AC4 pantry off hides the badge
Component: apps/ios/AbodeNestlyTests/PantryBadgeViewTests.swift → AC1 badge text, AC2 the prompt, AC5 offline. Interaction: Yes hands off to X2, No dismisses.
Integration: n/a (no API handler in this story)
E2E: E2E-PANT-2 apps/ios/AbodeNestlyUITests/PantryBadgeUITests.swift
Offline test: yes, TESTING §6 — URLProtocol throws, then kick(); PantryBadgeOfflineTests.swift covers the same steps as the web file

## Dependencies
Blocked by: X1-db, SHOP-04-ios  Blocks: PANT-15-ios, X2-ios

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
none

# SHOP-16-android — Pantry-sourced items

## Summary
A line that came from the pantry is marked, and checking it off offers to put the stock back.

## Spec references
SPEC §4 SHOP-16; §9 X2; DESIGN C2; TESTING §8 E2E-PANT-2

## Scope
In: The from pantry badge and the Restock pantry prompt on check-off.
Out: Inserting the line (X1). Creating the movement when the person taps Yes (X2, which covers PANT-15). Skipped layers: -db, -api — the badge and the prompt are client UI. The movement they confirm is X2. The row is inserted by X1-db.

## Acceptance criteria
- [ ] AC1 Given a list item has source pantry, when I view the list, then the row shows the badge from pantry.
- [ ] AC2 Given I check that item off and pantry is on, when the check lands, then I am asked Restock pantry? with Yes and No.
- [ ] AC3 Given I tap No, when the prompt closes, then no pantry movement is written.
- [ ] AC4 Given pantry is off, when I check the item off, then there is no badge and no prompt.
- [ ] AC5 Given the device is offline, when I view the badge, then it still renders from the local row.

## Design
Screen: DESIGN C2 — States: populated, offline. Interactions: check a pantry-sourced item shows Restock pantry? Yes and No, only if pantry is on.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Shopping: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Shopping only (the §9 matrix): no pantry badges and no restock prompt. Shopping off: the list is hidden (X7).

## Data and contract
Tables: list_item  Endpoints: none  Types: ListItem
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/shopping/src/test/java/app/abodenestly/shopping/PantryBadgeTest.kt → AC3 No does not enqueue a pantry delta; AC4 pantry off hides the badge
Component: apps/android/shopping/src/test/java/app/abodenestly/shopping/PantryBadgeComposeTest.kt → AC1 badge text, AC2 the prompt, AC5 offline. Interaction: Yes hands off to X2, No dismisses.
Integration: n/a (no API handler in this story)
E2E: E2E-PANT-2 apps/android/shopping/src/androidTest/java/app/abodenestly/shopping/PantryBadgeE2ETest.kt
Offline test: yes, TESTING §6 — Fake ApiClient throws IOException, then kick(); PantryBadgeOfflineTest.kt

## Dependencies
Blocked by: X1-db, SHOP-04-android  Blocks: PANT-15-android, X2-android

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
none

# SHOP-17-web — Maintenance consumables on the list

## Summary
A line that was sent from a maintenance task says which job it is for.

## Spec references
SPEC §4 SHOP-17; DESIGN C2 badge; TESTING §8 E2E-MAINT-2

## Scope
In: The for: task badge on a list row whose source is maintenance.
Out: Sending the lines (X3, MAINT-16). Skipped layers: -db, -api — the label is client UI on rows X3-api inserts.

## Acceptance criteria
- [ ] AC1 Given a list item has source maintenance and a task titled Boiler service, when I view the list, then the row shows for: Boiler service.
- [ ] AC2 Given the task was renamed, when the list refreshes, then the badge shows the new title.
- [ ] AC3 Given the item source is manual, when I view the row, then there is no for: badge.
- [ ] AC4 Given the device is offline, when I view the row, then the badge still renders from local data.
- [ ] AC5 Given maintenance is off and shopping is on, when I view an existing maintenance-sourced row, then the badge remains, because the row is a shopping row.

## Design
Screen: DESIGN C2 — States: populated, offline. Interactions: the task badge is read-only on the row.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Shopping: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Shopping off: the list is hidden (X7). The §9 maintenance-only row hides Send to list; it does not delete shopping rows already created.

## Data and contract
Tables: list_item, maint_task  Endpoints: none  Types: ListItem, MaintTask
Schema change: no  Contract change: no

## Tests required
Unit: web/src/shopping/TaskBadge.test.ts → AC1 the badge text is for: plus the task title; AC3 manual items have no badge
Component: web/src/shopping/TaskBadge.test.tsx → AC2 title refresh, AC4 offline, AC5 badge remains. No extra interaction beyond rendering.
Integration: n/a (no API handler in this story)
E2E: E2E-MAINT-2 web/e2e/TaskBadge.spec.ts
Offline test: yes, TESTING §6 — web/e2e/SHOP-17-badge.offline.spec.ts

## Dependencies
Blocked by: X3-api, SHOP-01-web  Blocks: none

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
§9 does not say what the badge does if maintenance is later switched off. Proposed default: the shopping row and its badge stay, and Send to list is what disappears.

# SHOP-17-ios — Maintenance consumables on the list

## Summary
A line that was sent from a maintenance task says which job it is for.

## Spec references
SPEC §4 SHOP-17; DESIGN C2 badge; TESTING §8 E2E-MAINT-2

## Scope
In: The for: task badge on a list row whose source is maintenance.
Out: Sending the lines (X3, MAINT-16). Skipped layers: -db, -api — the label is client UI on rows X3-api inserts.

## Acceptance criteria
- [ ] AC1 Given a list item has source maintenance and a task titled Boiler service, when I view the list, then the row shows for: Boiler service.
- [ ] AC2 Given the task was renamed, when the list refreshes, then the badge shows the new title.
- [ ] AC3 Given the item source is manual, when I view the row, then there is no for: badge.
- [ ] AC4 Given the device is offline, when I view the row, then the badge still renders from local data.
- [ ] AC5 Given maintenance is off and shopping is on, when I view an existing maintenance-sourced row, then the badge remains, because the row is a shopping row.

## Design
Screen: DESIGN C2 — States: populated, offline. Interactions: the task badge is read-only on the row.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Shopping: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Shopping off: the list is hidden (X7). The §9 maintenance-only row hides Send to list; it does not delete shopping rows already created.

## Data and contract
Tables: list_item, maint_task  Endpoints: none  Types: ListItem, MaintTask
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/shopping/Tests/TaskBadgeTests.swift → AC1 the badge text is for: plus the task title; AC3 manual items have no badge
Component: apps/ios/AbodeNestlyTests/TaskBadgeViewTests.swift → AC2 title refresh, AC4 offline, AC5 badge remains. No extra interaction beyond rendering.
Integration: n/a (no API handler in this story)
E2E: E2E-MAINT-2 apps/ios/AbodeNestlyUITests/TaskBadgeUITests.swift
Offline test: yes, TESTING §6 — URLProtocol throws, then kick(); TaskBadgeOfflineTests.swift covers the same steps as the web file

## Dependencies
Blocked by: X3-api, SHOP-01-ios  Blocks: none

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
§9 does not say what the badge does if maintenance is later switched off. Proposed default: the shopping row and its badge stay, and Send to list is what disappears.

# SHOP-17-android — Maintenance consumables on the list

## Summary
A line that was sent from a maintenance task says which job it is for.

## Spec references
SPEC §4 SHOP-17; DESIGN C2 badge; TESTING §8 E2E-MAINT-2

## Scope
In: The for: task badge on a list row whose source is maintenance.
Out: Sending the lines (X3, MAINT-16). Skipped layers: -db, -api — the label is client UI on rows X3-api inserts.

## Acceptance criteria
- [ ] AC1 Given a list item has source maintenance and a task titled Boiler service, when I view the list, then the row shows for: Boiler service.
- [ ] AC2 Given the task was renamed, when the list refreshes, then the badge shows the new title.
- [ ] AC3 Given the item source is manual, when I view the row, then there is no for: badge.
- [ ] AC4 Given the device is offline, when I view the row, then the badge still renders from local data.
- [ ] AC5 Given maintenance is off and shopping is on, when I view an existing maintenance-sourced row, then the badge remains, because the row is a shopping row.

## Design
Screen: DESIGN C2 — States: populated, offline. Interactions: the task badge is read-only on the row.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Shopping: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Shopping off: the list is hidden (X7). The §9 maintenance-only row hides Send to list; it does not delete shopping rows already created.

## Data and contract
Tables: list_item, maint_task  Endpoints: none  Types: ListItem, MaintTask
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/shopping/src/test/java/app/abodenestly/shopping/TaskBadgeTest.kt → AC1 the badge text is for: plus the task title; AC3 manual items have no badge
Component: apps/android/shopping/src/test/java/app/abodenestly/shopping/TaskBadgeComposeTest.kt → AC2 title refresh, AC4 offline, AC5 badge remains. No extra interaction beyond rendering.
Integration: n/a (no API handler in this story)
E2E: E2E-MAINT-2 apps/android/shopping/src/androidTest/java/app/abodenestly/shopping/TaskBadgeE2ETest.kt
Offline test: yes, TESTING §6 — Fake ApiClient throws IOException, then kick(); TaskBadgeOfflineTest.kt

## Dependencies
Blocked by: X3-api, SHOP-01-android  Blocks: none

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
§9 does not say what the badge does if maintenance is later switched off. Proposed default: the shopping row and its badge stay, and Send to list is what disappears.
