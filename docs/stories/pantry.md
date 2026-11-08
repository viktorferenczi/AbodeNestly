# Pantry stories

One story per feature ID per layer that has work. Each body is `docs/STORY-TEMPLATE.md` verbatim. Order inside an ID is db, api, web, ios, android.

# PANT-01-db — Pantry locations

## Summary
The household can keep food in the fridge, the freezer, the pantry, or a place they add.

## Spec references
SPEC §4 PANT-01; §8 Location; TESTING §3 -db row

## Scope
In: location rows with a kind of fridge, freezer, pantry, or other. Members can write them.
Out: The location list (PANT-01 clients). The seed rows (CORE-02-db). Skipped layers: -api — locations are outbox CRUD. The three defaults are seeded by CORE-02-db.

## Acceptance criteria
- [ ] AC1 Given a member, when they insert a location of kind other, then the row is stored.
- [ ] AC2 Given a kind outside fridge, freezer, pantry, and other, when they insert, then the row is rejected.
- [ ] AC3 Given a non-member, when they select the location, then they see nothing.

## Design
Screen: n/a — this layer has no DESIGN screen.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Pantry: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Pantry off (X7): the Pantry tab is hidden and a pantry-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: location  Endpoints: none  Types: Location
Schema change: yes — supabase/migrations/20261108120000_PANT-01_location.sql  Contract change: no

## Tests required
Unit: n/a (database behaviour is asserted in pgTAP, TESTING §2)
Component: n/a (no UI)
Integration: supabase/tests/PANT-01_location.sql → AC1 custom location; AC2 bad kind rejected; AC3 non-member denied
E2E: n/a (covered by the client journeys for this ID)
Offline test: n/a (server-side)

## Dependencies
Blocked by: CORE-02-db  Blocks: PANT-01-web, PANT-01-ios, PANT-01-android, PANT-02-db

## Skill
.claude/skills/sql-supabase/SKILL.md

## Open questions
none

# PANT-01-web — Locations

## Summary
A person can add a place to keep food, and can see the fridge, freezer, and pantry that were already created.

## Spec references
SPEC §4 PANT-01; DESIGN D1, D4; TESTING §3 client rows

## Scope
In: The location segment on Pantry (D1) and the locations list on pantry settings (D4).
Out: Items inside a location (PANT-02). Categories and conversions on the same settings screen (PANT-08, PANT-13). Skipped layers: -api — locations are outbox CRUD. The three defaults are seeded by CORE-02-db.

## Acceptance criteria
- [ ] AC1 Given only the defaults exist, when I open pantry settings, then I see the defaults-only state with Fridge, Freezer, and Pantry.
- [ ] AC2 Given I add a location, when I save, then the custom state shows it and the segment control includes it.
- [ ] AC3 Given I rename a location, when I save, then the new name is on the segment and on the items that use it.
- [ ] AC4 Given the device is offline, when I add a location, then it appears and an outbox row exists.

## Design
Screen: DESIGN D4 — States: defaults-only, custom. Interactions: add a location; D1's segmented control filters by it.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Pantry: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Pantry off (X7): the Pantry tab is hidden and a pantry-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: location  Endpoints: none  Types: Location
Schema change: no  Contract change: no

## Tests required
Unit: web/src/pantry/Locations.test.ts → AC4 offline add enqueues one location upsert
Component: web/src/pantry/Locations.test.tsx → AC1 defaults-only, AC2 custom; add and rename cover AC3. D1 segments are part of the same component test.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: yes, TESTING §6 — web/e2e/PANT-offline.spec.ts

## Dependencies
Blocked by: PANT-01-db, CORE-02-web, SHOP-08-web  Blocks: PANT-02-web, PANT-06-web

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
none

# PANT-01-ios — Locations

## Summary
A person can add a place to keep food, and can see the fridge, freezer, and pantry that were already created.

## Spec references
SPEC §4 PANT-01; DESIGN D1, D4; TESTING §3 client rows

## Scope
In: The location segment on Pantry (D1) and the locations list on pantry settings (D4).
Out: Items inside a location (PANT-02). Categories and conversions on the same settings screen (PANT-08, PANT-13). Skipped layers: -api — locations are outbox CRUD. The three defaults are seeded by CORE-02-db.

## Acceptance criteria
- [ ] AC1 Given only the defaults exist, when I open pantry settings, then I see the defaults-only state with Fridge, Freezer, and Pantry.
- [ ] AC2 Given I add a location, when I save, then the custom state shows it and the segment control includes it.
- [ ] AC3 Given I rename a location, when I save, then the new name is on the segment and on the items that use it.
- [ ] AC4 Given the device is offline, when I add a location, then it appears and an outbox row exists.

## Design
Screen: DESIGN D4 — States: defaults-only, custom. Interactions: add a location; D1's segmented control filters by it.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Pantry: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Pantry off (X7): the Pantry tab is hidden and a pantry-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: location  Endpoints: none  Types: Location
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/pantry/Tests/LocationsTests.swift → AC4 offline add enqueues one location upsert
Component: apps/ios/AbodeNestlyTests/LocationsViewTests.swift → AC1 defaults-only, AC2 custom; add and rename cover AC3. D1 segments are part of the same component test.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: yes, TESTING §6 — URLProtocol throws, then kick(); LocationsOfflineTests.swift covers the same steps as the web file

## Dependencies
Blocked by: PANT-01-db, CORE-02-ios, SHOP-08-ios  Blocks: PANT-02-ios, PANT-06-ios

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
none

# PANT-01-android — Locations

## Summary
A person can add a place to keep food, and can see the fridge, freezer, and pantry that were already created.

## Spec references
SPEC §4 PANT-01; DESIGN D1, D4; TESTING §3 client rows

## Scope
In: The location segment on Pantry (D1) and the locations list on pantry settings (D4).
Out: Items inside a location (PANT-02). Categories and conversions on the same settings screen (PANT-08, PANT-13). Skipped layers: -api — locations are outbox CRUD. The three defaults are seeded by CORE-02-db.

## Acceptance criteria
- [ ] AC1 Given only the defaults exist, when I open pantry settings, then I see the defaults-only state with Fridge, Freezer, and Pantry.
- [ ] AC2 Given I add a location, when I save, then the custom state shows it and the segment control includes it.
- [ ] AC3 Given I rename a location, when I save, then the new name is on the segment and on the items that use it.
- [ ] AC4 Given the device is offline, when I add a location, then it appears and an outbox row exists.

## Design
Screen: DESIGN D4 — States: defaults-only, custom. Interactions: add a location; D1's segmented control filters by it.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Pantry: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Pantry off (X7): the Pantry tab is hidden and a pantry-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: location  Endpoints: none  Types: Location
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/pantry/src/test/java/app/abodenestly/pantry/LocationsTest.kt → AC4 offline add enqueues one location upsert
Component: apps/android/pantry/src/test/java/app/abodenestly/pantry/LocationsComposeTest.kt → AC1 defaults-only, AC2 custom; add and rename cover AC3. D1 segments are part of the same component test.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: yes, TESTING §6 — Fake ApiClient throws IOException, then kick(); LocationsOfflineTest.kt

## Dependencies
Blocked by: PANT-01-db, CORE-02-android, SHOP-08-android  Blocks: PANT-02-android, PANT-06-android

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
none

# PANT-02-db — Pantry items

## Summary
The household can store what it has, where it is, and when it goes off, without clients writing the quantity column.

## Spec references
SPEC §4 PANT-02; §5 quantity only via movements; §8 PantryItem; TESTING §3 -db row

## Scope
In: pantry_item columns except the privilege to update quantity. A member update of quantity is rejected.
Out: The item sheet (PANT-02 clients). Movements (X5, PANT-03). Skipped layers: -api — item fields sync through the outbox. Quantity is not written on this row; that is X5 and PANT-03.

## Acceptance criteria
- [ ] AC1 Given a member, when they insert an item with name, unit, location, category, added date, expiry, and note, then the row is stored and quantity stays at its default.
- [ ] AC2 Given a member, when they update quantity directly, then the update is rejected.
- [ ] AC3 Given a non-member, when they select the item, then they see nothing.

## Design
Screen: n/a — this layer has no DESIGN screen.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Pantry: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Pantry off (X7): the Pantry tab is hidden and a pantry-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: pantry_item  Endpoints: none  Types: PantryItem
Schema change: yes — supabase/migrations/20261108120100_PANT-02_pantry_item.sql  Contract change: no

## Tests required
Unit: n/a (database behaviour is asserted in pgTAP, TESTING §2)
Component: n/a (no UI)
Integration: supabase/tests/PANT-02_item.sql → AC1 insert leaves quantity default; AC2 direct quantity update rejected; AC3 non-member denied
E2E: n/a (covered by the client journeys for this ID)
Offline test: n/a (server-side)

## Dependencies
Blocked by: PANT-01-db  Blocks: PANT-02-web, PANT-02-ios, PANT-02-android, PANT-12-db, PANT-14-db, PANT-16-db, X5-db

## Skill
.claude/skills/sql-supabase/SKILL.md

## Open questions
§4 lists quantity inside item CRUD and §5 forbids writing it. Proposed default: the insert trigger or a column privilege keeps the default quantity, and the starting amount is an added movement from PANT-03.

# PANT-02-web — Pantry items

## Summary
A person can record what the household has, where it sits, and when it expires.

## Spec references
SPEC §4 PANT-02; DESIGN D1, D2; TESTING §3 client rows

## Scope
In: Create and edit on the item sheet (D2) for name, unit, location, category, added date, expiry, and note, and the row on D1.
Out: The stepper (PANT-03). Threshold (PANT-04). Photo (PANT-10). History (PANT-07). Skipped layers: -api — item fields sync through the outbox. Quantity is not written on this row; that is X5 and PANT-03.

## Acceptance criteria
- [ ] AC1 Given I add an item with a name and a location, when I save, then it appears in that location's group.
- [ ] AC2 Given I set an expiry, when I save, then the row's caption shows that date.
- [ ] AC3 Given I delete the item, when I confirm through the undo toast, then a discarded movement is queued and the item is soft-deleted.
- [ ] AC4 Given the device is offline, when I save an edit, then the outbox has the upsert and the row updates locally.
- [ ] AC5 Given I try to type a quantity into a raw field, when I look at the sheet, then the only quantity control is the stepper from PANT-03.

## Design
Screen: DESIGN D2 — States: new, edit, expired. Interactions: save; delete with undo. The low state is PANT-04..
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Pantry: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Pantry off (X7): the Pantry tab is hidden and a pantry-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: pantry_item  Endpoints: none  Types: PantryItem
Schema change: no  Contract change: no

## Tests required
Unit: web/src/pantry/ItemSheet.test.ts → AC5 the save payload does not contain quantity; AC4 offline enqueues one upsert
Component: web/src/pantry/ItemSheet.test.tsx → AC1 new, AC2 edit with expiry, AC3 delete undo. States new, edit, expired.
Integration: n/a (no API handler in this story)
E2E: E2E-PANT-1 web/e2e/ItemSheet.spec.ts
Offline test: yes, TESTING §6 — web/e2e/PANT-offline.spec.ts

## Dependencies
Blocked by: PANT-02-db, PANT-01-web  Blocks: PANT-03-web, PANT-04-web, PANT-05-web, PANT-06-web, PANT-08-web, PANT-09-web, PANT-10-web, PANT-12-web, PANT-13-web

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
none

# PANT-02-ios — Pantry items

## Summary
A person can record what the household has, where it sits, and when it expires.

## Spec references
SPEC §4 PANT-02; DESIGN D1, D2; TESTING §3 client rows

## Scope
In: Create and edit on the item sheet (D2) for name, unit, location, category, added date, expiry, and note, and the row on D1.
Out: The stepper (PANT-03). Threshold (PANT-04). Photo (PANT-10). History (PANT-07). Skipped layers: -api — item fields sync through the outbox. Quantity is not written on this row; that is X5 and PANT-03.

## Acceptance criteria
- [ ] AC1 Given I add an item with a name and a location, when I save, then it appears in that location's group.
- [ ] AC2 Given I set an expiry, when I save, then the row's caption shows that date.
- [ ] AC3 Given I delete the item, when I confirm through the undo toast, then a discarded movement is queued and the item is soft-deleted.
- [ ] AC4 Given the device is offline, when I save an edit, then the outbox has the upsert and the row updates locally.
- [ ] AC5 Given I try to type a quantity into a raw field, when I look at the sheet, then the only quantity control is the stepper from PANT-03.

## Design
Screen: DESIGN D2 — States: new, edit, expired. Interactions: save; delete with undo. The low state is PANT-04..
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Pantry: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Pantry off (X7): the Pantry tab is hidden and a pantry-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: pantry_item  Endpoints: none  Types: PantryItem
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/pantry/Tests/ItemSheetTests.swift → AC5 the save payload does not contain quantity; AC4 offline enqueues one upsert
Component: apps/ios/AbodeNestlyTests/ItemSheetViewTests.swift → AC1 new, AC2 edit with expiry, AC3 delete undo. States new, edit, expired.
Integration: n/a (no API handler in this story)
E2E: E2E-PANT-1 apps/ios/AbodeNestlyUITests/ItemSheetUITests.swift
Offline test: yes, TESTING §6 — URLProtocol throws, then kick(); ItemSheetOfflineTests.swift covers the same steps as the web file

## Dependencies
Blocked by: PANT-02-db, PANT-01-ios  Blocks: PANT-03-ios, PANT-04-ios, PANT-05-ios, PANT-06-ios, PANT-08-ios, PANT-09-ios, PANT-10-ios, PANT-12-ios, PANT-13-ios

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
none

