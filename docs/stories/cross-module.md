# Cross-module stories

SPEC §9 rules X1–X7. Each body is `docs/STORY-TEMPLATE.md` verbatim. These are separate from the §4 feature stories they support.

# X1-db — Low stock crosses into the shopping list

## Summary
When the pantry and the shopping list are both on, a low item becomes one shopping line and does not do that twice.

## Spec references
SPEC §9 X1; §4 PANT-14; TESTING §3 -db row, §3 cross-module row, §8 E2E-PANT-1

## Scope
In: The matrix cases on the PANT-14 trigger: both modules on, shopping off, and the partial unique index.
Out: Creating the trigger (PANT-14-db). The low badge (PANT-04). The from pantry badge (SHOP-16). Skipped layers: -api, -web, -ios, -android — X1 is a Postgres trigger. Clients only show its result, which is SHOP-16 and PANT-04.

## Acceptance criteria
- [ ] AC1 Given shopping and pantry are on and the quantity crosses the threshold, when the trigger runs, then the line is in the oldest eligible list with source pantry and pantry_item_id set.
- [ ] AC2 Given that open line already exists, when the quantity crosses again, then the unique index keeps a single open line.
- [ ] AC3 Given only pantry is on, when the quantity crosses, then no shopping line is inserted.
- [ ] AC4 Given shopping and pantry are on and there is no list, when the quantity crosses, then the update still commits and no line exists.

## Design
Screen: n/a — this layer has no DESIGN screen.
Deviations from the plan: none

## Offline behaviour
The trigger runs on the server when a queued delta is applied (SPEC §6.5 Pantry).

## Module-off behaviour
Pantry only (the §9 matrix): no shopping line. Shopping only: this trigger does not run because the pantry edit is rejected. Both on: X1 is active.

## Data and contract
Tables: pantry_item, list_item, household  Endpoints: none  Types: ListItem
Schema change: no  Contract change: no

## Tests required
Unit: n/a (database behaviour is asserted in pgTAP, TESTING §2)
Component: n/a (no UI)
Integration: supabase/tests/X1_low_stock_matrix.sql → AC1 oldest list and source; AC2 unique index; AC3 pantry only inserts nothing; AC4 no list commits
E2E: n/a (covered by the client journeys for this ID)
Offline test: n/a (server-side)

## Dependencies
Blocked by: PANT-14-db  Blocks: SHOP-16-web, SHOP-16-ios, SHOP-16-android

## Skill
.claude/skills/sql-supabase/SKILL.md

## Open questions
§10 writes a low-stock notification when X1 fires, and that notice has no feature ID. Proposed default: this trigger's migration also inserts that notification, including the actor, and it is not part of the daily job.

# X2-web — Restock only when pantry is on

## Summary
Checking off a pantry line offers to restock only when the pantry is actually in use.

## Spec references
SPEC §9 X2; §4 PANT-15; §4 SHOP-16; the §9 matrix; TESTING §8 E2E-PANT-2

## Scope
In: The gate: the offer and the delta exist only when pantry is on and the source is pantry.
Out: Drawing the badge (SHOP-16). Writing the delta (PANT-15). Applying it (X5). Skipped layers: -db, -api — X2 is client UI plus a sync delta. The movement trigger is X5. The Yes button's payload is PANT-15.

## Acceptance criteria
- [ ] AC1 Given shopping and pantry are on and the source is pantry, when I check the item off and tap Yes, then the delta is the list quantity or 1.
- [ ] AC2 Given only shopping is on, when I check an item off, then there is no restock offer.
- [ ] AC3 Given pantry is off, when a pantry-sourced row is still in the local database, then checking it off does not queue a movement.
- [ ] AC4 Given the source is manual, when I check it off with both modules on, then there is no restock offer.

## Design
Screen: DESIGN C2 — States: populated, offline. Interactions: the Yes and No prompt is present only when pantry is on.
Deviations from the plan: none

## Offline behaviour
The delta, when it is offered, queues offline like any pantry write (SPEC §6.5).

## Module-off behaviour
Shopping only (the §9 matrix): no pantry badges and no restock. Pantry plus shopping: X2 is active. Pantry off: no movement.

## Data and contract
Tables: list_item, pantry_movement  Endpoints: POST /sync  Types: MutationDelta
Schema change: no  Contract change: no

## Tests required
Unit: web/src/shopping/RestockGate.test.ts → AC1 the delta matches X2; AC2 shopping-only writes no movement; AC3 pantry off writes no movement; AC4 manual writes no movement
Component: web/src/shopping/RestockGate.test.tsx → AC2 no prompt, AC4 no prompt. States populated and offline.
Integration: n/a (no API handler in this story)
E2E: E2E-PANT-2 web/e2e/RestockGate.spec.ts
Offline test: yes, TESTING §6 — the Yes path queues a delta and the outbox empties after kick()

## Dependencies
Blocked by: PANT-15-web, SHOP-16-web  Blocks: none

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
none

# X2-ios — Restock only when pantry is on

## Summary
Checking off a pantry line offers to restock only when the pantry is actually in use.

## Spec references
SPEC §9 X2; §4 PANT-15; §4 SHOP-16; the §9 matrix; TESTING §8 E2E-PANT-2

## Scope
In: The gate: the offer and the delta exist only when pantry is on and the source is pantry.
Out: Drawing the badge (SHOP-16). Writing the delta (PANT-15). Applying it (X5). Skipped layers: -db, -api — X2 is client UI plus a sync delta. The movement trigger is X5. The Yes button's payload is PANT-15.

## Acceptance criteria
- [ ] AC1 Given shopping and pantry are on and the source is pantry, when I check the item off and tap Yes, then the delta is the list quantity or 1.
- [ ] AC2 Given only shopping is on, when I check an item off, then there is no restock offer.
- [ ] AC3 Given pantry is off, when a pantry-sourced row is still in the local database, then checking it off does not queue a movement.
- [ ] AC4 Given the source is manual, when I check it off with both modules on, then there is no restock offer.

## Design
Screen: DESIGN C2 — States: populated, offline. Interactions: the Yes and No prompt is present only when pantry is on.
Deviations from the plan: none

## Offline behaviour
The delta, when it is offered, queues offline like any pantry write (SPEC §6.5).

## Module-off behaviour
Shopping only (the §9 matrix): no pantry badges and no restock. Pantry plus shopping: X2 is active. Pantry off: no movement.

## Data and contract
Tables: list_item, pantry_movement  Endpoints: POST /sync  Types: MutationDelta
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/shopping/Tests/RestockGateTests.swift → AC1 the delta matches X2; AC2 shopping-only writes no movement; AC3 pantry off writes no movement; AC4 manual writes no movement
Component: apps/ios/AbodeNestlyTests/RestockGateViewTests.swift → AC2 no prompt, AC4 no prompt. States populated and offline.
Integration: n/a (no API handler in this story)
E2E: E2E-PANT-2 apps/ios/AbodeNestlyUITests/RestockGateUITests.swift
Offline test: yes, TESTING §6 — URLProtocol throws, then kick(); RestockGateOfflineTests.swift covers the same steps as the web file

## Dependencies
Blocked by: PANT-15-ios, SHOP-16-ios  Blocks: none

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
none

# X2-android — Restock only when pantry is on

## Summary
Checking off a pantry line offers to restock only when the pantry is actually in use.

## Spec references
SPEC §9 X2; §4 PANT-15; §4 SHOP-16; the §9 matrix; TESTING §8 E2E-PANT-2

## Scope
In: The gate: the offer and the delta exist only when pantry is on and the source is pantry.
Out: Drawing the badge (SHOP-16). Writing the delta (PANT-15). Applying it (X5). Skipped layers: -db, -api — X2 is client UI plus a sync delta. The movement trigger is X5. The Yes button's payload is PANT-15.

## Acceptance criteria
- [ ] AC1 Given shopping and pantry are on and the source is pantry, when I check the item off and tap Yes, then the delta is the list quantity or 1.
- [ ] AC2 Given only shopping is on, when I check an item off, then there is no restock offer.
- [ ] AC3 Given pantry is off, when a pantry-sourced row is still in the local database, then checking it off does not queue a movement.
- [ ] AC4 Given the source is manual, when I check it off with both modules on, then there is no restock offer.

## Design
Screen: DESIGN C2 — States: populated, offline. Interactions: the Yes and No prompt is present only when pantry is on.
Deviations from the plan: none

## Offline behaviour
The delta, when it is offered, queues offline like any pantry write (SPEC §6.5).

## Module-off behaviour
Shopping only (the §9 matrix): no pantry badges and no restock. Pantry plus shopping: X2 is active. Pantry off: no movement.