# PANT-02-android — Pantry items

## Summary
A person can record what the household has, where it sits, and when it expires.

## Spec references
SPEC §4 PANT-02; DESIGN D1, D2; TESTING §3 client rows

## Scope
In: Create and edit on the item sheet (D2) for name, unit, location, category, added date, expiry, and note, and the row on D1.
Out: The stepper (PANT-03). Threshold (PANT-04). Photo (PANT-10). History (PANT-07). Skipped layers: -api — item fields sync through the outbox. Quantity is not written on this row; that is X5 and PANT-03.

## Acceptance criteria
- [ ] AC1 Given I add an item with a name and a location, when I save, then it appears in that location's group.
- [ ] AC2 Given I set an expiry, when I save, then the row's caption shows that date.
- [ ] AC3 Given I delete the item, when I confirm through the undo toast, then a discarded movement is queued and the item is soft-deleted.
- [ ] AC4 Given the device is offline, when I save an edit, then the outbox has the upsert and the row updates locally.
- [ ] AC5 Given I try to type a quantity into a raw field, when I look at the sheet, then the only quantity control is the stepper from PANT-03.

## Design
Screen: DESIGN D2 — States: new, edit, expired. Interactions: save; delete with undo. The low state is PANT-04..
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Pantry: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Pantry off (X7): the Pantry tab is hidden and a pantry-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: pantry_item  Endpoints: none  Types: PantryItem
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/pantry/src/test/java/app/abodenestly/pantry/ItemSheetTest.kt → AC5 the save payload does not contain quantity; AC4 offline enqueues one upsert
Component: apps/android/pantry/src/test/java/app/abodenestly/pantry/ItemSheetComposeTest.kt → AC1 new, AC2 edit with expiry, AC3 delete undo. States new, edit, expired.
Integration: n/a (no API handler in this story)
E2E: E2E-PANT-1 apps/android/pantry/src/androidTest/java/app/abodenestly/pantry/ItemSheetE2ETest.kt
Offline test: yes, TESTING §6 — Fake ApiClient throws IOException, then kick(); ItemSheetOfflineTest.kt

## Dependencies
Blocked by: PANT-02-db, PANT-01-android  Blocks: PANT-03-android, PANT-04-android, PANT-05-android, PANT-06-android, PANT-08-android, PANT-09-android, PANT-10-android, PANT-12-android, PANT-13-android

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
none

# PANT-03-api — Accept a quantity delta

## Summary
Two people can change the same jar while offline and both changes count.

## Spec references
SPEC §4 PANT-03; §6.2 delta; §9 X5; TESTING §3 -api row

## Scope
In: POST /sync accepts a delta mutation on pantry_item and inserts a pantry_movement instead of writing quantity.
Out: The trigger that applies the movement (X5-db). The stepper (PANT-03 clients). The rest of sync (SHOP-08-api). Skipped layers: -db — applying the delta is X5-db. This story is the sync delta op and the stepper.

## Acceptance criteria
- [ ] AC1 Given a delta of -1 with reason used, when it is pushed, then a pantry_movement is inserted and the response lists the mutation as applied.
- [ ] AC2 Given a delta whose table is not pantry_item, when it is pushed, then it is rejected with validation.
- [ ] AC3 Given no JWT, when the push runs, then the response is 401.
- [ ] AC4 Given the same delta id is pushed twice, when the second arrives, then only one movement exists.

## Design
Screen: n/a — this layer has no DESIGN screen.
Deviations from the plan: none

## Offline behaviour
The delta is how an offline pantry quantity change becomes true on the server (SPEC §6.5).

## Module-off behaviour
Pantry off: the mutation is rejected with module_disabled (X7) and no movement is inserted.

## Data and contract
Tables: pantry_movement, pantry_item  Endpoints: POST /sync  Types: MutationDelta, PantryMovement
Schema change: no  Contract change: yes

## Tests required
Unit: api/test/PANT-03-delta.test.ts → AC2 a delta on another table is validation
Component: n/a (no UI)
Integration: api/test/PANT-03-delta.int.test.ts → AC1 movement inserted; AC3 401; AC4 replay inserts one movement.
E2E: n/a (client journeys cover the user path)
Offline test: n/a (server-side; queued client writes are covered by the client offline test)

## Dependencies
Blocked by: SHOP-08-api, X5-db  Blocks: PANT-03-web, PANT-03-ios, PANT-03-android, PANT-11-api

## Skill
.claude/skills/node-backend/SKILL.md

## Open questions
none

# PANT-03-web — Quick plus and minus

## Summary
From the list, a person can add or remove one without opening the item.

## Spec references
SPEC §4 PANT-03; DESIGN D1; TESTING §3 client rows

## Scope
In: Swipe-right stepper on the pantry list. Minus writes reason used, plus writes reason added.
Out: The delta endpoint (PANT-03-api) and the apply trigger (X5). Skipped layers: -db — applying the delta is X5-db. This story is the sync delta op and the stepper.

## Acceptance criteria
- [ ] AC1 Given I swipe an item right, when the stepper shows, then I see minus, the quantity, and plus.
- [ ] AC2 Given I tap minus, when the tap is handled, then a movement with delta -1 and reason used is queued and the visible quantity drops by one.
- [ ] AC3 Given I tap plus, when the tap is handled, then a movement with delta 1 and reason added is queued.
- [ ] AC4 Given the device is offline, when I tap either button, then the local quantity changes and the outbox has the delta.

## Design
Screen: DESIGN D1 — States: populated, offline, pending-sync. Interactions: swipe right reveals the stepper; minus and plus write deltas.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Pantry: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Pantry off (X7): the Pantry tab is hidden and a pantry-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: pantry_item, pantry_movement  Endpoints: POST /sync  Types: MutationDelta
Schema change: no  Contract change: no

## Tests required
Unit: web/src/pantry/Stepper.test.ts → AC2 the queued delta is -1 used; AC3 plus is +1 added; AC4 no direct quantity field
Component: web/src/pantry/Stepper.test.tsx → AC1 stepper visible, AC4 offline. Interactions: swipe right, minus, plus.
Integration: n/a (no API handler in this story)
E2E: E2E-PANT-1 web/e2e/Stepper.spec.ts
Offline test: yes, TESTING §6 — web/e2e/PANT-offline.spec.ts

## Dependencies
Blocked by: PANT-03-api, PANT-02-web, SHOP-08-web  Blocks: PANT-07-web, PANT-15-web

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
none

# PANT-03-ios — Quick plus and minus

## Summary
From the list, a person can add or remove one without opening the item.

## Spec references
SPEC §4 PANT-03; DESIGN D1; TESTING §3 client rows

## Scope
In: Swipe-right stepper on the pantry list. Minus writes reason used, plus writes reason added.
Out: The delta endpoint (PANT-03-api) and the apply trigger (X5). Skipped layers: -db — applying the delta is X5-db. This story is the sync delta op and the stepper.

## Acceptance criteria
- [ ] AC1 Given I swipe an item right, when the stepper shows, then I see minus, the quantity, and plus.
- [ ] AC2 Given I tap minus, when the tap is handled, then a movement with delta -1 and reason used is queued and the visible quantity drops by one.
- [ ] AC3 Given I tap plus, when the tap is handled, then a movement with delta 1 and reason added is queued.
- [ ] AC4 Given the device is offline, when I tap either button, then the local quantity changes and the outbox has the delta.

## Design
Screen: DESIGN D1 — States: populated, offline, pending-sync. Interactions: swipe right reveals the stepper; minus and plus write deltas.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Pantry: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Pantry off (X7): the Pantry tab is hidden and a pantry-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: pantry_item, pantry_movement  Endpoints: POST /sync  Types: MutationDelta
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/pantry/Tests/StepperTests.swift → AC2 the queued delta is -1 used; AC3 plus is +1 added; AC4 no direct quantity field
Component: apps/ios/AbodeNestlyTests/StepperViewTests.swift → AC1 stepper visible, AC4 offline. Interactions: swipe right, minus, plus.
Integration: n/a (no API handler in this story)
E2E: E2E-PANT-1 apps/ios/AbodeNestlyUITests/StepperUITests.swift
Offline test: yes, TESTING §6 — URLProtocol throws, then kick(); StepperOfflineTests.swift covers the same steps as the web file

## Dependencies
Blocked by: PANT-03-api, PANT-02-ios, SHOP-08-ios  Blocks: PANT-07-ios, PANT-15-ios

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
none

# PANT-03-android — Quick plus and minus

## Summary
From the list, a person can add or remove one without opening the item.

## Spec references
SPEC §4 PANT-03; DESIGN D1; TESTING §3 client rows

## Scope
In: Swipe-right stepper on the pantry list. Minus writes reason used, plus writes reason added.
Out: The delta endpoint (PANT-03-api) and the apply trigger (X5). Skipped layers: -db — applying the delta is X5-db. This story is the sync delta op and the stepper.

## Acceptance criteria
- [ ] AC1 Given I swipe an item right, when the stepper shows, then I see minus, the quantity, and plus.
- [ ] AC2 Given I tap minus, when the tap is handled, then a movement with delta -1 and reason used is queued and the visible quantity drops by one.
- [ ] AC3 Given I tap plus, when the tap is handled, then a movement with delta 1 and reason added is queued.
- [ ] AC4 Given the device is offline, when I tap either button, then the local quantity changes and the outbox has the delta.

## Design
Screen: DESIGN D1 — States: populated, offline, pending-sync. Interactions: swipe right reveals the stepper; minus and plus write deltas.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Pantry: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Pantry off (X7): the Pantry tab is hidden and a pantry-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: pantry_item, pantry_movement  Endpoints: POST /sync  Types: MutationDelta
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/pantry/src/test/java/app/abodenestly/pantry/StepperTest.kt → AC2 the queued delta is -1 used; AC3 plus is +1 added; AC4 no direct quantity field
Component: apps/android/pantry/src/test/java/app/abodenestly/pantry/StepperComposeTest.kt → AC1 stepper visible, AC4 offline. Interactions: swipe right, minus, plus.
Integration: n/a (no API handler in this story)
E2E: E2E-PANT-1 apps/android/pantry/src/androidTest/java/app/abodenestly/pantry/StepperE2ETest.kt
Offline test: yes, TESTING §6 — Fake ApiClient throws IOException, then kick(); StepperOfflineTest.kt

## Dependencies
Blocked by: PANT-03-api, PANT-02-android, SHOP-08-android  Blocks: PANT-07-android, PANT-15-android

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
none

# PANT-04-web — Low-stock threshold and auto-add

## Summary
Each item can say how low is too low, and whether that should feed the shopping list.

## Spec references
SPEC §4 PANT-04; DESIGN D2; TESTING §3 client rows

## Scope
In: The threshold stepper and the Add to shopping list automatically switch on the item sheet.
Out: The insert into the shopping list (X1, which is PANT-14). Skipped layers: -db, -api — low_threshold and auto_add are columns on pantry_item from PANT-02-db. The trigger that reads them is X1.

## Acceptance criteria
- [ ] AC1 Given I set a threshold of 1, when I save, then low_threshold is 1 on the item.
- [ ] AC2 Given I turn the auto-add switch off, when I save, then auto_add is false.
- [ ] AC3 Given shopping is off, when I open the sheet, then the switch is hidden and the threshold remains.
- [ ] AC4 Given the device is offline, when I change the threshold, then the change is queued.

## Design
Screen: DESIGN D2 — States: edit, low. Interactions: threshold stepper; auto-add switch, hidden if shopping is off.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Pantry: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Pantry off (X7): the Pantry tab is hidden and a pantry-table mutation is rejected with module_disabled. This screen is not reachable and must not error. With shopping off the switch is hidden (DESIGN D2) and X1 will not insert a list line.

## Data and contract
Tables: pantry_item  Endpoints: none  Types: PantryItem
Schema change: no  Contract change: no

## Tests required
Unit: web/src/pantry/LowStockFields.test.ts → AC1 and AC2 the upsert carries the threshold and auto_add; AC3 shopping off omits the switch
Component: web/src/pantry/LowStockFields.test.tsx → AC4 offline. States edit and low. Interactions: stepper and switch.
Integration: n/a (no API handler in this story)
E2E: E2E-PANT-1 web/e2e/LowStockFields.spec.ts
Offline test: yes, TESTING §6 — web/e2e/PANT-offline.spec.ts

## Dependencies
Blocked by: PANT-02-web  Blocks: none

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
none

# PANT-04-ios — Low-stock threshold and auto-add

## Summary
Each item can say how low is too low, and whether that should feed the shopping list.

## Spec references
SPEC §4 PANT-04; DESIGN D2; TESTING §3 client rows

## Scope
In: The threshold stepper and the Add to shopping list automatically switch on the item sheet.
Out: The insert into the shopping list (X1, which is PANT-14). Skipped layers: -db, -api — low_threshold and auto_add are columns on pantry_item from PANT-02-db. The trigger that reads them is X1.

## Acceptance criteria
- [ ] AC1 Given I set a threshold of 1, when I save, then low_threshold is 1 on the item.
- [ ] AC2 Given I turn the auto-add switch off, when I save, then auto_add is false.
- [ ] AC3 Given shopping is off, when I open the sheet, then the switch is hidden and the threshold remains.
- [ ] AC4 Given the device is offline, when I change the threshold, then the change is queued.

## Design
Screen: DESIGN D2 — States: edit, low. Interactions: threshold stepper; auto-add switch, hidden if shopping is off.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Pantry: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Pantry off (X7): the Pantry tab is hidden and a pantry-table mutation is rejected with module_disabled. This screen is not reachable and must not error. With shopping off the switch is hidden (DESIGN D2) and X1 will not insert a list line.