## Data and contract
Tables: list_item, pantry_movement  Endpoints: POST /sync  Types: MutationDelta
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/shopping/src/test/java/app/abodenestly/shopping/RestockGateTest.kt → AC1 the delta matches X2; AC2 shopping-only writes no movement; AC3 pantry off writes no movement; AC4 manual writes no movement
Component: apps/android/shopping/src/test/java/app/abodenestly/shopping/RestockGateComposeTest.kt → AC2 no prompt, AC4 no prompt. States populated and offline.
Integration: n/a (no API handler in this story)
E2E: E2E-PANT-2 apps/android/shopping/src/androidTest/java/app/abodenestly/shopping/RestockGateE2ETest.kt
Offline test: yes, TESTING §6 — Fake ApiClient throws IOException, then kick(); RestockGateOfflineTest.kt

## Dependencies
Blocked by: PANT-15-android, SHOP-16-android  Blocks: none

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
none

# X3-api — Send consumables to a shopping list

## Summary
One action puts a task's consumables on the shopping list, tagged with that task, without duplicating a line that is already there.

## Spec references
SPEC §9 X3; §4 MAINT-16; §7 POST /tasks/{id}/consumables-to-list; TESTING §8 E2E-MAINT-2

## Scope
In: The route inserts one list item per consumable with source maintenance and maint_task_id, skipping an unchecked line with the same name_key and task.
Out: The button (MAINT-16 clients). The badge (SHOP-17). MAINT-16-api only checks the route is not duplicated. Skipped layers: -db, -web, -ios, -android — X3 is one API route. The button is MAINT-16 clients and the badge is SHOP-17.

## Acceptance criteria
- [ ] AC1 Given shopping is on and the task has two consumables, when the route succeeds, then two list items come back with source maintenance.
- [ ] AC2 Given an unchecked line with the same name_key and task already exists, when the route runs, then that consumable is skipped.
- [ ] AC3 Given shopping is off, when the route is called, then it is rejected and no list item is inserted.
- [ ] AC4 Given no JWT, when the route is called, then the response is 401.

## Design
Screen: n/a — this layer has no DESIGN screen.
Deviations from the plan: none

## Offline behaviour
The route needs a network (DESIGN E3). The shopping rows it creates then follow shopping offline rules.