## Data and contract
Tables: pantry_item  Endpoints: none  Types: PantryItem
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/pantry/Tests/LowStockFieldsTests.swift → AC1 and AC2 the upsert carries the threshold and auto_add; AC3 shopping off omits the switch
Component: apps/ios/AbodeNestlyTests/LowStockFieldsViewTests.swift → AC4 offline. States edit and low. Interactions: stepper and switch.
Integration: n/a (no API handler in this story)
E2E: E2E-PANT-1 apps/ios/AbodeNestlyUITests/LowStockFieldsUITests.swift
Offline test: yes, TESTING §6 — URLProtocol throws, then kick(); LowStockFieldsOfflineTests.swift covers the same steps as the web file

## Dependencies
Blocked by: PANT-02-ios  Blocks: none

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
none

# PANT-04-android — Low-stock threshold and auto-add

## Summary
Each item can say how low is too low, and whether that should feed the shopping list.

## Spec references
SPEC §4 PANT-04; DESIGN D2; TESTING §3 client rows

## Scope
In: The threshold stepper and the Add to shopping list automatically switch on the item sheet.
Out: The insert into the shopping list (X1, which is PANT-14). Skipped layers: -db, -api — low_threshold and auto_add are columns on pantry_item from PANT-02-db. The trigger that reads them is X1.

## Acceptance criteria
- [ ] AC1 Given I set a threshold of 1, when I save, then low_threshold is 1 on the item.
- [ ] AC2 Given I turn the auto-add switch off, when I save, then auto_add is false.
- [ ] AC3 Given shopping is off, when I open the sheet, then the switch is hidden and the threshold remains.
- [ ] AC4 Given the device is offline, when I change the threshold, then the change is queued.

## Design
Screen: DESIGN D2 — States: edit, low. Interactions: threshold stepper; auto-add switch, hidden if shopping is off.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Pantry: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Pantry off (X7): the Pantry tab is hidden and a pantry-table mutation is rejected with module_disabled. This screen is not reachable and must not error. With shopping off the switch is hidden (DESIGN D2) and X1 will not insert a list line.

## Data and contract
Tables: pantry_item  Endpoints: none  Types: PantryItem
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/pantry/src/test/java/app/abodenestly/pantry/LowStockFieldsTest.kt → AC1 and AC2 the upsert carries the threshold and auto_add; AC3 shopping off omits the switch
Component: apps/android/pantry/src/test/java/app/abodenestly/pantry/LowStockFieldsComposeTest.kt → AC4 offline. States edit and low. Interactions: stepper and switch.
Integration: n/a (no API handler in this story)
E2E: E2E-PANT-1 apps/android/pantry/src/androidTest/java/app/abodenestly/pantry/LowStockFieldsE2ETest.kt
Offline test: yes, TESTING §6 — Fake ApiClient throws IOException, then kick(); LowStockFieldsOfflineTest.kt

## Dependencies
Blocked by: PANT-02-android  Blocks: none

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
none

# PANT-05-web — Eat soon

## Summary
The household can see the food that expires within the next seven days, with the soonest first.

## Spec references
SPEC §4 PANT-05; DESIGN D3; TESTING §8 E2E-PANT-3

## Scope
In: Eat soon (D3), grouped Today, Tomorrow, and This week, with Used and Discard.
Out: The push that uses 3 days (PANT-16). The item sheet (PANT-02). Skipped layers: -db, -api — the view is a local query. The 3-day push is PANT-16, not this 7-day screen.

## Acceptance criteria
- [ ] AC1 Given an item expires tomorrow, when I open Eat soon, then it is in the Tomorrow group.
- [ ] AC2 Given an item expires in 8 days, when I open Eat soon, then it is not listed.
- [ ] AC3 Given I tap Used, when the tap is handled, then a used movement is queued.
- [ ] AC4 Given I tap Discard, when the tap is handled, then a discarded movement is queued.
- [ ] AC5 Given nothing expires this week, when I open the screen, then I see Nothing expiring this week.
- [ ] AC6 Given the device is offline, when I open the screen, then the groups still render.

## Design
Screen: DESIGN D3 — States: empty, populated. Interactions: Used and Discard.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Pantry: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Pantry off (X7): the Pantry tab is hidden and a pantry-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: pantry_item, pantry_movement  Endpoints: none  Types: PantryItem
Schema change: no  Contract change: no

## Tests required
Unit: web/src/pantry/EatSoon.test.ts → AC1 tomorrow is grouped under Tomorrow; AC2 day 8 is excluded
Component: web/src/pantry/EatSoon.test.tsx → AC3 Used, AC4 Discard, AC5 empty, AC6 offline
Integration: n/a (no API handler in this story)
E2E: E2E-PANT-3 web/e2e/EatSoon.spec.ts
Offline test: yes, TESTING §6 — web/e2e/PANT-offline.spec.ts

## Dependencies
Blocked by: PANT-02-web  Blocks: none

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
none

# PANT-05-ios — Eat soon

## Summary
The household can see the food that expires within the next seven days, with the soonest first.

## Spec references
SPEC §4 PANT-05; DESIGN D3; TESTING §8 E2E-PANT-3

## Scope
In: Eat soon (D3), grouped Today, Tomorrow, and This week, with Used and Discard.
Out: The push that uses 3 days (PANT-16). The item sheet (PANT-02). Skipped layers: -db, -api — the view is a local query. The 3-day push is PANT-16, not this 7-day screen.

## Acceptance criteria
- [ ] AC1 Given an item expires tomorrow, when I open Eat soon, then it is in the Tomorrow group.
- [ ] AC2 Given an item expires in 8 days, when I open Eat soon, then it is not listed.
- [ ] AC3 Given I tap Used, when the tap is handled, then a used movement is queued.
- [ ] AC4 Given I tap Discard, when the tap is handled, then a discarded movement is queued.
- [ ] AC5 Given nothing expires this week, when I open the screen, then I see Nothing expiring this week.
- [ ] AC6 Given the device is offline, when I open the screen, then the groups still render.

## Design
Screen: DESIGN D3 — States: empty, populated. Interactions: Used and Discard.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Pantry: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Pantry off (X7): the Pantry tab is hidden and a pantry-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: pantry_item, pantry_movement  Endpoints: none  Types: PantryItem
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/pantry/Tests/EatSoonTests.swift → AC1 tomorrow is grouped under Tomorrow; AC2 day 8 is excluded
Component: apps/ios/AbodeNestlyTests/EatSoonViewTests.swift → AC3 Used, AC4 Discard, AC5 empty, AC6 offline
Integration: n/a (no API handler in this story)
E2E: E2E-PANT-3 apps/ios/AbodeNestlyUITests/EatSoonUITests.swift
Offline test: yes, TESTING §6 — URLProtocol throws, then kick(); EatSoonOfflineTests.swift covers the same steps as the web file

## Dependencies
Blocked by: PANT-02-ios  Blocks: none

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
none

# PANT-05-android — Eat soon

## Summary
The household can see the food that expires within the next seven days, with the soonest first.

## Spec references
SPEC §4 PANT-05; DESIGN D3; TESTING §8 E2E-PANT-3

## Scope
In: Eat soon (D3), grouped Today, Tomorrow, and This week, with Used and Discard.
Out: The push that uses 3 days (PANT-16). The item sheet (PANT-02). Skipped layers: -db, -api — the view is a local query. The 3-day push is PANT-16, not this 7-day screen.

## Acceptance criteria
- [ ] AC1 Given an item expires tomorrow, when I open Eat soon, then it is in the Tomorrow group.
- [ ] AC2 Given an item expires in 8 days, when I open Eat soon, then it is not listed.
- [ ] AC3 Given I tap Used, when the tap is handled, then a used movement is queued.
- [ ] AC4 Given I tap Discard, when the tap is handled, then a discarded movement is queued.
- [ ] AC5 Given nothing expires this week, when I open the screen, then I see Nothing expiring this week.
- [ ] AC6 Given the device is offline, when I open the screen, then the groups still render.

## Design
Screen: DESIGN D3 — States: empty, populated. Interactions: Used and Discard.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Pantry: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Pantry off (X7): the Pantry tab is hidden and a pantry-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: pantry_item, pantry_movement  Endpoints: none  Types: PantryItem
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/pantry/src/test/java/app/abodenestly/pantry/EatSoonTest.kt → AC1 tomorrow is grouped under Tomorrow; AC2 day 8 is excluded
Component: apps/android/pantry/src/test/java/app/abodenestly/pantry/EatSoonComposeTest.kt → AC3 Used, AC4 Discard, AC5 empty, AC6 offline
Integration: n/a (no API handler in this story)
E2E: E2E-PANT-3 apps/android/pantry/src/androidTest/java/app/abodenestly/pantry/EatSoonE2ETest.kt
Offline test: yes, TESTING §6 — Fake ApiClient throws IOException, then kick(); EatSoonOfflineTest.kt

## Dependencies
Blocked by: PANT-02-android  Blocks: none

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
none

# PANT-06-web — Search and filter

## Summary
A person can find an item by name and narrow it to a location or a category, on the device.

## Spec references
SPEC §4 PANT-06; DESIGN D1; TESTING §3 client rows

## Scope
In: The search field, the location segments, and the category grouping on the pantry overview.
Out: Editing items (PANT-02). Locations (PANT-01). Categories (PANT-08). Skipped layers: -db, -api — search and filters run on the local database. §4 says so.

## Acceptance criteria
- [ ] AC1 Given I type a name, when 150 ms pass, then only matching items remain.
- [ ] AC2 Given I pick Fridge, when the segment changes, then items in other locations are hidden.
- [ ] AC3 Given the filter matches nothing, when the list updates, then I see the filtered-empty state.
- [ ] AC4 Given the device is offline, when I search, then the filter still runs on local rows.

## Design
Screen: DESIGN D1 — States: populated, filtered-empty, offline. Interactions: search; location segment; items stay grouped by category.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Pantry: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08). SPEC §4 says this search is local.

## Module-off behaviour
Pantry off (X7): the Pantry tab is hidden and a pantry-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: pantry_item, location, category  Endpoints: none  Types: PantryItem
Schema change: no  Contract change: no

## Tests required
Unit: web/src/pantry/PantryFilter.test.ts → AC1 the debounce keeps only name matches; AC2 the location filter drops other locations
Component: web/src/pantry/PantryFilter.test.tsx → AC3 filtered-empty, AC4 offline. Interactions: search and segment.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: yes, TESTING §6 — web/e2e/PANT-offline.spec.ts

## Dependencies
Blocked by: PANT-02-web, PANT-01-web  Blocks: none

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
none

# PANT-06-ios — Search and filter

## Summary
A person can find an item by name and narrow it to a location or a category, on the device.

## Spec references
SPEC §4 PANT-06; DESIGN D1; TESTING §3 client rows

## Scope
In: The search field, the location segments, and the category grouping on the pantry overview.
Out: Editing items (PANT-02). Locations (PANT-01). Categories (PANT-08). Skipped layers: -db, -api — search and filters run on the local database. §4 says so.

## Acceptance criteria
- [ ] AC1 Given I type a name, when 150 ms pass, then only matching items remain.
- [ ] AC2 Given I pick Fridge, when the segment changes, then items in other locations are hidden.
- [ ] AC3 Given the filter matches nothing, when the list updates, then I see the filtered-empty state.
- [ ] AC4 Given the device is offline, when I search, then the filter still runs on local rows.

## Design
Screen: DESIGN D1 — States: populated, filtered-empty, offline. Interactions: search; location segment; items stay grouped by category.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Pantry: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08). SPEC §4 says this search is local.

## Module-off behaviour
Pantry off (X7): the Pantry tab is hidden and a pantry-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: pantry_item, location, category  Endpoints: none  Types: PantryItem
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/pantry/Tests/PantryFilterTests.swift → AC1 the debounce keeps only name matches; AC2 the location filter drops other locations
Component: apps/ios/AbodeNestlyTests/PantryFilterViewTests.swift → AC3 filtered-empty, AC4 offline. Interactions: search and segment.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: yes, TESTING §6 — URLProtocol throws, then kick(); PantryFilterOfflineTests.swift covers the same steps as the web file

## Dependencies
Blocked by: PANT-02-ios, PANT-01-ios  Blocks: none

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
none

# PANT-06-android — Search and filter

## Summary
A person can find an item by name and narrow it to a location or a category, on the device.

## Spec references
SPEC §4 PANT-06; DESIGN D1; TESTING §3 client rows

## Scope
In: The search field, the location segments, and the category grouping on the pantry overview.
Out: Editing items (PANT-02). Locations (PANT-01). Categories (PANT-08). Skipped layers: -db, -api — search and filters run on the local database. §4 says so.

## Acceptance criteria
- [ ] AC1 Given I type a name, when 150 ms pass, then only matching items remain.
- [ ] AC2 Given I pick Fridge, when the segment changes, then items in other locations are hidden.
- [ ] AC3 Given the filter matches nothing, when the list updates, then I see the filtered-empty state.
- [ ] AC4 Given the device is offline, when I search, then the filter still runs on local rows.

## Design
Screen: DESIGN D1 — States: populated, filtered-empty, offline. Interactions: search; location segment; items stay grouped by category.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Pantry: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08). SPEC §4 says this search is local.

## Module-off behaviour
Pantry off (X7): the Pantry tab is hidden and a pantry-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: pantry_item, location, category  Endpoints: none  Types: PantryItem
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/pantry/src/test/java/app/abodenestly/pantry/PantryFilterTest.kt → AC1 the debounce keeps only name matches; AC2 the location filter drops other locations
Component: apps/android/pantry/src/test/java/app/abodenestly/pantry/PantryFilterComposeTest.kt → AC3 filtered-empty, AC4 offline. Interactions: search and segment.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: yes, TESTING §6 — Fake ApiClient throws IOException, then kick(); PantryFilterOfflineTest.kt