## Module-off behaviour
Shopping off: rejected (X3's gate). Maintenance only (the §9 matrix): the button that calls this route is hidden. Pantry plus maintenance: no coupling, so this route is still refused while shopping is off.

## Data and contract
Tables: task_consumable, list_item, household  Endpoints: POST /tasks/{id}/consumables-to-list  Types: ListItem, TaskConsumable
Schema change: no  Contract change: yes

## Tests required
Unit: api/test/X3-consumables.test.ts → AC2 the skip; AC3 shopping off
Component: n/a (no UI)
Integration: api/test/X3-consumables.int.test.ts → AC1 two items; AC2 skip; AC4 401. Contract check covers the route.
E2E: n/a (client journeys cover the user path)
Offline test: n/a (server-side; queued client writes are covered by the client offline test)

## Dependencies
Blocked by: MAINT-16-db, SHOP-01-db  Blocks: SHOP-17-web, SHOP-17-ios, SHOP-17-android, MAINT-16-api, MAINT-16-web, MAINT-16-ios, MAINT-16-android

## Skill
.claude/skills/node-backend/SKILL.md

## Open questions
none

# X4-db — Learn aisle order when an item is checked off

## Summary
Each check-off at a store teaches the order the household actually walked.

## Spec references
SPEC §9 X4; §4 SHOP-06; TESTING §3 -db row, §8 E2E-SHOP-3

## Scope
In: Trigger list_item_learn updates store_item_order.mean_position for the list's store and the item's name_key. No store means no write.
Out: The sort (SHOP-06). Drag teaching is SHOP-07, not this trigger. Skipped layers: -api, -web, -ios, -android — X4 is the check-off trigger. Sorting and the store screen are SHOP-06.

## Acceptance criteria
- [ ] AC1 Given the list has a store and the item flips to checked, when the trigger runs, then the store's mean for that name_key is updated and samples increases.
- [ ] AC2 Given the list has no store, when the item is checked, then store_item_order is unchanged.
- [ ] AC3 Given the item was already checked, when another column changes, then the mean is not updated again.

## Design
Screen: n/a — this layer has no DESIGN screen.
Deviations from the plan: none

## Offline behaviour
An offline check-off updates the row when it syncs, and this trigger runs on that update (SPEC §6.5).

## Module-off behaviour
Shopping off: the check-off mutation is rejected (X7), so the trigger does not learn a new position.

## Data and contract
Tables: list_item, shopping_list, store_item_order  Endpoints: none  Types: StoreItemOrder
Schema change: yes — supabase/migrations/20261108110900_X4_learn_order.sql  Contract change: no

## Tests required
Unit: n/a (database behaviour is asserted in pgTAP, TESTING §2)
Component: n/a (no UI)
Integration: supabase/tests/X4_learn.sql → AC1 mean updates; AC2 no store writes nothing; AC3 a non-transition does not learn
E2E: n/a (covered by the client journeys for this ID)
Offline test: n/a (server-side)

## Dependencies
Blocked by: SHOP-06-db  Blocks: SHOP-06-web, SHOP-06-ios, SHOP-06-android

## Skill
.claude/skills/sql-supabase/SKILL.md

## Open questions
none

# X5-db — Apply a pantry movement to the quantity

## Summary
A quantity change is a movement, so two offline edits add up instead of one wiping the other out.

## Spec references
SPEC §9 X5; §5; §4 PANT-03; TESTING §3 -db row

## Scope
In: Trigger pantry_movement_apply adds the delta to pantry_item.quantity. It is the only way quantity changes.
Out: Inserting the movement (PANT-03-api and the clients). Skipped layers: -api, -web, -ios, -android — X5 is the movement trigger. The stepper and the delta op are PANT-03.

## Acceptance criteria
- [ ] AC1 Given an item quantity of 4, when a movement of -1 is inserted, then the quantity is 3.
- [ ] AC2 Given two movements are inserted, when both commit, then the quantity includes both deltas.
- [ ] AC3 Given a client updates pantry_item.quantity directly, when the statement runs, then it is rejected.

## Design
Screen: n/a — this layer has no DESIGN screen.
Deviations from the plan: none

## Offline behaviour
Offline deltas become movements at sync, and this trigger applies them (SPEC §6.5 Pantry).

## Module-off behaviour
Pantry off: the movement mutation is rejected before insert (X7), so the trigger does not run for that edit.

## Data and contract
Tables: pantry_movement, pantry_item  Endpoints: none  Types: PantryMovement, PantryItem
Schema change: yes — supabase/migrations/20261108120900_X5_apply_movement.sql  Contract change: no

## Tests required
Unit: n/a (database behaviour is asserted in pgTAP, TESTING §2)
Component: n/a (no UI)
Integration: supabase/tests/X5_apply.sql → AC1 one delta; AC2 both deltas; AC3 direct quantity update rejected
E2E: n/a (covered by the client journeys for this ID)
Offline test: n/a (server-side)

## Dependencies
Blocked by: PANT-02-db  Blocks: PANT-03-api

## Skill
.claude/skills/sql-supabase/SKILL.md

## Open questions
none

# X6-api — Write an activity sentence for a visible action

## Summary
The household gets a sentence when someone checks an item off, adds or uses food, finishes a task, or joins.

## Spec references
SPEC §9 X6; §4 CORE-08; TESTING §3 -api row

## Scope
In: Sync and the accept-invite path write an activity row for those four actions. The summary is a sentence like Anna checked off Milk.
Out: The table (CORE-08-db). The feed (CORE-08 clients). Skipped layers: -db, -web, -ios, -android — the activity table is CORE-08-db. The feed is CORE-08 clients. X6 is the writer inside sync.

## Acceptance criteria
- [ ] AC1 Given a list item is checked off through sync, when the push commits, then an activity row exists whose summary names the actor and the item.
- [ ] AC2 Given a pantry movement with reason used or added is applied, when the push commits, then an activity row exists.
- [ ] AC3 Given a task is completed, when that write commits, then an activity row exists.
- [ ] AC4 Given a member joins, when accept-invite commits, then an activity row exists even though join is not a sync mutation.

## Design
Screen: n/a — this layer has no DESIGN screen.
Deviations from the plan: none

## Offline behaviour
The row is written on the server as the action is accepted. The feed can show it offline after the next pull.

## Module-off behaviour
An action X7 rejects does not write activity. Joining is core and is not module-gated.

## Data and contract
Tables: activity  Endpoints: POST /sync; POST /invites/{code}/accept  Types: Activity
Schema change: no  Contract change: no

## Tests required
Unit: api/test/X6-activity.test.ts → the summary builder for a check-off
Component: n/a (no UI)
Integration: api/test/X6-activity.int.test.ts → AC1 check-off; AC2 pantry use; AC3 task done; AC4 member joined.
E2E: n/a (client journeys cover the user path)
Offline test: n/a (server-side; queued client writes are covered by the client offline test)

## Dependencies
Blocked by: CORE-08-db, SHOP-08-api  Blocks: none

## Skill
.claude/skills/node-backend/SKILL.md

## Open questions
X6 says sync writes the member-joined row, and joining is POST /invites/{code}/accept. Proposed default: accept-invite writes that row in the API, and the other three are written by the sync applier.

# X7-api — Reject a disabled module's mutations

## Summary
Turning a module off stops that module's edits at the server without breaking the other modules.

## Spec references
SPEC §9 X7; §4 CORE-05; §6.3 module_disabled; TESTING §3 -api row, §3 cross-module row

## Scope
In: POST /sync rejects mutations whose table belongs to a disabled module with module_disabled, and still applies the rest of the batch.
Out: Storing the flags (CORE-05). Hiding tabs (X7 clients). The triggers' own flag checks are in X1, X3, and the notification jobs. Skipped layers: -db — the flags are CORE-05-db. X7 is the sync rejection and the tab shell.

## Acceptance criteria
- [ ] AC1 Given pantry is off, when a pantry_item mutation is pushed, then it is rejected with module_disabled.
- [ ] AC2 Given pantry is off and a list_item mutation is in the same batch, when the push runs, then the list item is applied.
- [ ] AC3 Given no JWT, when the push runs, then the response is 401.
- [ ] AC4 Given all three modules are on, when a pantry mutation is pushed, then it is not rejected for module_disabled.

## Design
Screen: n/a — this layer has no DESIGN screen.
Deviations from the plan: none

## Offline behaviour
The rejection is what makes an offline queue safe after someone else turned the module off.

## Module-off behaviour
Each disabled module rejects only its own tables. Core tables are not rejected by X7.

## Data and contract
Tables: household and the module tables in the sync allow-list  Endpoints: POST /sync  Types: SyncPushResponse
Schema change: no  Contract change: no

## Tests required
Unit: api/test/X7-module.test.ts → AC1 the rejection reason; AC2 the sibling mutation stays
Component: n/a (no UI)
Integration: api/test/X7-module.int.test.ts → AC1 pantry rejected; AC2 list item applied; AC3 401; AC4 allowed when the flag is on.
E2E: n/a (client journeys cover the user path)
Offline test: n/a (server-side; queued client writes are covered by the client offline test)

## Dependencies
Blocked by: CORE-05-db, SHOP-08-api  Blocks: X7-web, X7-ios, X7-android

## Skill
.claude/skills/node-backend/SKILL.md

## Open questions
none

# X7-web — Hide a module that is off

## Summary
When a module is off, its tab is gone and the rest of the app still opens.

## Spec references
SPEC §9 X7; DESIGN A6 Tabs; TESTING §8 E2E-CORE-3

## Scope
In: The tab shell hides Shopping, Pantry, or Maintenance when that flag is off, and falls back to Home when all are off.
Out: The settings switches (CORE-05). The server rejection (X7-api). Skipped layers: -db — the flags are CORE-05-db. X7 is the sync rejection and the tab shell.

## Acceptance criteria
- [ ] AC1 Given pantry is off, when the shell renders, then there is no Pantry tab and opening a pantry route shows Home instead of an error.
- [ ] AC2 Given only shopping is on, when the shell renders, then Shopping is the start tab.
- [ ] AC3 Given all three modules are off, when the shell renders, then the only tab is Home.
- [ ] AC4 Given I turn a module back on, when the flag arrives, then its tab returns without restarting the app.

## Design
Screen: DESIGN A6 Tabs — States: one module, several modules, all off. Interactions: the start tab follows the flags.
Deviations from the plan: none

## Offline behaviour
Flags already pulled are honoured offline. Changing a flag needs a network (CORE-05).

## Module-off behaviour
The §9 matrix is the picture of what remains: shopping only has no pantry badges, pantry only has a low badge and no shopping line, maintenance only hides Send to list. Those badges and buttons are SHOP-16, PANT-04, and MAINT-16. This story only removes the tab and must not error.

## Data and contract
Tables: household  Endpoints: none  Types: Household
Schema change: no  Contract change: no

## Tests required
Unit: web/src/core/Tabs.test.ts → AC1 a pantry route redirects home when the flag is off; AC2 the single enabled module is the start tab; AC3 all off lands on Home
Component: web/src/core/Tabs.test.tsx → AC4 the tab returns when the flag flips. States: one module, several, all off.
Integration: n/a (no API handler in this story)
E2E: E2E-CORE-3 web/e2e/Tabs.spec.ts
Offline test: n/a (core shell)

## Dependencies
Blocked by: CORE-05-web, X7-api  Blocks: none

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
none

# X7-ios — Hide a module that is off

## Summary
When a module is off, its tab is gone and the rest of the app still opens.

## Spec references
SPEC §9 X7; DESIGN A6 Tabs; TESTING §8 E2E-CORE-3

## Scope
In: The tab shell hides Shopping, Pantry, or Maintenance when that flag is off, and falls back to Home when all are off.
Out: The settings switches (CORE-05). The server rejection (X7-api). Skipped layers: -db — the flags are CORE-05-db. X7 is the sync rejection and the tab shell.

## Acceptance criteria
- [ ] AC1 Given pantry is off, when the shell renders, then there is no Pantry tab and opening a pantry route shows Home instead of an error.
- [ ] AC2 Given only shopping is on, when the shell renders, then Shopping is the start tab.
- [ ] AC3 Given all three modules are off, when the shell renders, then the only tab is Home.
- [ ] AC4 Given I turn a module back on, when the flag arrives, then its tab returns without restarting the app.

## Design
Screen: DESIGN A6 Tabs — States: one module, several modules, all off. Interactions: the start tab follows the flags.
Deviations from the plan: none

## Offline behaviour
Flags already pulled are honoured offline. Changing a flag needs a network (CORE-05).

## Module-off behaviour
The §9 matrix is the picture of what remains: shopping only has no pantry badges, pantry only has a low badge and no shopping line, maintenance only hides Send to list. Those badges and buttons are SHOP-16, PANT-04, and MAINT-16. This story only removes the tab and must not error.

## Data and contract
Tables: household  Endpoints: none  Types: Household
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/core/Tests/TabsTests.swift → AC1 a pantry route redirects home when the flag is off; AC2 the single enabled module is the start tab; AC3 all off lands on Home
Component: apps/ios/AbodeNestlyTests/TabsViewTests.swift → AC4 the tab returns when the flag flips. States: one module, several, all off.
Integration: n/a (no API handler in this story)
E2E: E2E-CORE-3 apps/ios/AbodeNestlyUITests/TabsUITests.swift
Offline test: n/a (core shell)

## Dependencies
Blocked by: CORE-05-ios, X7-api  Blocks: none

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
none

# X7-android — Hide a module that is off

## Summary
When a module is off, its tab is gone and the rest of the app still opens.

## Spec references
SPEC §9 X7; DESIGN A6 Tabs; TESTING §8 E2E-CORE-3

## Scope
In: The tab shell hides Shopping, Pantry, or Maintenance when that flag is off, and falls back to Home when all are off.
Out: The settings switches (CORE-05). The server rejection (X7-api). Skipped layers: -db — the flags are CORE-05-db. X7 is the sync rejection and the tab shell.

## Acceptance criteria
- [ ] AC1 Given pantry is off, when the shell renders, then there is no Pantry tab and opening a pantry route shows Home instead of an error.
- [ ] AC2 Given only shopping is on, when the shell renders, then Shopping is the start tab.
- [ ] AC3 Given all three modules are off, when the shell renders, then the only tab is Home.
- [ ] AC4 Given I turn a module back on, when the flag arrives, then its tab returns without restarting the app.

## Design
Screen: DESIGN A6 Tabs — States: one module, several modules, all off. Interactions: the start tab follows the flags.
Deviations from the plan: none

## Offline behaviour
Flags already pulled are honoured offline. Changing a flag needs a network (CORE-05).

## Module-off behaviour
The §9 matrix is the picture of what remains: shopping only has no pantry badges, pantry only has a low badge and no shopping line, maintenance only hides Send to list. Those badges and buttons are SHOP-16, PANT-04, and MAINT-16. This story only removes the tab and must not error.

## Data and contract
Tables: household  Endpoints: none  Types: Household
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/core/src/test/java/app/abodenestly/core/TabsTest.kt → AC1 a pantry route redirects home when the flag is off; AC2 the single enabled module is the start tab; AC3 all off lands on Home
Component: apps/android/core/src/test/java/app/abodenestly/core/TabsComposeTest.kt → AC4 the tab returns when the flag flips. States: one module, several, all off.
Integration: n/a (no API handler in this story)
E2E: E2E-CORE-3 apps/android/core/src/androidTest/java/app/abodenestly/core/TabsE2ETest.kt
Offline test: n/a (core shell)

## Dependencies
Blocked by: CORE-05-android, X7-api  Blocks: none

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
none