## Dependencies
Blocked by: PANT-02-android, PANT-01-android  Blocks: none

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
none

# PANT-07-web — Usage history

## Summary
A person can open an item and see how its quantity has moved.

## Spec references
SPEC §4 PANT-07; DESIGN D2 History; TESTING §3 client rows

## Scope
In: The History section on the item sheet: date, delta, reason, and who.
Out: Writing movements (PANT-03, X2, X5). Skipped layers: -db, -api — history is the pantry_movement rows X5 already stores, shown on the item sheet.

## Acceptance criteria
- [ ] AC1 Given the item has movements, when I open it, then each row shows the date, the delta, the reason, and the member.
- [ ] AC2 Given there are no movements, when I open it, then the history section says there is no history yet.
- [ ] AC3 Given a movement was just queued, when I am still offline, then the history includes that local movement.
- [ ] AC4 Given I pull the newest page of history, when it renders, then the newest movement is first.

## Design
Screen: DESIGN D2 — States: edit. Interactions: read the history section.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Pantry: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Pantry off (X7): the Pantry tab is hidden and a pantry-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: pantry_movement, profile  Endpoints: none  Types: PantryMovement, Profile
Schema change: no  Contract change: no

## Tests required
Unit: web/src/pantry/History.test.ts → AC4 newest first; AC3 a queued movement is included
Component: web/src/pantry/History.test.tsx → AC1 rows show date, delta, reason, who; AC2 empty history. State edit.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: yes, TESTING §6 — web/e2e/PANT-offline.spec.ts

## Dependencies
Blocked by: PANT-03-web  Blocks: PANT-11-web

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
none

# PANT-07-ios — Usage history

## Summary
A person can open an item and see how its quantity has moved.

## Spec references
SPEC §4 PANT-07; DESIGN D2 History; TESTING §3 client rows

## Scope
In: The History section on the item sheet: date, delta, reason, and who.
Out: Writing movements (PANT-03, X2, X5). Skipped layers: -db, -api — history is the pantry_movement rows X5 already stores, shown on the item sheet.

## Acceptance criteria
- [ ] AC1 Given the item has movements, when I open it, then each row shows the date, the delta, the reason, and the member.
- [ ] AC2 Given there are no movements, when I open it, then the history section says there is no history yet.
- [ ] AC3 Given a movement was just queued, when I am still offline, then the history includes that local movement.
- [ ] AC4 Given I pull the newest page of history, when it renders, then the newest movement is first.

## Design
Screen: DESIGN D2 — States: edit. Interactions: read the history section.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Pantry: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Pantry off (X7): the Pantry tab is hidden and a pantry-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: pantry_movement, profile  Endpoints: none  Types: PantryMovement, Profile
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/pantry/Tests/HistoryTests.swift → AC4 newest first; AC3 a queued movement is included
Component: apps/ios/AbodeNestlyTests/HistoryViewTests.swift → AC1 rows show date, delta, reason, who; AC2 empty history. State edit.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: yes, TESTING §6 — URLProtocol throws, then kick(); HistoryOfflineTests.swift covers the same steps as the web file

## Dependencies
Blocked by: PANT-03-ios  Blocks: PANT-11-ios

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
none

# PANT-07-android — Usage history

## Summary
A person can open an item and see how its quantity has moved.

## Spec references
SPEC §4 PANT-07; DESIGN D2 History; TESTING §3 client rows

## Scope
In: The History section on the item sheet: date, delta, reason, and who.
Out: Writing movements (PANT-03, X2, X5). Skipped layers: -db, -api — history is the pantry_movement rows X5 already stores, shown on the item sheet.

## Acceptance criteria
- [ ] AC1 Given the item has movements, when I open it, then each row shows the date, the delta, the reason, and the member.
- [ ] AC2 Given there are no movements, when I open it, then the history section says there is no history yet.
- [ ] AC3 Given a movement was just queued, when I am still offline, then the history includes that local movement.
- [ ] AC4 Given I pull the newest page of history, when it renders, then the newest movement is first.

## Design
Screen: DESIGN D2 — States: edit. Interactions: read the history section.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Pantry: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Pantry off (X7): the Pantry tab is hidden and a pantry-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: pantry_movement, profile  Endpoints: none  Types: PantryMovement, Profile
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/pantry/src/test/java/app/abodenestly/pantry/HistoryTest.kt → AC4 newest first; AC3 a queued movement is included
Component: apps/android/pantry/src/test/java/app/abodenestly/pantry/HistoryComposeTest.kt → AC1 rows show date, delta, reason, who; AC2 empty history. State edit.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: yes, TESTING §6 — Fake ApiClient throws IOException, then kick(); HistoryOfflineTest.kt

## Dependencies
Blocked by: PANT-03-android  Blocks: PANT-11-android

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
none

# PANT-08-db — Categories and shelf life

## Summary
A category can carry a usual shelf life so adding food can suggest an expiry.

## Spec references
SPEC §4 PANT-08; §8 Category; TESTING §3 -db row

## Scope
In: category rows with name and default_shelf_days.
Out: The settings list and the suggest button (PANT-08 clients). Skipped layers: -api — categories sync through the outbox. Suggesting an expiry is client arithmetic from default_shelf_days.

## Acceptance criteria
- [ ] AC1 Given a member, when they insert a category with 7 shelf days, then the row is stored.
- [ ] AC2 Given shelf days is null, when they insert, then the row is stored and no expiry can be suggested from it.
- [ ] AC3 Given a non-member, when they select the category, then they see nothing.

## Design
Screen: n/a — this layer has no DESIGN screen.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Pantry: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Pantry off (X7): the Pantry tab is hidden and a pantry-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: category  Endpoints: none  Types: Category
Schema change: yes — supabase/migrations/20261108120200_PANT-08_category.sql  Contract change: no

## Tests required
Unit: n/a (database behaviour is asserted in pgTAP, TESTING §2)
Component: n/a (no UI)
Integration: supabase/tests/PANT-08_category.sql → AC1 shelf days stored; AC2 null allowed; AC3 non-member denied
E2E: n/a (covered by the client journeys for this ID)
Offline test: n/a (server-side)

## Dependencies
Blocked by: CORE-02-db  Blocks: PANT-08-web, PANT-08-ios, PANT-08-android

## Skill
.claude/skills/sql-supabase/SKILL.md

## Open questions
§12's seed is described as global categories, and Category is household-scoped. Proposed default: seed.sql's categories are copied into the household by create_household, next to the default locations.

# PANT-08-web — Categories and suggested expiry

## Summary
Picking a category while adding food fills in an expiry from that category's usual shelf life.

## Spec references
SPEC §4 PANT-08; DESIGN D2, D4; TESTING §3 client rows

## Scope
In: The categories list on D4 and the Suggest control on the item sheet.
Out: Locations on the same settings screen (PANT-01). Skipped layers: -api — categories sync through the outbox. Suggesting an expiry is client arithmetic from default_shelf_days.

## Acceptance criteria
- [ ] AC1 Given a category has 5 shelf days, when I tap Suggest on a new item, then the expiry becomes the added date plus 5 days.
- [ ] AC2 Given the category has no shelf life, when I tap Suggest, then the expiry is left unchanged.
- [ ] AC3 Given I add a category on the settings screen, when I save, then it appears in the item sheet's picker.
- [ ] AC4 Given the device is offline, when I add a category or tap Suggest, then both work from local data.

## Design
Screen: DESIGN D4 and D2 — States: D4 defaults-only and custom; D2 new and edit. Interactions: add a category; tap Suggest.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Pantry: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Pantry off (X7): the Pantry tab is hidden and a pantry-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: category, pantry_item  Endpoints: none  Types: Category, PantryItem
Schema change: no  Contract change: no

## Tests required
Unit: web/src/pantry/Categories.test.ts → AC1 expiry is added date plus shelf days; AC2 null shelf days does not change expiry
Component: web/src/pantry/Categories.test.tsx → AC3 the new category is in the picker; AC4 offline. States defaults-only, custom, new.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: yes, TESTING §6 — web/e2e/PANT-offline.spec.ts

## Dependencies
Blocked by: PANT-08-db, PANT-02-web  Blocks: none

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
none

# PANT-08-ios — Categories and suggested expiry

## Summary
Picking a category while adding food fills in an expiry from that category's usual shelf life.

## Spec references
SPEC §4 PANT-08; DESIGN D2, D4; TESTING §3 client rows

## Scope
In: The categories list on D4 and the Suggest control on the item sheet.
Out: Locations on the same settings screen (PANT-01). Skipped layers: -api — categories sync through the outbox. Suggesting an expiry is client arithmetic from default_shelf_days.

## Acceptance criteria
- [ ] AC1 Given a category has 5 shelf days, when I tap Suggest on a new item, then the expiry becomes the added date plus 5 days.
- [ ] AC2 Given the category has no shelf life, when I tap Suggest, then the expiry is left unchanged.
- [ ] AC3 Given I add a category on the settings screen, when I save, then it appears in the item sheet's picker.
- [ ] AC4 Given the device is offline, when I add a category or tap Suggest, then both work from local data.

## Design
Screen: DESIGN D4 and D2 — States: D4 defaults-only and custom; D2 new and edit. Interactions: add a category; tap Suggest.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Pantry: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Pantry off (X7): the Pantry tab is hidden and a pantry-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: category, pantry_item  Endpoints: none  Types: Category, PantryItem
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/pantry/Tests/CategoriesTests.swift → AC1 expiry is added date plus shelf days; AC2 null shelf days does not change expiry
Component: apps/ios/AbodeNestlyTests/CategoriesViewTests.swift → AC3 the new category is in the picker; AC4 offline. States defaults-only, custom, new.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: yes, TESTING §6 — URLProtocol throws, then kick(); CategoriesOfflineTests.swift covers the same steps as the web file

## Dependencies
Blocked by: PANT-08-db, PANT-02-ios  Blocks: none

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
none

# PANT-08-android — Categories and suggested expiry

## Summary
Picking a category while adding food fills in an expiry from that category's usual shelf life.

## Spec references
SPEC §4 PANT-08; DESIGN D2, D4; TESTING §3 client rows

## Scope
In: The categories list on D4 and the Suggest control on the item sheet.
Out: Locations on the same settings screen (PANT-01). Skipped layers: -api — categories sync through the outbox. Suggesting an expiry is client arithmetic from default_shelf_days.

## Acceptance criteria
- [ ] AC1 Given a category has 5 shelf days, when I tap Suggest on a new item, then the expiry becomes the added date plus 5 days.
- [ ] AC2 Given the category has no shelf life, when I tap Suggest, then the expiry is left unchanged.
- [ ] AC3 Given I add a category on the settings screen, when I save, then it appears in the item sheet's picker.
- [ ] AC4 Given the device is offline, when I add a category or tap Suggest, then both work from local data.

## Design
Screen: DESIGN D4 and D2 — States: D4 defaults-only and custom; D2 new and edit. Interactions: add a category; tap Suggest.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Pantry: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Pantry off (X7): the Pantry tab is hidden and a pantry-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: category, pantry_item  Endpoints: none  Types: Category, PantryItem
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/pantry/src/test/java/app/abodenestly/pantry/CategoriesTest.kt → AC1 expiry is added date plus shelf days; AC2 null shelf days does not change expiry
Component: apps/android/pantry/src/test/java/app/abodenestly/pantry/CategoriesComposeTest.kt → AC3 the new category is in the picker; AC4 offline. States defaults-only, custom, new.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: yes, TESTING §6 — Fake ApiClient throws IOException, then kick(); CategoriesOfflineTest.kt

## Dependencies
Blocked by: PANT-08-db, PANT-02-android  Blocks: none

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
none

# PANT-09-web — Barcode scan for a pantry item

## Summary
A person can scan a barcode and file that product in the pantry.

## Spec references
SPEC §4 PANT-09; DESIGN C7; TESTING §3 client rows

## Scope
In: The shared scanner (C7) adding a pantry item rather than a list item.
Out: The list-item use of the same scanner (SHOP-10). The product endpoint (SHOP-10-api). Skipped layers: -db, -api — the lookup is SHOP-10-api and the product cache is SHOP-10-db. This story is the scanner's pantry add.

## Acceptance criteria
- [ ] AC1 Given the lookup returns a product, when I tap Add on the pantry scanner, then a pantry item is created with that name.
- [ ] AC2 Given the lookup returns 404, when the card shows, then I can type a name and still add a pantry item.
- [ ] AC3 Given the device is offline, when I scan, then the pantry item is added with the barcode as its name and the lookup is queued.
- [ ] AC4 Given camera permission is denied, when I open the scanner, then I can enter a code manually.

## Design
Screen: DESIGN C7 — States: scanning, found, not-found, permission-denied, offline. Interactions: Add creates a pantry item in this story.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Pantry: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08). DESIGN C7 queues the lookup and still creates the item.

## Module-off behaviour
Pantry off (X7): the Pantry tab is hidden and a pantry-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: pantry_item, product  Endpoints: GET /products/{barcode}  Types: PantryItem, Product
Schema change: no  Contract change: no

## Tests required
Unit: web/src/pantry/PantryBarcode.test.ts → AC3 offline uses the barcode as the name
Component: web/src/pantry/PantryBarcode.test.tsx → AC1 found Add, AC2 not-found, AC4 permission-denied, scanning and offline
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: yes, TESTING §6 — web/e2e/PANT-offline.spec.ts

## Dependencies
Blocked by: SHOP-10-api, PANT-02-web  Blocks: none

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
none

# PANT-09-ios — Barcode scan for a pantry item

## Summary
A person can scan a barcode and file that product in the pantry.

## Spec references
SPEC §4 PANT-09; DESIGN C7; TESTING §3 client rows

## Scope
In: The shared scanner (C7) adding a pantry item rather than a list item.
Out: The list-item use of the same scanner (SHOP-10). The product endpoint (SHOP-10-api). Skipped layers: -db, -api — the lookup is SHOP-10-api and the product cache is SHOP-10-db. This story is the scanner's pantry add.

## Acceptance criteria
- [ ] AC1 Given the lookup returns a product, when I tap Add on the pantry scanner, then a pantry item is created with that name.
- [ ] AC2 Given the lookup returns 404, when the card shows, then I can type a name and still add a pantry item.
- [ ] AC3 Given the device is offline, when I scan, then the pantry item is added with the barcode as its name and the lookup is queued.
- [ ] AC4 Given camera permission is denied, when I open the scanner, then I can enter a code manually.

## Design
Screen: DESIGN C7 — States: scanning, found, not-found, permission-denied, offline. Interactions: Add creates a pantry item in this story.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Pantry: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08). DESIGN C7 queues the lookup and still creates the item.

## Module-off behaviour
Pantry off (X7): the Pantry tab is hidden and a pantry-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: pantry_item, product  Endpoints: GET /products/{barcode}  Types: PantryItem, Product
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/pantry/Tests/PantryBarcodeTests.swift → AC3 offline uses the barcode as the name
Component: apps/ios/AbodeNestlyTests/PantryBarcodeViewTests.swift → AC1 found Add, AC2 not-found, AC4 permission-denied, scanning and offline
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: yes, TESTING §6 — URLProtocol throws, then kick(); PantryBarcodeOfflineTests.swift covers the same steps as the web file

## Dependencies
Blocked by: SHOP-10-api, PANT-02-ios  Blocks: none

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
none

# PANT-09-android — Barcode scan for a pantry item

## Summary
A person can scan a barcode and file that product in the pantry.

## Spec references
SPEC §4 PANT-09; DESIGN C7; TESTING §3 client rows

## Scope
In: The shared scanner (C7) adding a pantry item rather than a list item.
Out: The list-item use of the same scanner (SHOP-10). The product endpoint (SHOP-10-api). Skipped layers: -db, -api — the lookup is SHOP-10-api and the product cache is SHOP-10-db. This story is the scanner's pantry add.

## Acceptance criteria
- [ ] AC1 Given the lookup returns a product, when I tap Add on the pantry scanner, then a pantry item is created with that name.
- [ ] AC2 Given the lookup returns 404, when the card shows, then I can type a name and still add a pantry item.
- [ ] AC3 Given the device is offline, when I scan, then the pantry item is added with the barcode as its name and the lookup is queued.
- [ ] AC4 Given camera permission is denied, when I open the scanner, then I can enter a code manually.

## Design
Screen: DESIGN C7 — States: scanning, found, not-found, permission-denied, offline. Interactions: Add creates a pantry item in this story.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Pantry: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08). DESIGN C7 queues the lookup and still creates the item.

## Module-off behaviour
Pantry off (X7): the Pantry tab is hidden and a pantry-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: pantry_item, product  Endpoints: GET /products/{barcode}  Types: PantryItem, Product
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/pantry/src/test/java/app/abodenestly/pantry/PantryBarcodeTest.kt → AC3 offline uses the barcode as the name
Component: apps/android/pantry/src/test/java/app/abodenestly/pantry/PantryBarcodeComposeTest.kt → AC1 found Add, AC2 not-found, AC4 permission-denied, scanning and offline
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: yes, TESTING §6 — Fake ApiClient throws IOException, then kick(); PantryBarcodeOfflineTest.kt

## Dependencies
Blocked by: SHOP-10-api, PANT-02-android  Blocks: none

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
none

# PANT-10-web — Photo of a pantry item

## Summary
A person can attach a photo of the packet to the pantry item.

## Spec references
SPEC §4 PANT-10; DESIGN D2; TESTING §3 client rows

## Scope
In: The photo on the item sheet, using the CORE-11 control.
Out: The shared uploader (CORE-11). The rest of the item (PANT-02). Skipped layers: -db, -api — the file uses CORE-11. This story is the photo control on the pantry item.

## Acceptance criteria
- [ ] AC1 Given I tap the photo, when I pick an image, then the CORE-11 control starts an upload for this pantry item.
- [ ] AC2 Given the upload finishes, when I reopen the item, then the photo is shown.
- [ ] AC3 Given the device is offline, when I pick a photo, then the upload is queued and the sheet does not error.
- [ ] AC4 Given I remove the photo, when I confirm, then the attachment is soft-deleted.

## Design
Screen: DESIGN D2 — States: new, edit. Interactions: tap the photo to add; the upload queues offline, as D2 states.
Deviations from the plan: none

## Offline behaviour
DESIGN D2: the photo upload is queued offline. SPEC §6.5's pantry write rule covers the item; the bytes follow the CORE-11 default of queueing until online.

## Module-off behaviour
Pantry off (X7): the Pantry tab is hidden and a pantry-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: attachment, pantry_item  Endpoints: POST /attachments/sign  Types: Attachment
Schema change: no  Contract change: no

## Tests required
Unit: web/src/pantry/ItemPhoto.test.ts → AC3 offline queues the upload and does not call sign
Component: web/src/pantry/ItemPhoto.test.tsx → AC1 uploading, AC2 photo shown, AC4 removed. States new and edit.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: yes, TESTING §6 — web/e2e/PANT-offline.spec.ts

## Dependencies
Blocked by: CORE-11-web, PANT-02-web  Blocks: none

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
none

# PANT-10-ios — Photo of a pantry item

## Summary
A person can attach a photo of the packet to the pantry item.

## Spec references
SPEC §4 PANT-10; DESIGN D2; TESTING §3 client rows

## Scope
In: The photo on the item sheet, using the CORE-11 control.
Out: The shared uploader (CORE-11). The rest of the item (PANT-02). Skipped layers: -db, -api — the file uses CORE-11. This story is the photo control on the pantry item.

## Acceptance criteria
- [ ] AC1 Given I tap the photo, when I pick an image, then the CORE-11 control starts an upload for this pantry item.
- [ ] AC2 Given the upload finishes, when I reopen the item, then the photo is shown.
- [ ] AC3 Given the device is offline, when I pick a photo, then the upload is queued and the sheet does not error.
- [ ] AC4 Given I remove the photo, when I confirm, then the attachment is soft-deleted.

## Design
Screen: DESIGN D2 — States: new, edit. Interactions: tap the photo to add; the upload queues offline, as D2 states.
Deviations from the plan: none

## Offline behaviour
DESIGN D2: the photo upload is queued offline. SPEC §6.5's pantry write rule covers the item; the bytes follow the CORE-11 default of queueing until online.

## Module-off behaviour
Pantry off (X7): the Pantry tab is hidden and a pantry-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: attachment, pantry_item  Endpoints: POST /attachments/sign  Types: Attachment
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/pantry/Tests/ItemPhotoTests.swift → AC3 offline queues the upload and does not call sign
Component: apps/ios/AbodeNestlyTests/ItemPhotoViewTests.swift → AC1 uploading, AC2 photo shown, AC4 removed. States new and edit.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: yes, TESTING §6 — URLProtocol throws, then kick(); ItemPhotoOfflineTests.swift covers the same steps as the web file

## Dependencies
Blocked by: CORE-11-ios, PANT-02-ios  Blocks: none

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
none

# PANT-10-android — Photo of a pantry item

## Summary
A person can attach a photo of the packet to the pantry item.

## Spec references
SPEC §4 PANT-10; DESIGN D2; TESTING §3 client rows

## Scope
In: The photo on the item sheet, using the CORE-11 control.
Out: The shared uploader (CORE-11). The rest of the item (PANT-02). Skipped layers: -db, -api — the file uses CORE-11. This story is the photo control on the pantry item.

## Acceptance criteria
- [ ] AC1 Given I tap the photo, when I pick an image, then the CORE-11 control starts an upload for this pantry item.
- [ ] AC2 Given the upload finishes, when I reopen the item, then the photo is shown.
- [ ] AC3 Given the device is offline, when I pick a photo, then the upload is queued and the sheet does not error.
- [ ] AC4 Given I remove the photo, when I confirm, then the attachment is soft-deleted.

## Design
Screen: DESIGN D2 — States: new, edit. Interactions: tap the photo to add; the upload queues offline, as D2 states.
Deviations from the plan: none

## Offline behaviour
DESIGN D2: the photo upload is queued offline. SPEC §6.5's pantry write rule covers the item; the bytes follow the CORE-11 default of queueing until online.

## Module-off behaviour
Pantry off (X7): the Pantry tab is hidden and a pantry-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: attachment, pantry_item  Endpoints: POST /attachments/sign  Types: Attachment
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/pantry/src/test/java/app/abodenestly/pantry/ItemPhotoTest.kt → AC3 offline queues the upload and does not call sign
Component: apps/android/pantry/src/test/java/app/abodenestly/pantry/ItemPhotoComposeTest.kt → AC1 uploading, AC2 photo shown, AC4 removed. States new and edit.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: yes, TESTING §6 — Fake ApiClient throws IOException, then kick(); ItemPhotoOfflineTest.kt

## Dependencies
Blocked by: CORE-11-android, PANT-02-android  Blocks: none

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
none

# PANT-11-api — Consumption stats

## Summary
The household can ask the server for the average weekly use of each item, computed from movements.

## Spec references
SPEC §4 PANT-11; §7 GET /pantry/usage; §8 UsageStat; TESTING §3 -api row

## Scope
In: GET /pantry/usage returns per-item average consumption per week from movements.
Out: The on-device screen, which DESIGN D5 computes locally (PANT-11 clients).

## Acceptance criteria
- [ ] AC1 Given movements over two weeks, when GET runs, then per_week is the average weekly consumption for that item.
- [ ] AC2 Given no JWT, when GET runs, then the response is 401.
- [ ] AC3 Given an unknown household, when GET runs, then the response is 403 or an empty list, and no other household's items are included.

## Design
Screen: n/a — this layer has no DESIGN screen.
Deviations from the plan: none

## Offline behaviour
The report reads movements that may have been written offline (SPEC §6.5). Computing it on the server needs a network.

## Module-off behaviour
Pantry off: the route is rejected.

## Data and contract
Tables: pantry_movement, pantry_item  Endpoints: GET /pantry/usage  Types: UsageStat
Schema change: no  Contract change: yes

## Tests required
Unit: api/test/PANT-11-usage.test.ts → AC1 the weekly average for a fixed movement fixture
Component: n/a (no UI)
Integration: api/test/PANT-11-usage.int.test.ts → AC1 happy path; AC2 401; AC3 no cross-household rows. Contract check covers the route.
E2E: n/a (client journeys cover the user path)
Offline test: n/a (server-side; queued client writes are covered by the client offline test)

## Dependencies
Blocked by: PANT-03-api  Blocks: PANT-11-web, PANT-11-ios, PANT-11-android

## Skill
.claude/skills/node-backend/SKILL.md

## Open questions
DESIGN D5 says the screen computes this locally, and §7 exposes GET /pantry/usage. Proposed default: both exist. The screen does not call this route.

# PANT-11-web — Usage stats

## Summary
A person can see how fast each item is used, from the movements already on the device.

## Spec references
SPEC §4 PANT-11; DESIGN D5; TESTING §3 client rows

## Scope
In: The usage screen (D5) computed from local movements, including the 8-week bars.
Out: The server report (PANT-11-api), which this screen does not call.

## Acceptance criteria
- [ ] AC1 Given fewer than two weeks of movements, when I open Usage, then I see the insufficient-data state.
- [ ] AC2 Given two or more weeks, when I open Usage, then each row shows the name, the per-week figure, and the last restocked date.
- [ ] AC3 Given eight weeks of movements, when the row renders, then it shows eight bars and no chart library is imported.
- [ ] AC4 Given the device is offline, when I open Usage, then the figures still compute from local movements.

## Design
Screen: DESIGN D5 — States: insufficient-data, populated. Interactions: read the rows; there is no further action.
Deviations from the plan: none

## Offline behaviour
DESIGN D5: computed locally, so it works offline. This matches SPEC §6.5's pantry reads.

## Module-off behaviour
Pantry off (X7): the Pantry tab is hidden and a pantry-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: pantry_movement, pantry_item  Endpoints: none on this screen  Types: UsageStat
Schema change: no  Contract change: no

## Tests required
Unit: web/src/pantry/Usage.test.ts → AC1 under two weeks is insufficient; AC2 per-week matches the fixture; AC3 eight bars
Component: web/src/pantry/Usage.test.tsx → AC4 offline renders populated. States insufficient-data and populated.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: yes, TESTING §6 — web/e2e/PANT-offline.spec.ts

## Dependencies
Blocked by: PANT-11-api, PANT-07-web  Blocks: none

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
§7 also offers GET /pantry/usage. Proposed default: the screen ignores that route and computes from local movements, which is what makes D5 work offline.

# PANT-11-ios — Usage stats

## Summary
A person can see how fast each item is used, from the movements already on the device.

## Spec references
SPEC §4 PANT-11; DESIGN D5; TESTING §3 client rows

## Scope
In: The usage screen (D5) computed from local movements, including the 8-week bars.
Out: The server report (PANT-11-api), which this screen does not call.

## Acceptance criteria
- [ ] AC1 Given fewer than two weeks of movements, when I open Usage, then I see the insufficient-data state.
- [ ] AC2 Given two or more weeks, when I open Usage, then each row shows the name, the per-week figure, and the last restocked date.
- [ ] AC3 Given eight weeks of movements, when the row renders, then it shows eight bars and no chart library is imported.
- [ ] AC4 Given the device is offline, when I open Usage, then the figures still compute from local movements.

## Design
Screen: DESIGN D5 — States: insufficient-data, populated. Interactions: read the rows; there is no further action.
Deviations from the plan: none

## Offline behaviour
DESIGN D5: computed locally, so it works offline. This matches SPEC §6.5's pantry reads.

## Module-off behaviour
Pantry off (X7): the Pantry tab is hidden and a pantry-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: pantry_movement, pantry_item  Endpoints: none on this screen  Types: UsageStat
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/pantry/Tests/UsageTests.swift → AC1 under two weeks is insufficient; AC2 per-week matches the fixture; AC3 eight bars
Component: apps/ios/AbodeNestlyTests/UsageViewTests.swift → AC4 offline renders populated. States insufficient-data and populated.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: yes, TESTING §6 — URLProtocol throws, then kick(); UsageOfflineTests.swift covers the same steps as the web file

## Dependencies
Blocked by: PANT-11-api, PANT-07-ios  Blocks: none

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
§7 also offers GET /pantry/usage. Proposed default: the screen ignores that route and computes from local movements, which is what makes D5 work offline.

# PANT-11-android — Usage stats

## Summary
A person can see how fast each item is used, from the movements already on the device.

## Spec references
SPEC §4 PANT-11; DESIGN D5; TESTING §3 client rows

## Scope
In: The usage screen (D5) computed from local movements, including the 8-week bars.
Out: The server report (PANT-11-api), which this screen does not call.

## Acceptance criteria
- [ ] AC1 Given fewer than two weeks of movements, when I open Usage, then I see the insufficient-data state.
- [ ] AC2 Given two or more weeks, when I open Usage, then each row shows the name, the per-week figure, and the last restocked date.
- [ ] AC3 Given eight weeks of movements, when the row renders, then it shows eight bars and no chart library is imported.
- [ ] AC4 Given the device is offline, when I open Usage, then the figures still compute from local movements.

## Design
Screen: DESIGN D5 — States: insufficient-data, populated. Interactions: read the rows; there is no further action.
Deviations from the plan: none

## Offline behaviour
DESIGN D5: computed locally, so it works offline. This matches SPEC §6.5's pantry reads.

## Module-off behaviour
Pantry off (X7): the Pantry tab is hidden and a pantry-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: pantry_movement, pantry_item  Endpoints: none on this screen  Types: UsageStat
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/pantry/src/test/java/app/abodenestly/pantry/UsageTest.kt → AC1 under two weeks is insufficient; AC2 per-week matches the fixture; AC3 eight bars
Component: apps/android/pantry/src/test/java/app/abodenestly/pantry/UsageComposeTest.kt → AC4 offline renders populated. States insufficient-data and populated.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: yes, TESTING §6 — Fake ApiClient throws IOException, then kick(); UsageOfflineTest.kt

## Dependencies
Blocked by: PANT-11-api, PANT-07-android  Blocks: none

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
§7 also offers GET /pantry/usage. Proposed default: the screen ignores that route and computes from local movements, which is what makes D5 work offline.

# PANT-12-db — Recipes

## Summary
The household can keep a recipe and the ingredients it needs.

## Spec references
SPEC §4 PANT-12; §8 Recipe, RecipeIngredient; DESIGN D6, D7; TESTING §3 -db row

## Scope
In: recipe and recipe_ingredient rows, household scoped.
Out: The can-make query (PANT-12-api). The screens (PANT-12 clients).

## Acceptance criteria
- [ ] AC1 Given a member, when they insert a recipe and an ingredient, then both rows are stored.
- [ ] AC2 Given a non-member, when they select the recipe, then they see nothing.
- [ ] AC3 Given an ingredient name, when it is stored, then name_key is the normalised form.

## Design
Screen: n/a — this layer has no DESIGN screen.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Pantry: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Pantry off (X7): the Pantry tab is hidden and a pantry-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: recipe, recipe_ingredient  Endpoints: none  Types: Recipe, RecipeIngredient
Schema change: yes — supabase/migrations/20261108120300_PANT-12_recipe.sql  Contract change: no

## Tests required
Unit: n/a (database behaviour is asserted in pgTAP, TESTING §2)
Component: n/a (no UI)
Integration: supabase/tests/PANT-12_recipe.sql → AC1 insert both rows; AC2 non-member denied; AC3 name_key
E2E: n/a (covered by the client journeys for this ID)
Offline test: n/a (server-side)

## Dependencies
Blocked by: PANT-02-db  Blocks: PANT-12-api, PANT-12-web, PANT-12-ios, PANT-12-android

## Skill
.claude/skills/sql-supabase/SKILL.md

## Open questions
§4 names the can-make view and does not name recipe editing. DESIGN D6 and D7 include create and edit. Proposed default: recipes are household data the member can edit, because otherwise the view has nothing to show.

# PANT-12-api — Recipes fully in stock

## Summary
The household can ask which recipes can be made from what is actually in the kitchen.

## Spec references
SPEC §4 PANT-12; §7 GET /pantry/can-make; TESTING §3 -api row

## Scope
In: GET /pantry/can-make returns recipes whose ingredients are all in stock.
Out: The recipe rows (PANT-12-db). The screen also computes this locally (PANT-12 clients).

## Acceptance criteria
- [ ] AC1 Given every ingredient is in stock, when GET runs, then that recipe is in the response.
- [ ] AC2 Given one ingredient is missing, when GET runs, then that recipe is absent.
- [ ] AC3 Given no JWT, when GET runs, then the response is 401.

## Design
Screen: n/a — this layer has no DESIGN screen.
Deviations from the plan: none

## Offline behaviour
The query needs a network. The quantities it reads may have been written offline.

## Module-off behaviour
Pantry off: the route is rejected.

## Data and contract
Tables: recipe, recipe_ingredient, pantry_item  Endpoints: GET /pantry/can-make  Types: Recipe
Schema change: no  Contract change: yes

## Tests required
Unit: api/test/PANT-12-can-make.test.ts → AC1 all in stock included; AC2 one missing excluded
Component: n/a (no UI)
Integration: api/test/PANT-12-can-make.int.test.ts → AC1 and AC2 against fixture rows; AC3 401.
E2E: n/a (client journeys cover the user path)
Offline test: n/a (server-side; queued client writes are covered by the client offline test)

## Dependencies
Blocked by: PANT-12-db  Blocks: PANT-12-web, PANT-12-ios, PANT-12-android

## Skill
.claude/skills/node-backend/SKILL.md

## Open questions
none

# PANT-12-web — What can I make

## Summary
A person can see which recipes they can cook with what is in stock, and can add the missing ingredients to a list.

## Spec references
SPEC §4 PANT-12; DESIGN D6, D7; TESTING §3 client rows

## Scope
In: The recipe list (D6) and recipe detail (D7), including edit and Add missing to list.
Out: The server query (PANT-12-api). Shopping must be on for Add missing.

## Acceptance criteria
- [ ] AC1 Given a recipe's ingredients are all in stock, when I open Can make, then that recipe is listed.
- [ ] AC2 Given some ingredients are missing, when I open the recipe, then those rows are highlighted and the others are checked.
- [ ] AC3 Given shopping is on, when I tap Add missing to list, then the missing ingredients become list items.
- [ ] AC4 Given shopping is off, when I open the recipe, then Add missing is hidden.
- [ ] AC5 Given there are no recipes, when I open the list, then I see the empty state.
- [ ] AC6 Given the device is offline, when I open Can make, then the match is computed from local pantry quantities.

## Design
Screen: DESIGN D6 and D7 — States: D6 empty, populated, can-make-empty; D7 all-in-stock, missing-some, edit. Interactions: tap a recipe; Add missing to list; edit.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Pantry: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08). DESIGN D6 says the screen is fully offline, so Can make does not wait for PANT-12-api.

## Module-off behaviour
Pantry off (X7): the Pantry tab is hidden and a pantry-table mutation is rejected with module_disabled. This screen is not reachable and must not error. Add missing is hidden when shopping is off (DESIGN D7).

## Data and contract
Tables: recipe, recipe_ingredient, pantry_item, list_item  Endpoints: none on this screen  Types: Recipe, RecipeIngredient, ListItem
Schema change: no  Contract change: no

## Tests required
Unit: web/src/pantry/Recipes.test.ts → AC1 all-in-stock is included; AC6 the match uses local quantities; AC3 missing names become list items
Component: web/src/pantry/Recipes.test.tsx → AC2 missing-some, AC4 Add missing hidden, AC5 empty. States empty, populated, can-make-empty, all-in-stock, missing-some, edit.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: yes, TESTING §6 — web/e2e/PANT-offline.spec.ts

## Dependencies
Blocked by: PANT-12-api, PANT-12-db, PANT-02-web  Blocks: none

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
DESIGN D7's Add missing to list is not in §9. Proposed default: it inserts manual list items, not a new cross-module rule, and it is hidden when shopping is off.

# PANT-12-ios — What can I make

## Summary
A person can see which recipes they can cook with what is in stock, and can add the missing ingredients to a list.

## Spec references
SPEC §4 PANT-12; DESIGN D6, D7; TESTING §3 client rows

## Scope
In: The recipe list (D6) and recipe detail (D7), including edit and Add missing to list.
Out: The server query (PANT-12-api). Shopping must be on for Add missing.

## Acceptance criteria
- [ ] AC1 Given a recipe's ingredients are all in stock, when I open Can make, then that recipe is listed.
- [ ] AC2 Given some ingredients are missing, when I open the recipe, then those rows are highlighted and the others are checked.
- [ ] AC3 Given shopping is on, when I tap Add missing to list, then the missing ingredients become list items.
- [ ] AC4 Given shopping is off, when I open the recipe, then Add missing is hidden.
- [ ] AC5 Given there are no recipes, when I open the list, then I see the empty state.
- [ ] AC6 Given the device is offline, when I open Can make, then the match is computed from local pantry quantities.

## Design
Screen: DESIGN D6 and D7 — States: D6 empty, populated, can-make-empty; D7 all-in-stock, missing-some, edit. Interactions: tap a recipe; Add missing to list; edit.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Pantry: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08). DESIGN D6 says the screen is fully offline, so Can make does not wait for PANT-12-api.

## Module-off behaviour
Pantry off (X7): the Pantry tab is hidden and a pantry-table mutation is rejected with module_disabled. This screen is not reachable and must not error. Add missing is hidden when shopping is off (DESIGN D7).

## Data and contract
Tables: recipe, recipe_ingredient, pantry_item, list_item  Endpoints: none on this screen  Types: Recipe, RecipeIngredient, ListItem
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/pantry/Tests/RecipesTests.swift → AC1 all-in-stock is included; AC6 the match uses local quantities; AC3 missing names become list items
Component: apps/ios/AbodeNestlyTests/RecipesViewTests.swift → AC2 missing-some, AC4 Add missing hidden, AC5 empty. States empty, populated, can-make-empty, all-in-stock, missing-some, edit.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: yes, TESTING §6 — URLProtocol throws, then kick(); RecipesOfflineTests.swift covers the same steps as the web file

## Dependencies
Blocked by: PANT-12-api, PANT-12-db, PANT-02-ios  Blocks: none

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
DESIGN D7's Add missing to list is not in §9. Proposed default: it inserts manual list items, not a new cross-module rule, and it is hidden when shopping is off.

# PANT-12-android — What can I make

## Summary
A person can see which recipes they can cook with what is in stock, and can add the missing ingredients to a list.

## Spec references
SPEC §4 PANT-12; DESIGN D6, D7; TESTING §3 client rows

## Scope
In: The recipe list (D6) and recipe detail (D7), including edit and Add missing to list.
Out: The server query (PANT-12-api). Shopping must be on for Add missing.

## Acceptance criteria
- [ ] AC1 Given a recipe's ingredients are all in stock, when I open Can make, then that recipe is listed.
- [ ] AC2 Given some ingredients are missing, when I open the recipe, then those rows are highlighted and the others are checked.
- [ ] AC3 Given shopping is on, when I tap Add missing to list, then the missing ingredients become list items.
- [ ] AC4 Given shopping is off, when I open the recipe, then Add missing is hidden.
- [ ] AC5 Given there are no recipes, when I open the list, then I see the empty state.
- [ ] AC6 Given the device is offline, when I open Can make, then the match is computed from local pantry quantities.

## Design
Screen: DESIGN D6 and D7 — States: D6 empty, populated, can-make-empty; D7 all-in-stock, missing-some, edit. Interactions: tap a recipe; Add missing to list; edit.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Pantry: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08). DESIGN D6 says the screen is fully offline, so Can make does not wait for PANT-12-api.

## Module-off behaviour
Pantry off (X7): the Pantry tab is hidden and a pantry-table mutation is rejected with module_disabled. This screen is not reachable and must not error. Add missing is hidden when shopping is off (DESIGN D7).

## Data and contract
Tables: recipe, recipe_ingredient, pantry_item, list_item  Endpoints: none on this screen  Types: Recipe, RecipeIngredient, ListItem
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/pantry/src/test/java/app/abodenestly/pantry/RecipesTest.kt → AC1 all-in-stock is included; AC6 the match uses local quantities; AC3 missing names become list items
Component: apps/android/pantry/src/test/java/app/abodenestly/pantry/RecipesComposeTest.kt → AC2 missing-some, AC4 Add missing hidden, AC5 empty. States empty, populated, can-make-empty, all-in-stock, missing-some, edit.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: yes, TESTING §6 — Fake ApiClient throws IOException, then kick(); RecipesOfflineTest.kt

## Dependencies
Blocked by: PANT-12-api, PANT-12-db, PANT-02-android  Blocks: none

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
DESIGN D7's Add missing to list is not in §9. Proposed default: it inserts manual list items, not a new cross-module rule, and it is hidden when shopping is off.

# PANT-13-db — Unit conversions

## Summary
The household can store how one unit turns into another.

## Spec references
SPEC §4 PANT-13; §8 UnitConversion; TESTING §3 -db row

## Scope
In: unit_conversion rows keyed by household, from_unit, and to_unit, with a numeric factor.
Out: The settings list (PANT-13 clients). Skipped layers: -api — the conversion table syncs through the outbox. §7 has no conversion endpoint.

## Acceptance criteria
- [ ] AC1 Given a member, when they insert 1 kg = 1000 g, then the factor is 1000.
- [ ] AC2 Given the same pair is inserted twice, when the second insert runs, then it is rejected by the primary key.
- [ ] AC3 Given a non-member, when they select the row, then they see nothing.

## Design
Screen: n/a — this layer has no DESIGN screen.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Pantry: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Pantry off (X7): the Pantry tab is hidden and a pantry-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: unit_conversion  Endpoints: none  Types: UnitConversion
Schema change: yes — supabase/migrations/20261108120400_PANT-13_unit_conversion.sql  Contract change: no

## Tests required
Unit: n/a (database behaviour is asserted in pgTAP, TESTING §2)
Component: n/a (no UI)
Integration: supabase/tests/PANT-13_units.sql → AC1 factor stored; AC2 duplicate key rejected; AC3 non-member denied
E2E: n/a (covered by the client journeys for this ID)
Offline test: n/a (server-side)

## Dependencies
Blocked by: CORE-02-db  Blocks: PANT-13-web, PANT-13-ios, PANT-13-android

## Skill
.claude/skills/sql-supabase/SKILL.md

## Open questions
none

# PANT-13-web — Unit conversion

## Summary
A person can teach the household that 1 kg is 1000 g, and see amounts in that unit.

## Spec references
SPEC §4 PANT-13; DESIGN D4; TESTING §3 client rows

## Scope
In: The conversions list on pantry settings, and displaying a converted quantity when a row matches.
Out: The item's own unit (PANT-02). Skipped layers: -api — the conversion table syncs through the outbox. §7 has no conversion endpoint.

## Acceptance criteria
- [ ] AC1 Given no conversions have been added, when I open settings, then the conversions list is empty and quantities show the item's own unit.
- [ ] AC2 Given I add 1 kg = 1000 g, when I save, then the row reads 1 kg = 1000 g.
- [ ] AC3 Given an item is stored in g and a conversion to kg exists, when I view the item, then I can see the kg figure as well as the stored grams.
- [ ] AC4 Given the device is offline, when I add a conversion, then it is queued and the display updates locally.

## Design
Screen: DESIGN D4 — States: defaults-only, custom. Interactions: add a conversion row.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Pantry: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Pantry off (X7): the Pantry tab is hidden and a pantry-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: unit_conversion, pantry_item  Endpoints: none  Types: UnitConversion
Schema change: no  Contract change: no

## Tests required
Unit: web/src/pantry/Units.test.ts → AC3 a 1000 g item also renders 1 kg when that row exists
Component: web/src/pantry/Units.test.tsx → AC1 empty list, AC2 the saved row, AC4 offline. States defaults-only and custom.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: yes, TESTING §6 — web/e2e/PANT-offline.spec.ts

## Dependencies
Blocked by: PANT-13-db, PANT-02-web  Blocks: none

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
§4 says display in the preferred unit, and §8 has no preferred-unit field. Proposed default: the item keeps its stored unit, and any matching conversion is shown beside it. There is no separate preferred-unit setting.

# PANT-13-ios — Unit conversion

## Summary
A person can teach the household that 1 kg is 1000 g, and see amounts in that unit.

## Spec references
SPEC §4 PANT-13; DESIGN D4; TESTING §3 client rows

## Scope
In: The conversions list on pantry settings, and displaying a converted quantity when a row matches.
Out: The item's own unit (PANT-02). Skipped layers: -api — the conversion table syncs through the outbox. §7 has no conversion endpoint.

## Acceptance criteria
- [ ] AC1 Given no conversions have been added, when I open settings, then the conversions list is empty and quantities show the item's own unit.
- [ ] AC2 Given I add 1 kg = 1000 g, when I save, then the row reads 1 kg = 1000 g.
- [ ] AC3 Given an item is stored in g and a conversion to kg exists, when I view the item, then I can see the kg figure as well as the stored grams.
- [ ] AC4 Given the device is offline, when I add a conversion, then it is queued and the display updates locally.

## Design
Screen: DESIGN D4 — States: defaults-only, custom. Interactions: add a conversion row.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Pantry: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Pantry off (X7): the Pantry tab is hidden and a pantry-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: unit_conversion, pantry_item  Endpoints: none  Types: UnitConversion
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/pantry/Tests/UnitsTests.swift → AC3 a 1000 g item also renders 1 kg when that row exists
Component: apps/ios/AbodeNestlyTests/UnitsViewTests.swift → AC1 empty list, AC2 the saved row, AC4 offline. States defaults-only and custom.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: yes, TESTING §6 — URLProtocol throws, then kick(); UnitsOfflineTests.swift covers the same steps as the web file

## Dependencies
Blocked by: PANT-13-db, PANT-02-ios  Blocks: none

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
§4 says display in the preferred unit, and §8 has no preferred-unit field. Proposed default: the item keeps its stored unit, and any matching conversion is shown beside it. There is no separate preferred-unit setting.

# PANT-13-android — Unit conversion

## Summary
A person can teach the household that 1 kg is 1000 g, and see amounts in that unit.

## Spec references
SPEC §4 PANT-13; DESIGN D4; TESTING §3 client rows

## Scope
In: The conversions list on pantry settings, and displaying a converted quantity when a row matches.
Out: The item's own unit (PANT-02). Skipped layers: -api — the conversion table syncs through the outbox. §7 has no conversion endpoint.

## Acceptance criteria
- [ ] AC1 Given no conversions have been added, when I open settings, then the conversions list is empty and quantities show the item's own unit.
- [ ] AC2 Given I add 1 kg = 1000 g, when I save, then the row reads 1 kg = 1000 g.
- [ ] AC3 Given an item is stored in g and a conversion to kg exists, when I view the item, then I can see the kg figure as well as the stored grams.
- [ ] AC4 Given the device is offline, when I add a conversion, then it is queued and the display updates locally.

## Design
Screen: DESIGN D4 — States: defaults-only, custom. Interactions: add a conversion row.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Pantry: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
Pantry off (X7): the Pantry tab is hidden and a pantry-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: unit_conversion, pantry_item  Endpoints: none  Types: UnitConversion
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/pantry/src/test/java/app/abodenestly/pantry/UnitsTest.kt → AC3 a 1000 g item also renders 1 kg when that row exists
Component: apps/android/pantry/src/test/java/app/abodenestly/pantry/UnitsComposeTest.kt → AC1 empty list, AC2 the saved row, AC4 offline. States defaults-only and custom.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: yes, TESTING §6 — Fake ApiClient throws IOException, then kick(); UnitsOfflineTest.kt

## Dependencies
Blocked by: PANT-13-db, PANT-02-android  Blocks: none

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
§4 says display in the preferred unit, and §8 has no preferred-unit field. Proposed default: the item keeps its stored unit, and any matching conversion is shown beside it. There is no separate preferred-unit setting.

# PANT-14-db — Low stock adds a shopping line

## Summary
When something falls to its low line, it shows up once on the shopping list.

## Spec references
SPEC §4 PANT-14; §9 X1; TESTING §3 -db row, §8 E2E-PANT-1

## Scope
In: Trigger pantry_item_low_stock inserting one open pantry-sourced list item into the oldest non-archived, non-template list.
Out: The cross-module matrix cases that sit on top of this trigger are X1-db. The badge and prompt are SHOP-16. Skipped layers: -api, -web, -ios, -android — the feature is the X1 trigger. The low badge on the pantry-only matrix is drawn by PANT-04's low state. There is no PANT-14 screen.

## Acceptance criteria
- [ ] AC1 Given quantity crosses from above the threshold to at or below it, auto-add is on, and shopping is on, when the update commits, then one list item with source pantry is inserted.
- [ ] AC2 Given the quantity was already at or below the threshold, when it is updated again, then no second line is inserted.
- [ ] AC3 Given shopping is off, or auto-add is off, or the threshold is null, when the quantity crosses, then no list item is inserted.
- [ ] AC4 Given no eligible list exists, when the quantity crosses, then nothing is inserted and the statement still succeeds.

## Design
Screen: n/a — this layer has no DESIGN screen.
Deviations from the plan: none

## Offline behaviour
The trigger runs when the quantity change reaches the server, including a delta that was queued offline (SPEC §6.5).

## Module-off behaviour
Pantry only (the §9 matrix): no shopping line is created. The low badge is the client state on PANT-04, not this trigger.

## Data and contract
Tables: pantry_item, list_item, shopping_list, household  Endpoints: none  Types: ListItem, PantryItem
Schema change: yes — supabase/migrations/20261108120500_PANT-14_low_stock.sql  Contract change: no

## Tests required
Unit: n/a (database behaviour is asserted in pgTAP, TESTING §2)
Component: n/a (no UI)
Integration: supabase/tests/PANT-14_low_stock.sql → AC1 one insert; AC2 no second line; AC3 gates hold; AC4 no list is a no-op
E2E: n/a (covered by the client journeys for this ID)
Offline test: n/a (server-side)

## Dependencies
Blocked by: PANT-02-db, SHOP-01-db  Blocks: X1-db

## Skill
.claude/skills/sql-supabase/SKILL.md

## Open questions
none

# PANT-15-web — Restock from a checked-off list item

## Summary
Accepting restock puts the bought amount back into the pantry as a restock movement.

## Spec references
SPEC §4 PANT-15; §9 X2; DESIGN C2; TESTING §8 E2E-PANT-2

## Scope
In: Tapping Yes on the restock prompt writes a restocked movement.
Out: Showing the prompt and the badge (SHOP-16). Applying the delta (X5). Skipped layers: -db, -api — the movement is a client delta. X5 applies it. X2 is the cross-module gate on the same gesture.

## Acceptance criteria
- [ ] AC1 Given the prompt is showing and the list item quantity is 2, when I tap Yes, then a movement with reason restocked and delta 2 is queued.
- [ ] AC2 Given the list item quantity is empty, when I tap Yes, then the delta is 1.
- [ ] AC3 Given I tap No, when the prompt closes, then no movement is queued.
- [ ] AC4 Given the device is offline, when I tap Yes, then the local pantry quantity includes the delta and the outbox has it.

## Design
Screen: DESIGN C2 — States: populated, offline. Interactions: Yes and No on Restock pantry?.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Pantry: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
X2 runs only when pantry is on and the item source is pantry. Pantry off: the prompt is absent (SHOP-16) and a movement would be rejected (X7).

## Data and contract
Tables: pantry_movement, list_item  Endpoints: POST /sync (delta)  Types: MutationDelta, PantryMovement
Schema change: no  Contract change: no

## Tests required
Unit: web/src/pantry/Restock.test.ts → AC1 delta equals the list quantity; AC2 empty quantity yields 1; AC3 No writes nothing
Component: web/src/pantry/Restock.test.tsx → AC4 offline. States populated and offline. Interactions: Yes and No.
Integration: n/a (no API handler in this story)
E2E: E2E-PANT-2 web/e2e/Restock.spec.ts
Offline test: yes, TESTING §6 — web/e2e/PANT-offline.spec.ts

## Dependencies
Blocked by: SHOP-16-web, PANT-03-web  Blocks: X2-web

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
none

# PANT-15-ios — Restock from a checked-off list item

## Summary
Accepting restock puts the bought amount back into the pantry as a restock movement.

## Spec references
SPEC §4 PANT-15; §9 X2; DESIGN C2; TESTING §8 E2E-PANT-2

## Scope
In: Tapping Yes on the restock prompt writes a restocked movement.
Out: Showing the prompt and the badge (SHOP-16). Applying the delta (X5). Skipped layers: -db, -api — the movement is a client delta. X5 applies it. X2 is the cross-module gate on the same gesture.

## Acceptance criteria
- [ ] AC1 Given the prompt is showing and the list item quantity is 2, when I tap Yes, then a movement with reason restocked and delta 2 is queued.
- [ ] AC2 Given the list item quantity is empty, when I tap Yes, then the delta is 1.
- [ ] AC3 Given I tap No, when the prompt closes, then no movement is queued.
- [ ] AC4 Given the device is offline, when I tap Yes, then the local pantry quantity includes the delta and the outbox has it.

## Design
Screen: DESIGN C2 — States: populated, offline. Interactions: Yes and No on Restock pantry?.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Pantry: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
X2 runs only when pantry is on and the item source is pantry. Pantry off: the prompt is absent (SHOP-16) and a movement would be rejected (X7).

## Data and contract
Tables: pantry_movement, list_item  Endpoints: POST /sync (delta)  Types: MutationDelta, PantryMovement
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/pantry/Tests/RestockTests.swift → AC1 delta equals the list quantity; AC2 empty quantity yields 1; AC3 No writes nothing
Component: apps/ios/AbodeNestlyTests/RestockViewTests.swift → AC4 offline. States populated and offline. Interactions: Yes and No.
Integration: n/a (no API handler in this story)
E2E: E2E-PANT-2 apps/ios/AbodeNestlyUITests/RestockUITests.swift
Offline test: yes, TESTING §6 — URLProtocol throws, then kick(); RestockOfflineTests.swift covers the same steps as the web file

## Dependencies
Blocked by: SHOP-16-ios, PANT-03-ios  Blocks: X2-ios

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
none

# PANT-15-android — Restock from a checked-off list item

## Summary
Accepting restock puts the bought amount back into the pantry as a restock movement.

## Spec references
SPEC §4 PANT-15; §9 X2; DESIGN C2; TESTING §8 E2E-PANT-2

## Scope
In: Tapping Yes on the restock prompt writes a restocked movement.
Out: Showing the prompt and the badge (SHOP-16). Applying the delta (X5). Skipped layers: -db, -api — the movement is a client delta. X5 applies it. X2 is the cross-module gate on the same gesture.

## Acceptance criteria
- [ ] AC1 Given the prompt is showing and the list item quantity is 2, when I tap Yes, then a movement with reason restocked and delta 2 is queued.
- [ ] AC2 Given the list item quantity is empty, when I tap Yes, then the delta is 1.
- [ ] AC3 Given I tap No, when the prompt closes, then no movement is queued.
- [ ] AC4 Given the device is offline, when I tap Yes, then the local pantry quantity includes the delta and the outbox has it.

## Design
Screen: DESIGN C2 — States: populated, offline. Interactions: Yes and No on Restock pantry?.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Pantry: the screen is readable offline and every write is applied to the local database and the outbox in one local transaction, then pushed when the network returns (SHOP-08).

## Module-off behaviour
X2 runs only when pantry is on and the item source is pantry. Pantry off: the prompt is absent (SHOP-16) and a movement would be rejected (X7).

## Data and contract
Tables: pantry_movement, list_item  Endpoints: POST /sync (delta)  Types: MutationDelta, PantryMovement
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/pantry/src/test/java/app/abodenestly/pantry/RestockTest.kt → AC1 delta equals the list quantity; AC2 empty quantity yields 1; AC3 No writes nothing
Component: apps/android/pantry/src/test/java/app/abodenestly/pantry/RestockComposeTest.kt → AC4 offline. States populated and offline. Interactions: Yes and No.
Integration: n/a (no API handler in this story)
E2E: E2E-PANT-2 apps/android/pantry/src/androidTest/java/app/abodenestly/pantry/RestockE2ETest.kt
Offline test: yes, TESTING §6 — Fake ApiClient throws IOException, then kick(); RestockOfflineTest.kt

## Dependencies
Blocked by: SHOP-16-android, PANT-03-android  Blocks: X2-android

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
none

# PANT-16-db — Queue the daily eat-soon notice

## Summary
Once a day, a notice is waiting for food that expires within three days, for people who still want it.

## Spec references
SPEC §4 PANT-16; §10; TESTING §3 -db row

## Scope
In: The eat-soon insert inside enqueue_daily_notifications: expiry within 3 days, pantry module on, preference respected, deduped per item per window.
Out: Delivery (PANT-16-api). The 7-day screen (PANT-05). Task and warranty inserts are MAINT-08-db and MAINT-11-db.

## Acceptance criteria
- [ ] AC1 Given an item expires in 2 days and the preference is on, when the job runs, then one eat_soon notification is inserted per member.
- [ ] AC2 Given the job runs again the same day, when it finishes, then no second eat_soon row is inserted for that item and member.
- [ ] AC3 Given the preference is off or pantry is off, when the job runs, then no eat_soon row is inserted.

## Design
Screen: n/a — this layer has no DESIGN screen.
Deviations from the plan: none

## Offline behaviour
The job reads expiry dates that may have been edited offline. Sending is PANT-16-api and needs a network.

## Module-off behaviour
Pantry off: the insert does not run (§10 joins on module_pantry).

## Data and contract
Tables: notification, notification_pref, pantry_item  Endpoints: none  Types: Notification
Schema change: yes — supabase/migrations/20261108120600_PANT-16_eat_soon.sql  Contract change: no

## Tests required
Unit: n/a (database behaviour is asserted in pgTAP, TESTING §2)
Component: n/a (no UI)
Integration: supabase/tests/PANT-16_eat_soon.sql → AC1 one row per member; AC2 deduped; AC3 preference and module respected
E2E: n/a (covered by the client journeys for this ID)
Offline test: n/a (server-side)

## Dependencies
Blocked by: PANT-02-db, CORE-12-db  Blocks: PANT-16-api, PANT-16-web, PANT-16-ios, PANT-16-android, MAINT-08-db

## Skill
.claude/skills/sql-supabase/SKILL.md

## Open questions
§10 also writes a low-stock notice when X1 fires, and that notice has no feature ID. Proposed default: the low-stock insert is part of X1-db, not this job.

# PANT-16-api — Deliver notifications and register devices

## Summary
A queued notice actually reaches the phone, and the phone can tell the server where to send it.

## Spec references
SPEC §4 PANT-16; §7 POST /devices and POST /internal/cron/deliver-notifications; §10; TESTING §3 -api row

## Scope
In: Device registration and the cron sender that marks sent_at. The sender is shared by later notice types.
Out: The eat-soon rows (PANT-16-db). Task and warranty rows reuse this sender.

## Acceptance criteria
- [ ] AC1 Given a device token, when POST /devices succeeds, then the token is stored for that user and the response is 204.
- [ ] AC2 Given no JWT, when POST /devices is called, then the response is 401.
- [ ] AC3 Given the cron secret is wrong, when the deliver route is called, then the response is 401 and nothing is sent.
- [ ] AC4 Given an unsent eat_soon row and a device token, when the deliver route runs, then sent_at is set.

## Design
Screen: n/a — this layer has no DESIGN screen.
Deviations from the plan: none

## Offline behaviour
Delivery needs a network. The expiry dates behind the row may have been written offline.

## Module-off behaviour
The sender does not invent rows. PANT-16-db already skipped pantry-off households.

## Data and contract
Tables: device_token, notification  Endpoints: POST /devices; DELETE /devices/{token}; POST /internal/cron/deliver-notifications  Types: DeviceToken, Notification
Schema change: no  Contract change: yes

## Tests required
Unit: api/test/PANT-16-push.test.ts → AC3 wrong cron secret is 401
Component: n/a (no UI)
Integration: api/test/PANT-16-push.int.test.ts → AC1 token stored; AC2 401; AC4 sent_at set. The push provider is faked.
E2E: n/a (client journeys cover the user path)
Offline test: n/a (server-side; queued client writes are covered by the client offline test)

## Dependencies
Blocked by: PANT-16-db  Blocks: PANT-16-web, PANT-16-ios, PANT-16-android

## Skill
.claude/skills/node-backend/SKILL.md

## Open questions
POST /devices has no feature ID of its own. Proposed default: it lives in this story because no eat-soon push can arrive without it. DELETE /devices is included.

# PANT-16-web — Eat-soon push and the inbox

## Summary
A person gets a daily nudge about food that expires within three days, and can read it again inside the app.

## Spec references
SPEC §4 PANT-16; §10 inbox; DESIGN B10; TESTING §3 client rows

## Scope
In: Register the device, receive the eat-soon push, and show the inbox (B10).
Out: The 7-day Eat soon screen (PANT-05). Preference switches (CORE-12). Task and warranty pushes reuse the inbox.

## Acceptance criteria
- [ ] AC1 Given notification permission is granted, when the app starts, then the device token is registered.
- [ ] AC2 Given an eat-soon push arrives, when I tap it, then the pantry item it names is opened.
- [ ] AC3 Given the inbox has rows, when I open it, then unread rows are bold and Mark all read sets read_at.
- [ ] AC4 Given the inbox is empty, when I open it, then I see the empty state.
- [ ] AC5 Given the device is offline, when I open the inbox, then cached notices are shown.

## Design
Screen: DESIGN B10 — States: empty, populated. Interactions: Mark all read; tap a row.
Deviations from the plan: none

## Offline behaviour
Receiving a push needs a network. The inbox is readable offline once the notice rows have synced (DESIGN B10).

## Module-off behaviour
Pantry off (X7): the Pantry tab is hidden and a pantry-table mutation is rejected with module_disabled. This screen is not reachable and must not error. No eat-soon push is sent while pantry is off. The inbox itself stays, because it also holds other types.

## Data and contract
Tables: notification, device_token  Endpoints: POST /devices  Types: Notification, DeviceToken
Schema change: no  Contract change: no

## Tests required
Unit: web/src/pantry/Inbox.test.ts → AC3 mark all read writes read_at; AC5 offline reads the cache
Component: web/src/pantry/Inbox.test.tsx → AC2 tap opens the item, AC4 empty, AC1 the registration call. States empty and populated.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: n/a (the inbox is a read model; the pantry edit that caused it has its own offline test)

## Dependencies
Blocked by: PANT-16-db, PANT-16-api, CORE-12-web  Blocks: MAINT-08-web, MAINT-11-web

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
DESIGN B10 has no §4 ID. §10 says clients sync an inbox. Proposed default: the inbox ships in this story, and later notice types only add rows to it.

# PANT-16-ios — Eat-soon push and the inbox

## Summary
A person gets a daily nudge about food that expires within three days, and can read it again inside the app.

## Spec references
SPEC §4 PANT-16; §10 inbox; DESIGN B10; TESTING §3 client rows

## Scope
In: Register the device, receive the eat-soon push, and show the inbox (B10).
Out: The 7-day Eat soon screen (PANT-05). Preference switches (CORE-12). Task and warranty pushes reuse the inbox.

## Acceptance criteria
- [ ] AC1 Given notification permission is granted, when the app starts, then the device token is registered.
- [ ] AC2 Given an eat-soon push arrives, when I tap it, then the pantry item it names is opened.
- [ ] AC3 Given the inbox has rows, when I open it, then unread rows are bold and Mark all read sets read_at.
- [ ] AC4 Given the inbox is empty, when I open it, then I see the empty state.
- [ ] AC5 Given the device is offline, when I open the inbox, then cached notices are shown.

## Design
Screen: DESIGN B10 — States: empty, populated. Interactions: Mark all read; tap a row.
Deviations from the plan: none

## Offline behaviour
Receiving a push needs a network. The inbox is readable offline once the notice rows have synced (DESIGN B10).

## Module-off behaviour
Pantry off (X7): the Pantry tab is hidden and a pantry-table mutation is rejected with module_disabled. This screen is not reachable and must not error. No eat-soon push is sent while pantry is off. The inbox itself stays, because it also holds other types.

## Data and contract
Tables: notification, device_token  Endpoints: POST /devices  Types: Notification, DeviceToken
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/pantry/Tests/InboxTests.swift → AC3 mark all read writes read_at; AC5 offline reads the cache
Component: apps/ios/AbodeNestlyTests/InboxViewTests.swift → AC2 tap opens the item, AC4 empty, AC1 the registration call. States empty and populated.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: n/a (the inbox is a read model; the pantry edit that caused it has its own offline test)

## Dependencies
Blocked by: PANT-16-db, PANT-16-api, CORE-12-ios  Blocks: MAINT-08-ios, MAINT-11-ios

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
DESIGN B10 has no §4 ID. §10 says clients sync an inbox. Proposed default: the inbox ships in this story, and later notice types only add rows to it.

# PANT-16-android — Eat-soon push and the inbox

## Summary
A person gets a daily nudge about food that expires within three days, and can read it again inside the app.

## Spec references
SPEC §4 PANT-16; §10 inbox; DESIGN B10; TESTING §3 client rows

## Scope
In: Register the device, receive the eat-soon push, and show the inbox (B10).
Out: The 7-day Eat soon screen (PANT-05). Preference switches (CORE-12). Task and warranty pushes reuse the inbox.

## Acceptance criteria
- [ ] AC1 Given notification permission is granted, when the app starts, then the device token is registered.
- [ ] AC2 Given an eat-soon push arrives, when I tap it, then the pantry item it names is opened.
- [ ] AC3 Given the inbox has rows, when I open it, then unread rows are bold and Mark all read sets read_at.
- [ ] AC4 Given the inbox is empty, when I open it, then I see the empty state.
- [ ] AC5 Given the device is offline, when I open the inbox, then cached notices are shown.

## Design
Screen: DESIGN B10 — States: empty, populated. Interactions: Mark all read; tap a row.
Deviations from the plan: none

## Offline behaviour
Receiving a push needs a network. The inbox is readable offline once the notice rows have synced (DESIGN B10).

## Module-off behaviour
Pantry off (X7): the Pantry tab is hidden and a pantry-table mutation is rejected with module_disabled. This screen is not reachable and must not error. No eat-soon push is sent while pantry is off. The inbox itself stays, because it also holds other types.

## Data and contract
Tables: notification, device_token  Endpoints: POST /devices  Types: Notification, DeviceToken
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/pantry/src/test/java/app/abodenestly/pantry/InboxTest.kt → AC3 mark all read writes read_at; AC5 offline reads the cache
Component: apps/android/pantry/src/test/java/app/abodenestly/pantry/InboxComposeTest.kt → AC2 tap opens the item, AC4 empty, AC1 the registration call. States empty and populated.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: n/a (the inbox is a read model; the pantry edit that caused it has its own offline test)

## Dependencies
Blocked by: PANT-16-db, PANT-16-api, CORE-12-android  Blocks: MAINT-08-android, MAINT-11-android

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
DESIGN B10 has no §4 ID. §10 says clients sync an inbox. Proposed default: the inbox ships in this story, and later notice types only add rows to it.
