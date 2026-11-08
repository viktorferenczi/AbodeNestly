# Maintenance stories

One story per feature ID per layer that has work. Each body is `docs/STORY-TEMPLATE.md` verbatim. Order inside an ID is db, api, web, ios, android.

# MAINT-01-db — House profile

## Summary
The household can store the house's name, address, year, and notes.

## Spec references
SPEC §4 MAINT-01; §8 House; DESIGN E12; TESTING §3 -db row

## Scope
In: house rows with name, address, build year, and notes.
Out: The house screen (MAINT-01 clients). Skipped layers: -api — house rows sync through the outbox. §7 has no house endpoint.

## Acceptance criteria
- [ ] AC1 Given a member, when they insert a house, then the row is stored.
- [ ] AC2 Given build year is null, when they insert, then the row is stored.
- [ ] AC3 Given a non-member, when they select the house, then they see nothing.

## Design
Screen: n/a — this layer has no DESIGN screen.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Maintenance: this record is readable offline. DESIGN marks the screen fully offline. Proposed default, recorded on the story: edits queue in the outbox the same way tasks do.

## Module-off behaviour
Maintenance off (X7): the Maintenance tab is hidden and a maintenance-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: house  Endpoints: none  Types: House
Schema change: yes — supabase/migrations/20261108130000_MAINT-01_house.sql  Contract change: no

## Tests required
Unit: n/a (database behaviour is asserted in pgTAP, TESTING §2)
Component: n/a (no UI)
Integration: supabase/tests/MAINT-01_house.sql → AC1 insert; AC2 null build year; AC3 non-member denied
E2E: n/a (covered by the client journeys for this ID)
Offline test: n/a (server-side)

## Dependencies
Blocked by: CORE-02-db  Blocks: MAINT-01-web, MAINT-01-ios, MAINT-01-android, MAINT-02-db, MAINT-03-db

## Skill
.claude/skills/sql-supabase/SKILL.md

## Open questions
SPEC §6.5 does not list house edits as offline writes, and DESIGN E12 says the screen is fully offline. Proposed default: house edits queue in the outbox.

# MAINT-01-web — House profile

## Summary
A person can record the house's name, address, build year, and notes.

## Spec references
SPEC §4 MAINT-01; DESIGN E12; TESTING §3 client rows

## Scope
In: The house screen (E12), including a second house.
Out: Assets that point at a house (MAINT-02). Skipped layers: -api — house rows sync through the outbox. §7 has no house endpoint.

## Acceptance criteria
- [ ] AC1 Given no house yet, when I open the screen, then the form is empty and I can save the first house.
- [ ] AC2 Given I set name, address, build year, and notes, when I save, then those values are on the house.
- [ ] AC3 Given two houses, when I open the screen, then both are listed and I can add another.
- [ ] AC4 Given the device is offline, when I edit the name, then the edit is kept and queued.

## Design
Screen: DESIGN E12 — States: populated. Interactions: edit the fields; add a house.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Maintenance: this record is readable offline. DESIGN marks the screen fully offline. Proposed default, recorded on the story: edits queue in the outbox the same way tasks do.

## Module-off behaviour
Maintenance off (X7): the Maintenance tab is hidden and a maintenance-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: house  Endpoints: none  Types: House
Schema change: no  Contract change: no

## Tests required
Unit: web/src/maintenance/House.test.ts → AC4 offline enqueues one upsert
Component: web/src/maintenance/House.test.tsx → AC1 empty form, AC2 saved fields, AC3 two houses. Interaction: save and add.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: n/a (TESTING §3 asks for the offline pattern on shopping and pantry)

## Dependencies
Blocked by: MAINT-01-db  Blocks: none

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
Same offline conflict as MAINT-01-db. Proposed default: edits queue in the outbox.

# MAINT-01-ios — House profile

## Summary
A person can record the house's name, address, build year, and notes.

## Spec references
SPEC §4 MAINT-01; DESIGN E12; TESTING §3 client rows

## Scope
In: The house screen (E12), including a second house.
Out: Assets that point at a house (MAINT-02). Skipped layers: -api — house rows sync through the outbox. §7 has no house endpoint.

## Acceptance criteria
- [ ] AC1 Given no house yet, when I open the screen, then the form is empty and I can save the first house.
- [ ] AC2 Given I set name, address, build year, and notes, when I save, then those values are on the house.
- [ ] AC3 Given two houses, when I open the screen, then both are listed and I can add another.
- [ ] AC4 Given the device is offline, when I edit the name, then the edit is kept and queued.

## Design
Screen: DESIGN E12 — States: populated. Interactions: edit the fields; add a house.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Maintenance: this record is readable offline. DESIGN marks the screen fully offline. Proposed default, recorded on the story: edits queue in the outbox the same way tasks do.

## Module-off behaviour
Maintenance off (X7): the Maintenance tab is hidden and a maintenance-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: house  Endpoints: none  Types: House
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/maintenance/Tests/HouseTests.swift → AC4 offline enqueues one upsert
Component: apps/ios/AbodeNestlyTests/HouseViewTests.swift → AC1 empty form, AC2 saved fields, AC3 two houses. Interaction: save and add.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: n/a (TESTING §3 asks for the offline pattern on shopping and pantry)

## Dependencies
Blocked by: MAINT-01-db  Blocks: none

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
Same offline conflict as MAINT-01-db. Proposed default: edits queue in the outbox.

# MAINT-01-android — House profile

## Summary
A person can record the house's name, address, build year, and notes.

## Spec references
SPEC §4 MAINT-01; DESIGN E12; TESTING §3 client rows

## Scope
In: The house screen (E12), including a second house.
Out: Assets that point at a house (MAINT-02). Skipped layers: -api — house rows sync through the outbox. §7 has no house endpoint.

## Acceptance criteria
- [ ] AC1 Given no house yet, when I open the screen, then the form is empty and I can save the first house.
- [ ] AC2 Given I set name, address, build year, and notes, when I save, then those values are on the house.
- [ ] AC3 Given two houses, when I open the screen, then both are listed and I can add another.
- [ ] AC4 Given the device is offline, when I edit the name, then the edit is kept and queued.

## Design
Screen: DESIGN E12 — States: populated. Interactions: edit the fields; add a house.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Maintenance: this record is readable offline. DESIGN marks the screen fully offline. Proposed default, recorded on the story: edits queue in the outbox the same way tasks do.

## Module-off behaviour
Maintenance off (X7): the Maintenance tab is hidden and a maintenance-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: house  Endpoints: none  Types: House
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/maintenance/src/test/java/app/abodenestly/maintenance/HouseTest.kt → AC4 offline enqueues one upsert
Component: apps/android/maintenance/src/test/java/app/abodenestly/maintenance/HouseComposeTest.kt → AC1 empty form, AC2 saved fields, AC3 two houses. Interaction: save and add.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: n/a (TESTING §3 asks for the offline pattern on shopping and pantry)

## Dependencies
Blocked by: MAINT-01-db  Blocks: none

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
Same offline conflict as MAINT-01-db. Proposed default: edits queue in the outbox.

# MAINT-02-db — Assets

## Summary
The household can keep a record of a thing it owns, including what it cost and when the warranty ends.

## Spec references
SPEC §4 MAINT-02; §8 Asset; TESTING §3 -db row

## Scope
In: asset rows for kind, brand, model, serial, room, purchase date, purchase price, warranty end, notes, and qr_code.
Out: The asset screens (MAINT-02 clients). Attachments (CORE-11). The QR sheet (MAINT-14). Skipped layers: -api — asset rows sync through the outbox. Files use CORE-11.

## Acceptance criteria
- [ ] AC1 Given a member, when they insert an asset, then the row is stored and qr_code is generated.
- [ ] AC2 Given purchase price is set, when it is read, then it is numeric.
- [ ] AC3 Given a non-member, when they select the asset, then they see nothing.

## Design
Screen: n/a — this layer has no DESIGN screen.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Maintenance: this record is readable offline. DESIGN marks the screen fully offline. Proposed default, recorded on the story: edits queue in the outbox the same way tasks do.

## Module-off behaviour
Maintenance off (X7): the Maintenance tab is hidden and a maintenance-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: asset  Endpoints: none  Types: Asset
Schema change: yes — supabase/migrations/20261108130100_MAINT-02_asset.sql  Contract change: no

## Tests required
Unit: n/a (database behaviour is asserted in pgTAP, TESTING §2)
Component: n/a (no UI)
Integration: supabase/tests/MAINT-02_asset.sql → AC1 qr_code generated; AC2 numeric price; AC3 non-member denied
E2E: n/a (covered by the client journeys for this ID)
Offline test: n/a (server-side)

## Dependencies
Blocked by: MAINT-01-db  Blocks: MAINT-02-web, MAINT-02-ios, MAINT-02-android, MAINT-11-db

## Skill
.claude/skills/sql-supabase/SKILL.md

## Open questions
SPEC §6.5 does not list asset edits as offline writes, and DESIGN E8 says the screen is fully offline. Proposed default: asset edits queue in the outbox, and attachment bytes still queue until online.

# MAINT-02-web — Assets

## Summary
A person can record a thing the house owns, including its warranty, cost, and files.

## Spec references
SPEC §4 MAINT-02; DESIGN E8, E9, E11; TESTING §3 client rows

## Scope
In: The asset list (E8), detail (E9), and edit sheet (E11).
Out: QR labels (MAINT-14). Cost totals on the detail screen that are MAINT-10. The uploader (CORE-11). Skipped layers: -api — asset rows sync through the outbox. Files use CORE-11.

## Acceptance criteria
- [ ] AC1 Given there are no assets, when I open Assets, then I see the empty state.
- [ ] AC2 Given I save kind, brand, model, serial, room, purchase date, price, warranty end, and notes, when I reopen the asset, then those fields are shown.
- [ ] AC3 Given the warranty ends within 30 days, when I view the list, then the caption uses the warn colour and the text still says when it ends.
- [ ] AC4 Given I attach a manual, when the upload finishes, then it is listed on the asset.
- [ ] AC5 Given the device is offline, when I edit the serial, then the edit is queued and a new file upload waits until online.

## Design
Screen: DESIGN E8 — States: empty, populated. Interactions: add, scan QR. DESIGN E9 — States: populated, warranty-expiring, warranty-expired, no-tasks. Interactions: open QR, edit. DESIGN E11 — the edit sheet.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Maintenance: this record is readable offline. DESIGN marks the screen fully offline. Proposed default, recorded on the story: edits queue in the outbox the same way tasks do.

## Module-off behaviour
Maintenance off (X7): the Maintenance tab is hidden and a maintenance-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: asset, attachment  Endpoints: POST /attachments/sign  Types: Asset, Attachment
Schema change: no  Contract change: no

## Tests required
Unit: web/src/maintenance/Assets.test.ts → AC5 offline edit enqueues the asset and does not call sign for the file
Component: web/src/maintenance/Assets.test.tsx → AC1 empty, AC2 populated fields, AC3 warranty caption, AC4 attachment. States warranty-expiring, warranty-expired, no-tasks.
Integration: n/a (no API handler in this story)
E2E: E2E-MAINT-1 web/e2e/Assets.spec.ts
Offline test: n/a (TESTING §3 asks for the offline pattern on shopping and pantry)

## Dependencies
Blocked by: MAINT-02-db, CORE-11-web  Blocks: MAINT-11-web, MAINT-14-web

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
none

# MAINT-02-ios — Assets

## Summary
A person can record a thing the house owns, including its warranty, cost, and files.

## Spec references
SPEC §4 MAINT-02; DESIGN E8, E9, E11; TESTING §3 client rows

## Scope
In: The asset list (E8), detail (E9), and edit sheet (E11).
Out: QR labels (MAINT-14). Cost totals on the detail screen that are MAINT-10. The uploader (CORE-11). Skipped layers: -api — asset rows sync through the outbox. Files use CORE-11.

## Acceptance criteria
- [ ] AC1 Given there are no assets, when I open Assets, then I see the empty state.
- [ ] AC2 Given I save kind, brand, model, serial, room, purchase date, price, warranty end, and notes, when I reopen the asset, then those fields are shown.
- [ ] AC3 Given the warranty ends within 30 days, when I view the list, then the caption uses the warn colour and the text still says when it ends.
- [ ] AC4 Given I attach a manual, when the upload finishes, then it is listed on the asset.
- [ ] AC5 Given the device is offline, when I edit the serial, then the edit is queued and a new file upload waits until online.

## Design
Screen: DESIGN E8 — States: empty, populated. Interactions: add, scan QR. DESIGN E9 — States: populated, warranty-expiring, warranty-expired, no-tasks. Interactions: open QR, edit. DESIGN E11 — the edit sheet.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Maintenance: this record is readable offline. DESIGN marks the screen fully offline. Proposed default, recorded on the story: edits queue in the outbox the same way tasks do.

## Module-off behaviour
Maintenance off (X7): the Maintenance tab is hidden and a maintenance-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: asset, attachment  Endpoints: POST /attachments/sign  Types: Asset, Attachment
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/maintenance/Tests/AssetsTests.swift → AC5 offline edit enqueues the asset and does not call sign for the file
Component: apps/ios/AbodeNestlyTests/AssetsViewTests.swift → AC1 empty, AC2 populated fields, AC3 warranty caption, AC4 attachment. States warranty-expiring, warranty-expired, no-tasks.
Integration: n/a (no API handler in this story)
E2E: E2E-MAINT-1 apps/ios/AbodeNestlyUITests/AssetsUITests.swift
Offline test: n/a (TESTING §3 asks for the offline pattern on shopping and pantry)

## Dependencies
Blocked by: MAINT-02-db, CORE-11-ios  Blocks: MAINT-11-ios, MAINT-14-ios

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
none

# MAINT-02-android — Assets

## Summary
A person can record a thing the house owns, including its warranty, cost, and files.

## Spec references
SPEC §4 MAINT-02; DESIGN E8, E9, E11; TESTING §3 client rows

## Scope
In: The asset list (E8), detail (E9), and edit sheet (E11).
Out: QR labels (MAINT-14). Cost totals on the detail screen that are MAINT-10. The uploader (CORE-11). Skipped layers: -api — asset rows sync through the outbox. Files use CORE-11.

## Acceptance criteria
- [ ] AC1 Given there are no assets, when I open Assets, then I see the empty state.
- [ ] AC2 Given I save kind, brand, model, serial, room, purchase date, price, warranty end, and notes, when I reopen the asset, then those fields are shown.
- [ ] AC3 Given the warranty ends within 30 days, when I view the list, then the caption uses the warn colour and the text still says when it ends.
- [ ] AC4 Given I attach a manual, when the upload finishes, then it is listed on the asset.
- [ ] AC5 Given the device is offline, when I edit the serial, then the edit is queued and a new file upload waits until online.

## Design
Screen: DESIGN E8 — States: empty, populated. Interactions: add, scan QR. DESIGN E9 — States: populated, warranty-expiring, warranty-expired, no-tasks. Interactions: open QR, edit. DESIGN E11 — the edit sheet.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Maintenance: this record is readable offline. DESIGN marks the screen fully offline. Proposed default, recorded on the story: edits queue in the outbox the same way tasks do.

## Module-off behaviour
Maintenance off (X7): the Maintenance tab is hidden and a maintenance-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: asset, attachment  Endpoints: POST /attachments/sign  Types: Asset, Attachment
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/maintenance/src/test/java/app/abodenestly/maintenance/AssetsTest.kt → AC5 offline edit enqueues the asset and does not call sign for the file
Component: apps/android/maintenance/src/test/java/app/abodenestly/maintenance/AssetsComposeTest.kt → AC1 empty, AC2 populated fields, AC3 warranty caption, AC4 attachment. States warranty-expiring, warranty-expired, no-tasks.
Integration: n/a (no API handler in this story)
E2E: E2E-MAINT-1 apps/android/maintenance/src/androidTest/java/app/abodenestly/maintenance/AssetsE2ETest.kt
Offline test: n/a (TESTING §3 asks for the offline pattern on shopping and pantry)

## Dependencies
Blocked by: MAINT-02-db, CORE-11-android  Blocks: MAINT-11-android, MAINT-14-android

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
none

# MAINT-03-db — Recurring tasks

## Summary
A job can remember the rule that says when it happens again.

## Spec references
SPEC §4 MAINT-03; §8 MaintTask; TESTING §3 -db row

## Scope
In: maint_task with rrule and next_due. One-off tasks keep rrule null and are MAINT-04 on the same table.
Out: The task screens (MAINT-03 clients). The roll on completion (MAINT-05-api). Skipped layers: -api — rolling next_due on completion is MAINT-05-api, which calls the rrule library. This story stores the schedule and edits it.

## Acceptance criteria
- [ ] AC1 Given a member, when they insert a task with an RRULE and a next due date, then both are stored.
- [ ] AC2 Given next_due is a date, when it is read, then it has no time component.
- [ ] AC3 Given a non-member, when they select the task, then they see nothing.

## Design
Screen: n/a — this layer has no DESIGN screen.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Maintenance: tasks are readable offline and creating or editing a task is queued in the outbox.

## Module-off behaviour
Maintenance off (X7): the Maintenance tab is hidden and a maintenance-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: maint_task  Endpoints: none  Types: MaintTask
Schema change: yes — supabase/migrations/20261108130200_MAINT-03_task.sql  Contract change: no

## Tests required
Unit: n/a (database behaviour is asserted in pgTAP, TESTING §2)
Component: n/a (no UI)
Integration: supabase/tests/MAINT-03_task.sql → AC1 rrule and next_due stored; AC2 date only; AC3 non-member denied
E2E: n/a (covered by the client journeys for this ID)
Offline test: n/a (server-side)

## Dependencies
Blocked by: MAINT-01-db  Blocks: MAINT-03-web, MAINT-03-ios, MAINT-03-android, MAINT-05-api, MAINT-05-db, MAINT-08-db, MAINT-12-db, MAINT-16-db

## Skill
.claude/skills/sql-supabase/SKILL.md

## Open questions
none

# MAINT-03-web — Recurring tasks

## Summary
A person can set a job that repeats and always see the next time it is due.

## Spec references
SPEC §4 MAINT-03; DESIGN E2, E3, E5; TESTING §8 E2E-MAINT-1

## Scope
In: Creating a recurring task with the RRulePicker, and showing the schedule line.
Out: One-off tasks (MAINT-04). Mark done, which rolls the date (MAINT-05). Assignment (MAINT-15). Skipped layers: -api — rolling next_due on completion is MAINT-05-api, which calls the rrule library. This story stores the schedule and edits it.

## Acceptance criteria
- [ ] AC1 Given I pick Monthly, when I save, then the task has an RRULE and the schedule line shows the next date.
- [ ] AC2 Given I pick Custom, when I set an interval and a unit, then the RRULE matches that interval.
- [ ] AC3 Given the task exists, when I open it, then I see Every … and the next date.
- [ ] AC4 Given the device is offline, when I create the task, then it is queued and the next date is shown from the local rule.

## Design
Screen: DESIGN E5 and E3 — States: E5 new and edit; E3 recurring. Interactions: RRulePicker presets and Custom; save.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Maintenance: tasks are readable offline and creating or editing a task is queued in the outbox.

## Module-off behaviour
Maintenance off (X7): the Maintenance tab is hidden and a maintenance-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: maint_task  Endpoints: none  Types: MaintTask
Schema change: no  Contract change: no

## Tests required
Unit: web/src/maintenance/Tasks.test.ts → AC2 the custom interval becomes the RRULE; AC4 offline enqueues the task
Component: web/src/maintenance/Tasks.test.tsx → AC1 Monthly preset, AC3 schedule line. States new, edit, recurring. Interactions: each preset and Custom.
Integration: n/a (no API handler in this story)
E2E: E2E-MAINT-1 web/e2e/Tasks.spec.ts
Offline test: n/a (TESTING §3 asks for the offline pattern on shopping and pantry)

## Dependencies
Blocked by: MAINT-03-db  Blocks: MAINT-04-web, MAINT-05-web, MAINT-07-web, MAINT-15-web

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
none

# MAINT-03-ios — Recurring tasks

## Summary
A person can set a job that repeats and always see the next time it is due.

## Spec references
SPEC §4 MAINT-03; DESIGN E2, E3, E5; TESTING §8 E2E-MAINT-1

## Scope
In: Creating a recurring task with the RRulePicker, and showing the schedule line.
Out: One-off tasks (MAINT-04). Mark done, which rolls the date (MAINT-05). Assignment (MAINT-15). Skipped layers: -api — rolling next_due on completion is MAINT-05-api, which calls the rrule library. This story stores the schedule and edits it.

## Acceptance criteria
- [ ] AC1 Given I pick Monthly, when I save, then the task has an RRULE and the schedule line shows the next date.
- [ ] AC2 Given I pick Custom, when I set an interval and a unit, then the RRULE matches that interval.
- [ ] AC3 Given the task exists, when I open it, then I see Every … and the next date.
- [ ] AC4 Given the device is offline, when I create the task, then it is queued and the next date is shown from the local rule.

## Design
Screen: DESIGN E5 and E3 — States: E5 new and edit; E3 recurring. Interactions: RRulePicker presets and Custom; save.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Maintenance: tasks are readable offline and creating or editing a task is queued in the outbox.

## Module-off behaviour
Maintenance off (X7): the Maintenance tab is hidden and a maintenance-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: maint_task  Endpoints: none  Types: MaintTask
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/maintenance/Tests/TasksTests.swift → AC2 the custom interval becomes the RRULE; AC4 offline enqueues the task
Component: apps/ios/AbodeNestlyTests/TasksViewTests.swift → AC1 Monthly preset, AC3 schedule line. States new, edit, recurring. Interactions: each preset and Custom.
Integration: n/a (no API handler in this story)
E2E: E2E-MAINT-1 apps/ios/AbodeNestlyUITests/TasksUITests.swift
Offline test: n/a (TESTING §3 asks for the offline pattern on shopping and pantry)

## Dependencies
Blocked by: MAINT-03-db  Blocks: MAINT-04-ios, MAINT-05-ios, MAINT-07-ios, MAINT-15-ios

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
none

# MAINT-03-android — Recurring tasks

## Summary
A person can set a job that repeats and always see the next time it is due.

## Spec references
SPEC §4 MAINT-03; DESIGN E2, E3, E5; TESTING §8 E2E-MAINT-1

## Scope
In: Creating a recurring task with the RRulePicker, and showing the schedule line.
Out: One-off tasks (MAINT-04). Mark done, which rolls the date (MAINT-05). Assignment (MAINT-15). Skipped layers: -api — rolling next_due on completion is MAINT-05-api, which calls the rrule library. This story stores the schedule and edits it.

## Acceptance criteria
- [ ] AC1 Given I pick Monthly, when I save, then the task has an RRULE and the schedule line shows the next date.
- [ ] AC2 Given I pick Custom, when I set an interval and a unit, then the RRULE matches that interval.
- [ ] AC3 Given the task exists, when I open it, then I see Every … and the next date.
- [ ] AC4 Given the device is offline, when I create the task, then it is queued and the next date is shown from the local rule.

## Design
Screen: DESIGN E5 and E3 — States: E5 new and edit; E3 recurring. Interactions: RRulePicker presets and Custom; save.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Maintenance: tasks are readable offline and creating or editing a task is queued in the outbox.

## Module-off behaviour
Maintenance off (X7): the Maintenance tab is hidden and a maintenance-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: maint_task  Endpoints: none  Types: MaintTask
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/maintenance/src/test/java/app/abodenestly/maintenance/TasksTest.kt → AC2 the custom interval becomes the RRULE; AC4 offline enqueues the task
Component: apps/android/maintenance/src/test/java/app/abodenestly/maintenance/TasksComposeTest.kt → AC1 Monthly preset, AC3 schedule line. States new, edit, recurring. Interactions: each preset and Custom.
Integration: n/a (no API handler in this story)
E2E: E2E-MAINT-1 apps/android/maintenance/src/androidTest/java/app/abodenestly/maintenance/TasksE2ETest.kt
Offline test: n/a (TESTING §3 asks for the offline pattern on shopping and pantry)

## Dependencies
Blocked by: MAINT-03-db  Blocks: MAINT-04-android, MAINT-05-android, MAINT-07-android, MAINT-15-android

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
none

# MAINT-04-web — One-off tasks

## Summary
A person can add a job that happens once and does not reschedule itself.

## Spec references
SPEC §4 MAINT-04; DESIGN E5; TESTING §3 client rows

## Scope
In: The One-off choice on the task sheet, with a single date.
Out: Recurring tasks (MAINT-03). Mark done (MAINT-05). Skipped layers: -db, -api — a one-off task is a maint_task with rrule null, on the table from MAINT-03-db. Completion does not roll.

## Acceptance criteria
- [ ] AC1 Given I choose One-off and a date, when I save, then rrule is null and next_due is that date.
- [ ] AC2 Given the task is one-off, when I open it, then the one-off state is shown and there is no repeating schedule line.
- [ ] AC3 Given I later mark it done, when the completion returns, then next_due is not moved forward.
- [ ] AC4 Given the device is offline, when I create it, then it is queued.

## Design
Screen: DESIGN E5 and E3 — States: E5 new; E3 one-off. Interactions: choose One-off and a date.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Maintenance: tasks are readable offline and creating or editing a task is queued in the outbox.

## Module-off behaviour
Maintenance off (X7): the Maintenance tab is hidden and a maintenance-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: maint_task  Endpoints: none  Types: MaintTask
Schema change: no  Contract change: no

## Tests required
Unit: web/src/maintenance/OneOff.test.ts → AC1 rrule is null; AC3 completion does not change next_due
Component: web/src/maintenance/OneOff.test.tsx → AC2 one-off state, AC4 offline. Interactions: One-off and the date picker.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: n/a (TESTING §3 asks for the offline pattern on shopping and pantry)

## Dependencies
Blocked by: MAINT-03-web  Blocks: MAINT-07-web

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
none

# MAINT-04-ios — One-off tasks

## Summary
A person can add a job that happens once and does not reschedule itself.

## Spec references
SPEC §4 MAINT-04; DESIGN E5; TESTING §3 client rows

## Scope
In: The One-off choice on the task sheet, with a single date.
Out: Recurring tasks (MAINT-03). Mark done (MAINT-05). Skipped layers: -db, -api — a one-off task is a maint_task with rrule null, on the table from MAINT-03-db. Completion does not roll.

## Acceptance criteria
- [ ] AC1 Given I choose One-off and a date, when I save, then rrule is null and next_due is that date.
- [ ] AC2 Given the task is one-off, when I open it, then the one-off state is shown and there is no repeating schedule line.
- [ ] AC3 Given I later mark it done, when the completion returns, then next_due is not moved forward.
- [ ] AC4 Given the device is offline, when I create it, then it is queued.

## Design
Screen: DESIGN E5 and E3 — States: E5 new; E3 one-off. Interactions: choose One-off and a date.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Maintenance: tasks are readable offline and creating or editing a task is queued in the outbox.

## Module-off behaviour
Maintenance off (X7): the Maintenance tab is hidden and a maintenance-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: maint_task  Endpoints: none  Types: MaintTask
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/maintenance/Tests/OneOffTests.swift → AC1 rrule is null; AC3 completion does not change next_due
Component: apps/ios/AbodeNestlyTests/OneOffViewTests.swift → AC2 one-off state, AC4 offline. Interactions: One-off and the date picker.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: n/a (TESTING §3 asks for the offline pattern on shopping and pantry)

## Dependencies
Blocked by: MAINT-03-ios  Blocks: MAINT-07-ios

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
none

# MAINT-04-android — One-off tasks

## Summary
A person can add a job that happens once and does not reschedule itself.

## Spec references
SPEC §4 MAINT-04; DESIGN E5; TESTING §3 client rows

## Scope
In: The One-off choice on the task sheet, with a single date.
Out: Recurring tasks (MAINT-03). Mark done (MAINT-05). Skipped layers: -db, -api — a one-off task is a maint_task with rrule null, on the table from MAINT-03-db. Completion does not roll.

## Acceptance criteria
- [ ] AC1 Given I choose One-off and a date, when I save, then rrule is null and next_due is that date.
- [ ] AC2 Given the task is one-off, when I open it, then the one-off state is shown and there is no repeating schedule line.
- [ ] AC3 Given I later mark it done, when the completion returns, then next_due is not moved forward.
- [ ] AC4 Given the device is offline, when I create it, then it is queued.

## Design
Screen: DESIGN E5 and E3 — States: E5 new; E3 one-off. Interactions: choose One-off and a date.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Maintenance: tasks are readable offline and creating or editing a task is queued in the outbox.

## Module-off behaviour
Maintenance off (X7): the Maintenance tab is hidden and a maintenance-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: maint_task  Endpoints: none  Types: MaintTask
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/maintenance/src/test/java/app/abodenestly/maintenance/OneOffTest.kt → AC1 rrule is null; AC3 completion does not change next_due
Component: apps/android/maintenance/src/test/java/app/abodenestly/maintenance/OneOffComposeTest.kt → AC2 one-off state, AC4 offline. Interactions: One-off and the date picker.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: n/a (TESTING §3 asks for the offline pattern on shopping and pantry)

## Dependencies
Blocked by: MAINT-03-android  Blocks: MAINT-07-android

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
none

# MAINT-05-api — Mark a task done

## Summary
Marking a job done records the log entry and, when the job repeats, moves the next due date.

## Spec references
SPEC §4 MAINT-05; §7 POST /tasks/{id}/done; TESTING §3 -api row, §8 E2E-MAINT-1

## Scope
In: POST /tasks/{id}/done writes the log and rolls next_due for a recurring task.
Out: The sheet (MAINT-05 clients). The offline path queues the same two writes and this handler remains the online path.

## Acceptance criteria
- [ ] AC1 Given a recurring task, when POST /tasks/{id}/done succeeds, then a log row exists and next_due has moved forward by the RRULE.
- [ ] AC2 Given a one-off task, when POST succeeds, then a log row exists and next_due is not moved.
- [ ] AC3 Given no JWT, when POST is called, then the response is 401.
- [ ] AC4 Given a done date that is not a date, when POST is called, then the response is 400 and no log is written.

## Design
Screen: n/a — this layer has no DESIGN screen.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 allows the log and the task edit offline. This route is the online call. The client's queued writes must produce the same roll when they sync.

## Module-off behaviour
Maintenance off: the call is rejected with module_disabled.

## Data and contract
Tables: maint_log, maint_task  Endpoints: POST /tasks/{id}/done  Types: MaintLog, MaintTask, MaintLogInput
Schema change: no  Contract change: yes

## Tests required
Unit: api/test/MAINT-05-done.test.ts → AC1 the next date for a monthly rule; AC2 one-off stays put
Component: n/a (no UI)
Integration: api/test/MAINT-05-done.int.test.ts → AC1 log and rolled date; AC3 401; AC4 400.
E2E: n/a (client journeys cover the user path)
Offline test: n/a (server-side; queued client writes are covered by the client offline test)

## Dependencies
Blocked by: MAINT-05-db, MAINT-03-db  Blocks: MAINT-05-web, MAINT-05-ios, MAINT-05-android

## Skill
.claude/skills/node-backend/SKILL.md

## Open questions
SPEC §6.5 says log entries and tasks are writable offline, and §7 completes a task through this route. Proposed default: an offline completion queues the log and the task update, and the sync applier calls the same roll this route uses. The route stays for an online save.

# MAINT-05-db — Log rows written when a task is done

## Summary
The record of a finished job has a date, a person, a contractor, a cost, and notes.

## Spec references
SPEC §4 MAINT-05; §8 MaintLog; TESTING §3 -db row

## Scope
In: maint_log columns. Cost is numeric. done_at is a date.
Out: The POST handler (MAINT-05-api) and later edits (MAINT-06).

## Acceptance criteria
- [ ] AC1 Given a member, when they insert a log with a cost, then the numeric cost is stored.
- [ ] AC2 Given done_at includes a time, when they insert, then the value is rejected.
- [ ] AC3 Given a non-member, when they select the log, then they see nothing.

## Design
Screen: n/a — this layer has no DESIGN screen.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Maintenance: log entries are readable offline and creating or editing a log entry is queued in the outbox. Attachment bytes queue until online.

## Module-off behaviour
Maintenance off (X7): the Maintenance tab is hidden and a maintenance-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: maint_log  Endpoints: none  Types: MaintLog
Schema change: yes — supabase/migrations/20261108130300_MAINT-05_log.sql  Contract change: no

## Tests required
Unit: n/a (database behaviour is asserted in pgTAP, TESTING §2)
Component: n/a (no UI)
Integration: supabase/tests/MAINT-05_log.sql → AC1 numeric cost; AC2 non-date rejected; AC3 non-member denied
E2E: n/a (covered by the client journeys for this ID)
Offline test: n/a (server-side)

## Dependencies
Blocked by: MAINT-03-db  Blocks: MAINT-05-api, MAINT-05-web, MAINT-05-ios, MAINT-05-android, MAINT-06-db

## Skill
.claude/skills/sql-supabase/SKILL.md

## Open questions
none

# MAINT-05-web — Mark done

## Summary
Marking a task done asks for the date, who did it, the contractor, the cost, the notes, and photos.

## Spec references
SPEC §4 MAINT-05; DESIGN E4; TESTING §8 E2E-MAINT-1

## Scope
In: The mark-done sheet (E4), including the offline queued state.
Out: The task being completed (MAINT-03, MAINT-04). Photos (CORE-11). The roll (MAINT-05-api).

## Acceptance criteria
- [ ] AC1 Given I tap Mark done, when the sheet opens, then the date is today and who is me.
- [ ] AC2 Given I set a contractor, a cost, and notes, when I save online, then the log exists and a recurring task shows the new next due date.
- [ ] AC3 Given the device is offline, when I save, then the sheet shows offline-queued and the next due date is computed locally with the same rule.
- [ ] AC4 Given I add a photo, when the device is offline, then the photo waits and the log still queues.
- [ ] AC5 Given I am saving, when the request is in flight, then the saving state is shown.

## Design
Screen: DESIGN E4 — States: new, saving, offline-queued. Interactions: save.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Maintenance: log entries are readable offline and creating or editing a log entry is queued in the outbox. Attachment bytes queue until online. DESIGN E4 computes the next due date locally and the server confirms it.

## Module-off behaviour
Maintenance off (X7): the Maintenance tab is hidden and a maintenance-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: maint_log, maint_task, attachment  Endpoints: POST /tasks/{id}/done  Types: MaintLog, MaintLogInput
Schema change: no  Contract change: no

## Tests required
Unit: web/src/maintenance/MarkDone.test.ts → AC3 the local next date matches a monthly rule; AC4 the photo is queued and not uploaded
Component: web/src/maintenance/MarkDone.test.tsx → AC1 defaults, AC2 saving, AC5 saving, AC3 offline-queued. Interaction: save.
Integration: n/a (no API handler in this story)
E2E: E2E-MAINT-1 web/e2e/MarkDone.spec.ts
Offline test: n/a (TESTING §3 asks for the offline pattern on shopping and pantry)

## Dependencies
Blocked by: MAINT-05-db, MAINT-05-api, MAINT-03-web, CORE-11-web  Blocks: MAINT-06-web

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
none

# MAINT-05-ios — Mark done

## Summary
Marking a task done asks for the date, who did it, the contractor, the cost, the notes, and photos.

## Spec references
SPEC §4 MAINT-05; DESIGN E4; TESTING §8 E2E-MAINT-1

## Scope
In: The mark-done sheet (E4), including the offline queued state.
Out: The task being completed (MAINT-03, MAINT-04). Photos (CORE-11). The roll (MAINT-05-api).

## Acceptance criteria
- [ ] AC1 Given I tap Mark done, when the sheet opens, then the date is today and who is me.
- [ ] AC2 Given I set a contractor, a cost, and notes, when I save online, then the log exists and a recurring task shows the new next due date.
- [ ] AC3 Given the device is offline, when I save, then the sheet shows offline-queued and the next due date is computed locally with the same rule.
- [ ] AC4 Given I add a photo, when the device is offline, then the photo waits and the log still queues.
- [ ] AC5 Given I am saving, when the request is in flight, then the saving state is shown.

## Design
Screen: DESIGN E4 — States: new, saving, offline-queued. Interactions: save.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Maintenance: log entries are readable offline and creating or editing a log entry is queued in the outbox. Attachment bytes queue until online. DESIGN E4 computes the next due date locally and the server confirms it.

## Module-off behaviour
Maintenance off (X7): the Maintenance tab is hidden and a maintenance-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: maint_log, maint_task, attachment  Endpoints: POST /tasks/{id}/done  Types: MaintLog, MaintLogInput
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/maintenance/Tests/MarkDoneTests.swift → AC3 the local next date matches a monthly rule; AC4 the photo is queued and not uploaded
Component: apps/ios/AbodeNestlyTests/MarkDoneViewTests.swift → AC1 defaults, AC2 saving, AC5 saving, AC3 offline-queued. Interaction: save.
Integration: n/a (no API handler in this story)
E2E: E2E-MAINT-1 apps/ios/AbodeNestlyUITests/MarkDoneUITests.swift
Offline test: n/a (TESTING §3 asks for the offline pattern on shopping and pantry)

## Dependencies
Blocked by: MAINT-05-db, MAINT-05-api, MAINT-03-ios, CORE-11-ios  Blocks: MAINT-06-ios

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
none

# MAINT-05-android — Mark done

## Summary
Marking a task done asks for the date, who did it, the contractor, the cost, the notes, and photos.

## Spec references
SPEC §4 MAINT-05; DESIGN E4; TESTING §8 E2E-MAINT-1

## Scope
In: The mark-done sheet (E4), including the offline queued state.
Out: The task being completed (MAINT-03, MAINT-04). Photos (CORE-11). The roll (MAINT-05-api).

## Acceptance criteria
- [ ] AC1 Given I tap Mark done, when the sheet opens, then the date is today and who is me.
- [ ] AC2 Given I set a contractor, a cost, and notes, when I save online, then the log exists and a recurring task shows the new next due date.
- [ ] AC3 Given the device is offline, when I save, then the sheet shows offline-queued and the next due date is computed locally with the same rule.
- [ ] AC4 Given I add a photo, when the device is offline, then the photo waits and the log still queues.
- [ ] AC5 Given I am saving, when the request is in flight, then the saving state is shown.

## Design
Screen: DESIGN E4 — States: new, saving, offline-queued. Interactions: save.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Maintenance: log entries are readable offline and creating or editing a log entry is queued in the outbox. Attachment bytes queue until online. DESIGN E4 computes the next due date locally and the server confirms it.

## Module-off behaviour
Maintenance off (X7): the Maintenance tab is hidden and a maintenance-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: maint_log, maint_task, attachment  Endpoints: POST /tasks/{id}/done  Types: MaintLog, MaintLogInput
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/maintenance/src/test/java/app/abodenestly/maintenance/MarkDoneTest.kt → AC3 the local next date matches a monthly rule; AC4 the photo is queued and not uploaded
Component: apps/android/maintenance/src/test/java/app/abodenestly/maintenance/MarkDoneComposeTest.kt → AC1 defaults, AC2 saving, AC5 saving, AC3 offline-queued. Interaction: save.
Integration: n/a (no API handler in this story)
E2E: E2E-MAINT-1 apps/android/maintenance/src/androidTest/java/app/abodenestly/maintenance/MarkDoneE2ETest.kt
Offline test: n/a (TESTING §3 asks for the offline pattern on shopping and pantry)

## Dependencies
Blocked by: MAINT-05-db, MAINT-05-api, MAINT-03-android, CORE-11-android  Blocks: MAINT-06-android

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
none

# MAINT-06-db — Log entry edits

## Summary
A finished job can be corrected after it was recorded.

## Spec references
SPEC §4 MAINT-06; §8 MaintLog; TESTING §3 -db row

## Scope
In: Members can update and soft-delete a log row. The columns are the ones MAINT-05-db created.
Out: The log screens (MAINT-06 clients). Skipped layers: -api — editing a log is an outbox upsert. Export is MAINT-13.

## Acceptance criteria
- [ ] AC1 Given a log, when the member changes the cost, then the new numeric cost is stored.
- [ ] AC2 Given a log, when it is soft-deleted, then deleted_at is set and the row remains until purge.
- [ ] AC3 Given a non-member, when they update the log, then the update is rejected.

## Design
Screen: n/a — this layer has no DESIGN screen.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Maintenance: log entries are readable offline and creating or editing a log entry is queued in the outbox. Attachment bytes queue until online.

## Module-off behaviour
Maintenance off (X7): the Maintenance tab is hidden and a maintenance-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: maint_log  Endpoints: none  Types: MaintLog
Schema change: no  Contract change: no

## Tests required
Unit: n/a (database behaviour is asserted in pgTAP, TESTING §2)
Component: n/a (no UI)
Integration: supabase/tests/MAINT-06_log_edit.sql → AC1 cost update; AC2 soft delete; AC3 non-member denied
E2E: n/a (covered by the client journeys for this ID)
Offline test: n/a (server-side)

## Dependencies
Blocked by: MAINT-05-db  Blocks: MAINT-06-web, MAINT-06-ios, MAINT-06-android, MAINT-10-api, MAINT-13-api

## Skill
.claude/skills/sql-supabase/SKILL.md

## Open questions
The §4 acceptance summary is empty. Proposed default: the editable fields are done_at, done_by, contractor, cost, and notes, and photos stay with CORE-11.

# MAINT-06-web — Log entry CRUD

## Summary
A person can reread a log entry and correct or remove it.

## Spec references
SPEC §4 MAINT-06; DESIGN E6, E7; TESTING §3 client rows

## Scope
In: The log list (E6) and the log detail (E7), including edit and delete. Export stays MAINT-13.
Out: Creating the entry from mark-done (MAINT-05). Skipped layers: -api — editing a log is an outbox upsert. Export is MAINT-13.

## Acceptance criteria
- [ ] AC1 Given there are no entries, when I open the log, then I see the empty state.
- [ ] AC2 Given entries exist, when I open the log, then each row shows the date, title, asset, cost, and who.
- [ ] AC3 Given I change the cost on the detail screen, when I save, then the list shows the new cost.
- [ ] AC4 Given I delete the entry, when the undo window passes, then it is soft-deleted.
- [ ] AC5 Given the device is offline, when I edit the notes, then the edit is queued.

## Design
Screen: DESIGN E6 and E7 — States: E6 empty and populated; E7 the detail. Interactions: edit, delete. Export PDF is MAINT-13..
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Maintenance: log entries are readable offline and creating or editing a log entry is queued in the outbox. Attachment bytes queue until online.

## Module-off behaviour
Maintenance off (X7): the Maintenance tab is hidden and a maintenance-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: maint_log  Endpoints: none  Types: MaintLog
Schema change: no  Contract change: no

## Tests required
Unit: web/src/maintenance/Log.test.ts → AC4 soft delete sets deleted_at; AC5 offline enqueues the note
Component: web/src/maintenance/Log.test.tsx → AC1 empty, AC2 populated rows, AC3 edited cost. Interactions: edit and delete.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: n/a (TESTING §3 asks for the offline pattern on shopping and pantry)

## Dependencies
Blocked by: MAINT-06-db, MAINT-05-web  Blocks: MAINT-10-web, MAINT-13-web

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
The §4 summary is empty. Proposed default: the fields on E7 are the ones the member can edit.

# MAINT-06-ios — Log entry CRUD

## Summary
A person can reread a log entry and correct or remove it.

## Spec references
SPEC §4 MAINT-06; DESIGN E6, E7; TESTING §3 client rows

## Scope
In: The log list (E6) and the log detail (E7), including edit and delete. Export stays MAINT-13.
Out: Creating the entry from mark-done (MAINT-05). Skipped layers: -api — editing a log is an outbox upsert. Export is MAINT-13.

## Acceptance criteria
- [ ] AC1 Given there are no entries, when I open the log, then I see the empty state.
- [ ] AC2 Given entries exist, when I open the log, then each row shows the date, title, asset, cost, and who.
- [ ] AC3 Given I change the cost on the detail screen, when I save, then the list shows the new cost.
- [ ] AC4 Given I delete the entry, when the undo window passes, then it is soft-deleted.
- [ ] AC5 Given the device is offline, when I edit the notes, then the edit is queued.

## Design
Screen: DESIGN E6 and E7 — States: E6 empty and populated; E7 the detail. Interactions: edit, delete. Export PDF is MAINT-13..
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Maintenance: log entries are readable offline and creating or editing a log entry is queued in the outbox. Attachment bytes queue until online.

## Module-off behaviour
Maintenance off (X7): the Maintenance tab is hidden and a maintenance-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: maint_log  Endpoints: none  Types: MaintLog
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/maintenance/Tests/LogTests.swift → AC4 soft delete sets deleted_at; AC5 offline enqueues the note
Component: apps/ios/AbodeNestlyTests/LogViewTests.swift → AC1 empty, AC2 populated rows, AC3 edited cost. Interactions: edit and delete.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: n/a (TESTING §3 asks for the offline pattern on shopping and pantry)

## Dependencies
Blocked by: MAINT-06-db, MAINT-05-ios  Blocks: MAINT-10-ios, MAINT-13-ios

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
The §4 summary is empty. Proposed default: the fields on E7 are the ones the member can edit.

# MAINT-06-android — Log entry CRUD

## Summary
A person can reread a log entry and correct or remove it.

## Spec references
SPEC §4 MAINT-06; DESIGN E6, E7; TESTING §3 client rows

## Scope
In: The log list (E6) and the log detail (E7), including edit and delete. Export stays MAINT-13.
Out: Creating the entry from mark-done (MAINT-05). Skipped layers: -api — editing a log is an outbox upsert. Export is MAINT-13.

## Acceptance criteria
- [ ] AC1 Given there are no entries, when I open the log, then I see the empty state.
- [ ] AC2 Given entries exist, when I open the log, then each row shows the date, title, asset, cost, and who.
- [ ] AC3 Given I change the cost on the detail screen, when I save, then the list shows the new cost.
- [ ] AC4 Given I delete the entry, when the undo window passes, then it is soft-deleted.
- [ ] AC5 Given the device is offline, when I edit the notes, then the edit is queued.

## Design
Screen: DESIGN E6 and E7 — States: E6 empty and populated; E7 the detail. Interactions: edit, delete. Export PDF is MAINT-13..
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Maintenance: log entries are readable offline and creating or editing a log entry is queued in the outbox. Attachment bytes queue until online.

## Module-off behaviour
Maintenance off (X7): the Maintenance tab is hidden and a maintenance-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: maint_log  Endpoints: none  Types: MaintLog
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/maintenance/src/test/java/app/abodenestly/maintenance/LogTest.kt → AC4 soft delete sets deleted_at; AC5 offline enqueues the note
Component: apps/android/maintenance/src/test/java/app/abodenestly/maintenance/LogComposeTest.kt → AC1 empty, AC2 populated rows, AC3 edited cost. Interactions: edit and delete.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: n/a (TESTING §3 asks for the offline pattern on shopping and pantry)

## Dependencies
Blocked by: MAINT-06-db, MAINT-05-android  Blocks: MAINT-10-android, MAINT-13-android

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
The §4 summary is empty. Proposed default: the fields on E7 are the ones the member can edit.

# MAINT-07-web — Due and overdue

## Summary
The household can see which jobs are late and which are due this month.

## Spec references
SPEC §4 MAINT-07; DESIGN E1; TESTING §3 client rows

## Scope
In: The maintenance dashboard (E1): Overdue, Due this month, and Upcoming.
Out: Opening a task (MAINT-03). Mark done from the swipe (MAINT-05). Skipped layers: -db, -api — due and overdue are a local query on next_due. No new column and no endpoint.

## Acceptance criteria
- [ ] AC1 Given a task's next due date is before today, when I open Maintenance, then it is in the Overdue section.
- [ ] AC2 Given a task is due later this month, when I open Maintenance, then it is in Due this month.
- [ ] AC3 Given nothing is due, when I open Maintenance, then I see the nothing-due state.
- [ ] AC4 Given there are no tasks and no assets, when I open Maintenance, then I see Add your first asset or task.
- [ ] AC5 Given the device is offline, when I open the dashboard, then the sections render from local tasks.

## Design
Screen: DESIGN E1 — States: empty, nothing-due, populated, offline. Interactions: tap a task opens E3; swipe right Done opens E4.
Deviations from the plan: none

## Offline behaviour
The dashboard is readable offline (SPEC §6.5, DESIGN E1).

## Module-off behaviour
Maintenance off (X7): the Maintenance tab is hidden and a maintenance-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: maint_task  Endpoints: none  Types: MaintTask
Schema change: no  Contract change: no

## Tests required
Unit: web/src/maintenance/Dashboard.test.ts → AC1 next_due before today is overdue; AC2 the rest of the month is due this month
Component: web/src/maintenance/Dashboard.test.tsx → AC3 nothing-due, AC4 empty, AC5 offline. Interactions: tap and swipe Done.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: n/a (TESTING §3 asks for the offline pattern on shopping and pantry)

## Dependencies
Blocked by: MAINT-03-web, MAINT-04-web  Blocks: none

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
The §4 summary is empty. Proposed default: overdue means next_due before today, and due this month means the rest of the current month, which is how E1 is divided.

# MAINT-07-ios — Due and overdue

## Summary
The household can see which jobs are late and which are due this month.

## Spec references
SPEC §4 MAINT-07; DESIGN E1; TESTING §3 client rows

## Scope
In: The maintenance dashboard (E1): Overdue, Due this month, and Upcoming.
Out: Opening a task (MAINT-03). Mark done from the swipe (MAINT-05). Skipped layers: -db, -api — due and overdue are a local query on next_due. No new column and no endpoint.

## Acceptance criteria
- [ ] AC1 Given a task's next due date is before today, when I open Maintenance, then it is in the Overdue section.
- [ ] AC2 Given a task is due later this month, when I open Maintenance, then it is in Due this month.
- [ ] AC3 Given nothing is due, when I open Maintenance, then I see the nothing-due state.
- [ ] AC4 Given there are no tasks and no assets, when I open Maintenance, then I see Add your first asset or task.
- [ ] AC5 Given the device is offline, when I open the dashboard, then the sections render from local tasks.

## Design
Screen: DESIGN E1 — States: empty, nothing-due, populated, offline. Interactions: tap a task opens E3; swipe right Done opens E4.
Deviations from the plan: none

## Offline behaviour
The dashboard is readable offline (SPEC §6.5, DESIGN E1).

## Module-off behaviour
Maintenance off (X7): the Maintenance tab is hidden and a maintenance-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: maint_task  Endpoints: none  Types: MaintTask
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/maintenance/Tests/DashboardTests.swift → AC1 next_due before today is overdue; AC2 the rest of the month is due this month
Component: apps/ios/AbodeNestlyTests/DashboardViewTests.swift → AC3 nothing-due, AC4 empty, AC5 offline. Interactions: tap and swipe Done.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: n/a (TESTING §3 asks for the offline pattern on shopping and pantry)

## Dependencies
Blocked by: MAINT-03-ios, MAINT-04-ios  Blocks: none

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
The §4 summary is empty. Proposed default: overdue means next_due before today, and due this month means the rest of the current month, which is how E1 is divided.

# MAINT-07-android — Due and overdue

## Summary
The household can see which jobs are late and which are due this month.

## Spec references
SPEC §4 MAINT-07; DESIGN E1; TESTING §3 client rows

## Scope
In: The maintenance dashboard (E1): Overdue, Due this month, and Upcoming.
Out: Opening a task (MAINT-03). Mark done from the swipe (MAINT-05). Skipped layers: -db, -api — due and overdue are a local query on next_due. No new column and no endpoint.

## Acceptance criteria
- [ ] AC1 Given a task's next due date is before today, when I open Maintenance, then it is in the Overdue section.
- [ ] AC2 Given a task is due later this month, when I open Maintenance, then it is in Due this month.
- [ ] AC3 Given nothing is due, when I open Maintenance, then I see the nothing-due state.
- [ ] AC4 Given there are no tasks and no assets, when I open Maintenance, then I see Add your first asset or task.
- [ ] AC5 Given the device is offline, when I open the dashboard, then the sections render from local tasks.

## Design
Screen: DESIGN E1 — States: empty, nothing-due, populated, offline. Interactions: tap a task opens E3; swipe right Done opens E4.
Deviations from the plan: none

## Offline behaviour
The dashboard is readable offline (SPEC §6.5, DESIGN E1).

## Module-off behaviour
Maintenance off (X7): the Maintenance tab is hidden and a maintenance-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: maint_task  Endpoints: none  Types: MaintTask
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/maintenance/src/test/java/app/abodenestly/maintenance/DashboardTest.kt → AC1 next_due before today is overdue; AC2 the rest of the month is due this month
Component: apps/android/maintenance/src/test/java/app/abodenestly/maintenance/DashboardComposeTest.kt → AC3 nothing-due, AC4 empty, AC5 offline. Interactions: tap and swipe Done.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: n/a (TESTING §3 asks for the offline pattern on shopping and pantry)

## Dependencies
Blocked by: MAINT-03-android, MAINT-04-android  Blocks: none

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
The §4 summary is empty. Proposed default: overdue means next_due before today, and due this month means the rest of the current month, which is how E1 is divided.

# MAINT-08-db — Daily task reminders

## Summary
People get a daily reminder for jobs that are due or overdue, and an assigned job pings only that person.

## Spec references
SPEC §4 MAINT-08; §10; §4 MAINT-15; TESTING §3 -db row

## Scope
In: The task_due insert in the daily job: due or overdue, maintenance module on, assignee or everyone, preference respected, deduped per day.
Out: Showing the push (MAINT-08 clients). The sender (PANT-16-api). Assigning the person (MAINT-15). Skipped layers: -api — the rows are inserted by the database job. Delivery reuses PANT-16-api.

## Acceptance criteria
- [ ] AC1 Given a task is due today and has no assignee, when the job runs, then every member gets one task_due row.
- [ ] AC2 Given the task is assigned, when the job runs, then only that member gets the row.
- [ ] AC3 Given the job runs twice in one day, or the preference is off, or maintenance is off, when it finishes, then no extra row is inserted.

## Design
Screen: n/a — this layer has no DESIGN screen.
Deviations from the plan: none

## Offline behaviour
Sending needs a network. The due date may have been edited offline.

## Module-off behaviour
Maintenance off: the insert does not run.

## Data and contract
Tables: notification, maint_task, member, notification_pref  Endpoints: none  Types: Notification
Schema change: yes — supabase/migrations/20261108130400_MAINT-08_task_due.sql  Contract change: no

## Tests required
Unit: n/a (database behaviour is asserted in pgTAP, TESTING §2)
Component: n/a (no UI)
Integration: supabase/tests/MAINT-08_task_due.sql → AC1 all members; AC2 assignee only; AC3 dedupe, preference, and module
E2E: n/a (covered by the client journeys for this ID)
Offline test: n/a (server-side)

## Dependencies
Blocked by: MAINT-03-db, PANT-16-db  Blocks: MAINT-08-web, MAINT-08-ios, MAINT-08-android, MAINT-15-web, MAINT-15-ios, MAINT-15-android

## Skill
.claude/skills/sql-supabase/SKILL.md

## Open questions
none

# MAINT-08-web — Task reminders

## Summary
A person gets a daily push when a maintenance job is due or overdue.

## Spec references
SPEC §4 MAINT-08; DESIGN B10; TESTING §3 client rows

## Scope
In: Receiving the task push and showing it in the inbox from PANT-16.
Out: The inbox shell (PANT-16). The preference (CORE-12). Choosing the assignee (MAINT-15). Skipped layers: -api — the rows are inserted by the database job. Delivery reuses PANT-16-api.

## Acceptance criteria
- [ ] AC1 Given a task_due push arrives, when I tap it, then that task opens.
- [ ] AC2 Given the task is assigned to someone else, when the daily job has run, then I do not get the push.
- [ ] AC3 Given I turned the preference off, when the job runs, then I do not get the push.
- [ ] AC4 Given the notice is in the inbox, when I open the inbox offline, then the cached row is still there.

## Design
Screen: DESIGN B10 — States: populated. Interactions: tap a task_due row.
Deviations from the plan: none

## Offline behaviour
Receiving the push needs a network. The inbox row is readable offline after it has synced.

## Module-off behaviour
Maintenance off (X7): the Maintenance tab is hidden and a maintenance-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: notification  Endpoints: none  Types: Notification
Schema change: no  Contract change: no

## Tests required
Unit: web/src/maintenance/TaskPush.test.ts → AC2 an unassigned user is not in the local notice list for that task; AC3 preference off filters it
Component: web/src/maintenance/TaskPush.test.tsx → AC1 tap opens the task, AC4 offline inbox. State populated.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: n/a (TESTING §3 asks for the offline pattern on shopping and pantry)

## Dependencies
Blocked by: MAINT-08-db, PANT-16-web, CORE-12-web  Blocks: none

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
none

# MAINT-08-ios — Task reminders

## Summary
A person gets a daily push when a maintenance job is due or overdue.

## Spec references
SPEC §4 MAINT-08; DESIGN B10; TESTING §3 client rows

## Scope
In: Receiving the task push and showing it in the inbox from PANT-16.
Out: The inbox shell (PANT-16). The preference (CORE-12). Choosing the assignee (MAINT-15). Skipped layers: -api — the rows are inserted by the database job. Delivery reuses PANT-16-api.

## Acceptance criteria
- [ ] AC1 Given a task_due push arrives, when I tap it, then that task opens.
- [ ] AC2 Given the task is assigned to someone else, when the daily job has run, then I do not get the push.
- [ ] AC3 Given I turned the preference off, when the job runs, then I do not get the push.
- [ ] AC4 Given the notice is in the inbox, when I open the inbox offline, then the cached row is still there.

## Design
Screen: DESIGN B10 — States: populated. Interactions: tap a task_due row.
Deviations from the plan: none

## Offline behaviour
Receiving the push needs a network. The inbox row is readable offline after it has synced.

## Module-off behaviour
Maintenance off (X7): the Maintenance tab is hidden and a maintenance-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: notification  Endpoints: none  Types: Notification
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/maintenance/Tests/TaskPushTests.swift → AC2 an unassigned user is not in the local notice list for that task; AC3 preference off filters it
Component: apps/ios/AbodeNestlyTests/TaskPushViewTests.swift → AC1 tap opens the task, AC4 offline inbox. State populated.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: n/a (TESTING §3 asks for the offline pattern on shopping and pantry)

## Dependencies
Blocked by: MAINT-08-db, PANT-16-ios, CORE-12-ios  Blocks: none

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
none

# MAINT-08-android — Task reminders

## Summary
A person gets a daily push when a maintenance job is due or overdue.

## Spec references
SPEC §4 MAINT-08; DESIGN B10; TESTING §3 client rows

## Scope
In: Receiving the task push and showing it in the inbox from PANT-16.
Out: The inbox shell (PANT-16). The preference (CORE-12). Choosing the assignee (MAINT-15). Skipped layers: -api — the rows are inserted by the database job. Delivery reuses PANT-16-api.

## Acceptance criteria
- [ ] AC1 Given a task_due push arrives, when I tap it, then that task opens.
- [ ] AC2 Given the task is assigned to someone else, when the daily job has run, then I do not get the push.
- [ ] AC3 Given I turned the preference off, when the job runs, then I do not get the push.
- [ ] AC4 Given the notice is in the inbox, when I open the inbox offline, then the cached row is still there.

## Design
Screen: DESIGN B10 — States: populated. Interactions: tap a task_due row.
Deviations from the plan: none

## Offline behaviour
Receiving the push needs a network. The inbox row is readable offline after it has synced.

## Module-off behaviour
Maintenance off (X7): the Maintenance tab is hidden and a maintenance-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: notification  Endpoints: none  Types: Notification
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/maintenance/src/test/java/app/abodenestly/maintenance/TaskPushTest.kt → AC2 an unassigned user is not in the local notice list for that task; AC3 preference off filters it
Component: apps/android/maintenance/src/test/java/app/abodenestly/maintenance/TaskPushComposeTest.kt → AC1 tap opens the task, AC4 offline inbox. State populated.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: n/a (TESTING §3 asks for the offline pattern on shopping and pantry)

## Dependencies
Blocked by: MAINT-08-db, PANT-16-android, CORE-12-android  Blocks: none

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
none

# MAINT-09-db — Contractors

## Summary
The household can keep a contractor's name, trade, phone, and email.

## Spec references
SPEC §4 MAINT-09; §8 Contractor; TESTING §3 -db row

## Scope
In: contractor rows. Notes exist on the type and are included.
Out: The contractor screen (MAINT-09 clients). Skipped layers: -api — contractors sync through the outbox. §7 has no contractor endpoint.

## Acceptance criteria
- [ ] AC1 Given a member, when they insert a contractor with name, trade, phone, and email, then the row is stored.
- [ ] AC2 Given phone and email are null, when they insert, then the row is stored.
- [ ] AC3 Given a non-member, when they select the contractor, then they see nothing.

## Design
Screen: n/a — this layer has no DESIGN screen.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Maintenance: this record is readable offline. DESIGN marks the screen fully offline. Proposed default, recorded on the story: edits queue in the outbox the same way tasks do.

## Module-off behaviour
Maintenance off (X7): the Maintenance tab is hidden and a maintenance-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: contractor  Endpoints: none  Types: Contractor
Schema change: yes — supabase/migrations/20261108130500_MAINT-09_contractor.sql  Contract change: no

## Tests required
Unit: n/a (database behaviour is asserted in pgTAP, TESTING §2)
Component: n/a (no UI)
Integration: supabase/tests/MAINT-09_contractor.sql → AC1 fields stored; AC2 null phone and email; AC3 non-member denied
E2E: n/a (covered by the client journeys for this ID)
Offline test: n/a (server-side)

## Dependencies
Blocked by: CORE-02-db  Blocks: MAINT-09-web, MAINT-09-ios, MAINT-09-android

## Skill
.claude/skills/sql-supabase/SKILL.md

## Open questions
§8 also has notes, and §4 does not. Proposed default: notes are on the edit sheet because the column exists. SPEC §6.5 does not list contractor edits as offline writes, and DESIGN E13 says offline full. Proposed default: queue the edits.

# MAINT-09-web — Contractor contacts

## Summary
A person can keep a contractor's name, trade, phone, and email, and can call or write to them.

## Spec references
SPEC §4 MAINT-09; DESIGN E13; TESTING §3 client rows

## Scope
In: The contractor list (E13).
Out: Picking a contractor on mark-done (MAINT-05). Skipped layers: -api — contractors sync through the outbox. §7 has no contractor endpoint.

## Acceptance criteria
- [ ] AC1 Given I add a contractor, when I save, then the row shows the name and the trade.
- [ ] AC2 Given the row has a phone, when I tap it, then the platform dialer opens with that number.
- [ ] AC3 Given the row has an email, when I tap it, then the platform mail composer opens.
- [ ] AC4 Given the device is offline, when I add a contractor, then the row is queued.

## Design
Screen: DESIGN E13 — States: populated. Interactions: add; tap phone; tap email.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Maintenance: this record is readable offline. DESIGN marks the screen fully offline. Proposed default, recorded on the story: edits queue in the outbox the same way tasks do.

## Module-off behaviour
Maintenance off (X7): the Maintenance tab is hidden and a maintenance-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: contractor  Endpoints: none  Types: Contractor
Schema change: no  Contract change: no

## Tests required
Unit: web/src/maintenance/Contractors.test.ts → AC4 offline enqueues one upsert
Component: web/src/maintenance/Contractors.test.tsx → AC1 the row, AC2 dial, AC3 mail. An empty list is the same layout with no rows.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: n/a (TESTING §3 asks for the offline pattern on shopping and pantry)

## Dependencies
Blocked by: MAINT-09-db  Blocks: none

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
Same notes and offline default as MAINT-09-db. Proposed default: show notes, and queue edits offline.

# MAINT-09-ios — Contractor contacts

## Summary
A person can keep a contractor's name, trade, phone, and email, and can call or write to them.

## Spec references
SPEC §4 MAINT-09; DESIGN E13; TESTING §3 client rows

## Scope
In: The contractor list (E13).
Out: Picking a contractor on mark-done (MAINT-05). Skipped layers: -api — contractors sync through the outbox. §7 has no contractor endpoint.

## Acceptance criteria
- [ ] AC1 Given I add a contractor, when I save, then the row shows the name and the trade.
- [ ] AC2 Given the row has a phone, when I tap it, then the platform dialer opens with that number.
- [ ] AC3 Given the row has an email, when I tap it, then the platform mail composer opens.
- [ ] AC4 Given the device is offline, when I add a contractor, then the row is queued.

## Design
Screen: DESIGN E13 — States: populated. Interactions: add; tap phone; tap email.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Maintenance: this record is readable offline. DESIGN marks the screen fully offline. Proposed default, recorded on the story: edits queue in the outbox the same way tasks do.

## Module-off behaviour
Maintenance off (X7): the Maintenance tab is hidden and a maintenance-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: contractor  Endpoints: none  Types: Contractor
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/maintenance/Tests/ContractorsTests.swift → AC4 offline enqueues one upsert
Component: apps/ios/AbodeNestlyTests/ContractorsViewTests.swift → AC1 the row, AC2 dial, AC3 mail. An empty list is the same layout with no rows.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: n/a (TESTING §3 asks for the offline pattern on shopping and pantry)

## Dependencies
Blocked by: MAINT-09-db  Blocks: none

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
Same notes and offline default as MAINT-09-db. Proposed default: show notes, and queue edits offline.

# MAINT-09-android — Contractor contacts

## Summary
A person can keep a contractor's name, trade, phone, and email, and can call or write to them.

## Spec references
SPEC §4 MAINT-09; DESIGN E13; TESTING §3 client rows

## Scope
In: The contractor list (E13).
Out: Picking a contractor on mark-done (MAINT-05). Skipped layers: -api — contractors sync through the outbox. §7 has no contractor endpoint.

## Acceptance criteria
- [ ] AC1 Given I add a contractor, when I save, then the row shows the name and the trade.
- [ ] AC2 Given the row has a phone, when I tap it, then the platform dialer opens with that number.
- [ ] AC3 Given the row has an email, when I tap it, then the platform mail composer opens.
- [ ] AC4 Given the device is offline, when I add a contractor, then the row is queued.

## Design
Screen: DESIGN E13 — States: populated. Interactions: add; tap phone; tap email.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Maintenance: this record is readable offline. DESIGN marks the screen fully offline. Proposed default, recorded on the story: edits queue in the outbox the same way tasks do.

## Module-off behaviour
Maintenance off (X7): the Maintenance tab is hidden and a maintenance-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: contractor  Endpoints: none  Types: Contractor
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/maintenance/src/test/java/app/abodenestly/maintenance/ContractorsTest.kt → AC4 offline enqueues one upsert
Component: apps/android/maintenance/src/test/java/app/abodenestly/maintenance/ContractorsComposeTest.kt → AC1 the row, AC2 dial, AC3 mail. An empty list is the same layout with no rows.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: n/a (TESTING §3 asks for the offline pattern on shopping and pantry)

## Dependencies
Blocked by: MAINT-09-db  Blocks: none

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
Same notes and offline default as MAINT-09-db. Proposed default: show notes, and queue edits offline.

# MAINT-10-api — Cost report

## Summary
The household can ask what upkeep cost in a year, and what each asset cost.

## Spec references
SPEC §4 MAINT-10; §7 GET /reports/maintenance-costs; §8 CostReport; TESTING §3 -api row

## Scope
In: The cost report endpoint: per year, per asset, and the monthly breakdown on the type.
Out: The screen computes the same figures locally (MAINT-10 clients).

## Acceptance criteria
- [ ] AC1 Given logs in a year, when GET runs with that year, then the total equals the sum of the costs.
- [ ] AC2 Given an asset filter, when GET runs, then only that asset's costs are included.
- [ ] AC3 Given no JWT, when GET runs, then the response is 401.

## Design
Screen: n/a — this layer has no DESIGN screen.
Deviations from the plan: none

## Offline behaviour
The report needs a network. The log costs may have been written offline.

## Module-off behaviour
Maintenance off: the route is rejected.

## Data and contract
Tables: maint_log, asset, household  Endpoints: GET /reports/maintenance-costs  Types: CostReport
Schema change: no  Contract change: yes

## Tests required
Unit: api/test/MAINT-10-costs.test.ts → AC1 the sum; AC2 the asset filter
Component: n/a (no UI)
Integration: api/test/MAINT-10-costs.int.test.ts → AC1 and AC2 against fixture logs; AC3 401.
E2E: n/a (client journeys cover the user path)
Offline test: n/a (server-side; queued client writes are covered by the client offline test)

## Dependencies
Blocked by: MAINT-06-db  Blocks: MAINT-10-web, MAINT-10-ios, MAINT-10-android

## Skill
.claude/skills/node-backend/SKILL.md

## Open questions
§8 CostReport includes a monthly breakdown and §4 says per year and per asset. Proposed default: the payload includes both, and the screen draws both because DESIGN E15 shows both.

# MAINT-10-web — Cost reports

## Summary
A person can see maintenance spend for a year, for each asset, and for each month.

## Spec references
SPEC §4 MAINT-10; DESIGN E15; TESTING §3 client rows

## Scope
In: The report screen (E15) computed from local log rows. PDF export is MAINT-13.
Out: The server report (MAINT-10-api), which this screen does not call.

## Acceptance criteria
- [ ] AC1 Given there are no costs, when I open Reports, then I see the no-data state.
- [ ] AC2 Given logs exist for the selected year, when the screen loads, then I see the total and a row per asset.
- [ ] AC3 Given those logs, when the month bars render, then each month matches the sum of that month's costs.
- [ ] AC4 Given the device is offline, when I change the year, then the figures recompute from local logs.

## Design
Screen: DESIGN E15 — States: no-data, populated. Interactions: change the year. Export PDF is MAINT-13..
Deviations from the plan: none

## Offline behaviour
DESIGN E15 computes the report locally, so it works offline. SPEC §6.5 lets the underlying logs be read offline.

## Module-off behaviour
Maintenance off (X7): the Maintenance tab is hidden and a maintenance-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: maint_log, asset  Endpoints: none on this screen  Types: CostReport
Schema change: no  Contract change: no

## Tests required
Unit: web/src/maintenance/Costs.test.ts → AC2 the total is the sum; AC3 each month matches
Component: web/src/maintenance/Costs.test.tsx → AC1 no-data, AC4 offline year change. States no-data and populated.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: n/a (TESTING §3 asks for the offline pattern on shopping and pantry)

## Dependencies
Blocked by: MAINT-10-api, MAINT-06-web  Blocks: none

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
Same split as PANT-11. Proposed default: the screen computes locally and does not call MAINT-10-api.

# MAINT-10-ios — Cost reports

## Summary
A person can see maintenance spend for a year, for each asset, and for each month.

## Spec references
SPEC §4 MAINT-10; DESIGN E15; TESTING §3 client rows

## Scope
In: The report screen (E15) computed from local log rows. PDF export is MAINT-13.
Out: The server report (MAINT-10-api), which this screen does not call.

## Acceptance criteria
- [ ] AC1 Given there are no costs, when I open Reports, then I see the no-data state.
- [ ] AC2 Given logs exist for the selected year, when the screen loads, then I see the total and a row per asset.
- [ ] AC3 Given those logs, when the month bars render, then each month matches the sum of that month's costs.
- [ ] AC4 Given the device is offline, when I change the year, then the figures recompute from local logs.

## Design
Screen: DESIGN E15 — States: no-data, populated. Interactions: change the year. Export PDF is MAINT-13..
Deviations from the plan: none

## Offline behaviour
DESIGN E15 computes the report locally, so it works offline. SPEC §6.5 lets the underlying logs be read offline.

## Module-off behaviour
Maintenance off (X7): the Maintenance tab is hidden and a maintenance-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: maint_log, asset  Endpoints: none on this screen  Types: CostReport
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/maintenance/Tests/CostsTests.swift → AC2 the total is the sum; AC3 each month matches
Component: apps/ios/AbodeNestlyTests/CostsViewTests.swift → AC1 no-data, AC4 offline year change. States no-data and populated.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: n/a (TESTING §3 asks for the offline pattern on shopping and pantry)

## Dependencies
Blocked by: MAINT-10-api, MAINT-06-ios  Blocks: none

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
Same split as PANT-11. Proposed default: the screen computes locally and does not call MAINT-10-api.

# MAINT-10-android — Cost reports

## Summary
A person can see maintenance spend for a year, for each asset, and for each month.

## Spec references
SPEC §4 MAINT-10; DESIGN E15; TESTING §3 client rows

## Scope
In: The report screen (E15) computed from local log rows. PDF export is MAINT-13.
Out: The server report (MAINT-10-api), which this screen does not call.

## Acceptance criteria
- [ ] AC1 Given there are no costs, when I open Reports, then I see the no-data state.
- [ ] AC2 Given logs exist for the selected year, when the screen loads, then I see the total and a row per asset.
- [ ] AC3 Given those logs, when the month bars render, then each month matches the sum of that month's costs.
- [ ] AC4 Given the device is offline, when I change the year, then the figures recompute from local logs.

## Design
Screen: DESIGN E15 — States: no-data, populated. Interactions: change the year. Export PDF is MAINT-13..
Deviations from the plan: none

## Offline behaviour
DESIGN E15 computes the report locally, so it works offline. SPEC §6.5 lets the underlying logs be read offline.

## Module-off behaviour
Maintenance off (X7): the Maintenance tab is hidden and a maintenance-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: maint_log, asset  Endpoints: none on this screen  Types: CostReport
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/maintenance/src/test/java/app/abodenestly/maintenance/CostsTest.kt → AC2 the total is the sum; AC3 each month matches
Component: apps/android/maintenance/src/test/java/app/abodenestly/maintenance/CostsComposeTest.kt → AC1 no-data, AC4 offline year change. States no-data and populated.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: n/a (TESTING §3 asks for the offline pattern on shopping and pantry)

## Dependencies
Blocked by: MAINT-10-api, MAINT-06-android  Blocks: none

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
Same split as PANT-11. Proposed default: the screen computes locally and does not call MAINT-10-api.

# MAINT-11-db — Warranty reminders

## Summary
Thirty days before a warranty ends, a notice is waiting.

## Spec references
SPEC §4 MAINT-11; §10; TESTING §3 -db row

## Scope
In: The warranty insert 30 days before warranty_until, respecting the preference and the maintenance flag.
Out: Showing it (MAINT-11 clients). The sender (PANT-16-api). Skipped layers: -api — the row is inserted by the database job. Delivery reuses PANT-16-api.

## Acceptance criteria
- [ ] AC1 Given a warranty ends in 30 days and the preference is on, when the job runs, then one warranty notice is inserted per member.
- [ ] AC2 Given the warranty ends in 29 or 31 days, when the job runs, then no warranty notice is inserted.
- [ ] AC3 Given the preference is off or maintenance is off, when the job runs, then no warranty notice is inserted.

## Design
Screen: n/a — this layer has no DESIGN screen.
Deviations from the plan: none

## Offline behaviour
Sending needs a network. The warranty date is on the asset.

## Module-off behaviour
Maintenance off: no warranty notice is inserted.

## Data and contract
Tables: notification, asset, notification_pref, household  Endpoints: none  Types: Notification
Schema change: yes — supabase/migrations/20261108130600_MAINT-11_warranty.sql  Contract change: no

## Tests required
Unit: n/a (database behaviour is asserted in pgTAP, TESTING §2)
Component: n/a (no UI)
Integration: supabase/tests/MAINT-11_warranty.sql → AC1 exactly 30 days; AC2 other days skipped; AC3 preference and module
E2E: n/a (covered by the client journeys for this ID)
Offline test: n/a (server-side)

## Dependencies
Blocked by: MAINT-02-db, CORE-12-db  Blocks: MAINT-11-web, MAINT-11-ios, MAINT-11-android

## Skill
.claude/skills/sql-supabase/SKILL.md

## Open questions
The warranty insert in §14 does not join notification_pref and does not check module_maintenance, while §10 says the job respects preferences. Proposed default: this migration adds both filters.

# MAINT-11-web — Warranty reminders

## Summary
A person is warned 30 days before a warranty runs out.

## Spec references
SPEC §4 MAINT-11; DESIGN E9, B10; TESTING §3 client rows

## Scope
In: The warranty push, the inbox row, and the warranty badge already on the asset.
Out: The asset screen (MAINT-02). The sender (PANT-16-api). Skipped layers: -api — the row is inserted by the database job. Delivery reuses PANT-16-api.

## Acceptance criteria
- [ ] AC1 Given a warranty notice arrives, when I tap it, then that asset opens.
- [ ] AC2 Given the warranty ends within 30 days, when I view the asset, then the warranty-expiring state is shown.
- [ ] AC3 Given the warranty date is past, when I view the asset, then the warranty-expired state is shown.
- [ ] AC4 Given I turned the preference off, when the job runs, then I do not get the notice.

## Design
Screen: DESIGN E9 and B10 — States: E9 warranty-expiring and warranty-expired; B10 populated. Interactions: tap the warranty notice.
Deviations from the plan: none

## Offline behaviour
The push needs a network. The asset badge is readable offline.

## Module-off behaviour
Maintenance off (X7): the Maintenance tab is hidden and a maintenance-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: notification, asset  Endpoints: none  Types: Notification, Asset
Schema change: no  Contract change: no

## Tests required
Unit: web/src/maintenance/Warranty.test.ts → AC4 preference off yields no local notice
Component: web/src/maintenance/Warranty.test.tsx → AC1 tap opens the asset, AC2 warranty-expiring, AC3 warranty-expired
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: n/a (TESTING §3 asks for the offline pattern on shopping and pantry)

## Dependencies
Blocked by: MAINT-11-db, MAINT-02-web, PANT-16-web  Blocks: none

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
none

# MAINT-11-ios — Warranty reminders

## Summary
A person is warned 30 days before a warranty runs out.

## Spec references
SPEC §4 MAINT-11; DESIGN E9, B10; TESTING §3 client rows

## Scope
In: The warranty push, the inbox row, and the warranty badge already on the asset.
Out: The asset screen (MAINT-02). The sender (PANT-16-api). Skipped layers: -api — the row is inserted by the database job. Delivery reuses PANT-16-api.

## Acceptance criteria
- [ ] AC1 Given a warranty notice arrives, when I tap it, then that asset opens.
- [ ] AC2 Given the warranty ends within 30 days, when I view the asset, then the warranty-expiring state is shown.
- [ ] AC3 Given the warranty date is past, when I view the asset, then the warranty-expired state is shown.
- [ ] AC4 Given I turned the preference off, when the job runs, then I do not get the notice.

## Design
Screen: DESIGN E9 and B10 — States: E9 warranty-expiring and warranty-expired; B10 populated. Interactions: tap the warranty notice.
Deviations from the plan: none

## Offline behaviour
The push needs a network. The asset badge is readable offline.

## Module-off behaviour
Maintenance off (X7): the Maintenance tab is hidden and a maintenance-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: notification, asset  Endpoints: none  Types: Notification, Asset
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/maintenance/Tests/WarrantyTests.swift → AC4 preference off yields no local notice
Component: apps/ios/AbodeNestlyTests/WarrantyViewTests.swift → AC1 tap opens the asset, AC2 warranty-expiring, AC3 warranty-expired
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: n/a (TESTING §3 asks for the offline pattern on shopping and pantry)

## Dependencies
Blocked by: MAINT-11-db, MAINT-02-ios, PANT-16-ios  Blocks: none

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
none

# MAINT-11-android — Warranty reminders

## Summary
A person is warned 30 days before a warranty runs out.

## Spec references
SPEC §4 MAINT-11; DESIGN E9, B10; TESTING §3 client rows

## Scope
In: The warranty push, the inbox row, and the warranty badge already on the asset.
Out: The asset screen (MAINT-02). The sender (PANT-16-api). Skipped layers: -api — the row is inserted by the database job. Delivery reuses PANT-16-api.

## Acceptance criteria
- [ ] AC1 Given a warranty notice arrives, when I tap it, then that asset opens.
- [ ] AC2 Given the warranty ends within 30 days, when I view the asset, then the warranty-expiring state is shown.
- [ ] AC3 Given the warranty date is past, when I view the asset, then the warranty-expired state is shown.
- [ ] AC4 Given I turned the preference off, when the job runs, then I do not get the notice.

## Design
Screen: DESIGN E9 and B10 — States: E9 warranty-expiring and warranty-expired; B10 populated. Interactions: tap the warranty notice.
Deviations from the plan: none

## Offline behaviour
The push needs a network. The asset badge is readable offline.

## Module-off behaviour
Maintenance off (X7): the Maintenance tab is hidden and a maintenance-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: notification, asset  Endpoints: none  Types: Notification, Asset
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/maintenance/src/test/java/app/abodenestly/maintenance/WarrantyTest.kt → AC4 preference off yields no local notice
Component: apps/android/maintenance/src/test/java/app/abodenestly/maintenance/WarrantyComposeTest.kt → AC1 tap opens the asset, AC2 warranty-expiring, AC3 warranty-expired
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: n/a (TESTING §3 asks for the offline pattern on shopping and pantry)

## Dependencies
Blocked by: MAINT-11-db, MAINT-02-android, PANT-16-android  Blocks: none

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
none

# MAINT-12-db — Checklist templates

## Summary
A seasonal list can exist for every household, or for just one.

## Spec references
SPEC §4 MAINT-12; §8 ChecklistTemplate; TESTING §3 -db row

## Scope
In: Global templates are readable by members and not writable. Household templates are writable by members.
Out: Apply (MAINT-12-api). The screen (MAINT-12 clients).

## Acceptance criteria
- [ ] AC1 Given a global template, when a member selects it, then they can read it.
- [ ] AC2 Given a global template, when a member updates it, then the update is rejected.
- [ ] AC3 Given a household template, when a member inserts an item on it, then the row is stored.

## Design
Screen: n/a — this layer has no DESIGN screen.
Deviations from the plan: none

## Offline behaviour
Applying a template needs the API. The template rows for a household can sync.

## Module-off behaviour
Maintenance off: applying is rejected (X7). The rows can remain.

## Data and contract
Tables: checklist_template, checklist_template_item  Endpoints: none  Types: ChecklistTemplate, ChecklistTemplateItem
Schema change: yes — supabase/migrations/20261108130700_MAINT-12_templates.sql  Contract change: no

## Tests required
Unit: n/a (database behaviour is asserted in pgTAP, TESTING §2)
Component: n/a (no UI)
Integration: supabase/tests/MAINT-12_templates.sql → AC1 global readable; AC2 global not writable; AC3 household item insert
E2E: n/a (covered by the client journeys for this ID)
Offline test: n/a (server-side)

## Dependencies
Blocked by: MAINT-03-db  Blocks: MAINT-12-api, MAINT-12-web, MAINT-12-ios, MAINT-12-android

## Skill
.claude/skills/sql-supabase/SKILL.md

## Open questions
checklist_template is not in the §8 SyncTable list. Proposed default: household templates are added to the sync allow-list, and global templates are fetched with the apply screen's online read.

# MAINT-12-api — Apply a checklist

## Summary
Applying a seasonal checklist creates the tasks.

## Spec references
SPEC §4 MAINT-12; §7 POST /templates/{id}/apply; TESTING §3 -api row

## Scope
In: POST /templates/{id}/apply creates a task per template item.
Out: The template rows (MAINT-12-db). The screen (MAINT-12 clients).

## Acceptance criteria
- [ ] AC1 Given a template with two items, when apply succeeds, then two tasks are returned.
- [ ] AC2 Given no JWT, when apply is called, then the response is 401.
- [ ] AC3 Given an unknown template, when apply is called, then the response is 404 and no task is created.

## Design
Screen: n/a — this layer has no DESIGN screen.
Deviations from the plan: none

## Offline behaviour
Apply is an API call and needs a network. The tasks it creates can afterwards be edited offline (SPEC §6.5).

## Module-off behaviour
Maintenance off: apply is rejected with module_disabled.

## Data and contract
Tables: checklist_template, checklist_template_item, maint_task  Endpoints: POST /templates/{id}/apply  Types: MaintTask, ChecklistTemplate
Schema change: no  Contract change: yes

## Tests required
Unit: api/test/MAINT-12-apply.test.ts → AC1 two items become two tasks
Component: n/a (no UI)
Integration: api/test/MAINT-12-apply.int.test.ts → AC1 happy path; AC2 401; AC3 404.
E2E: n/a (client journeys cover the user path)
Offline test: n/a (server-side; queued client writes are covered by the client offline test)

## Dependencies
Blocked by: MAINT-12-db  Blocks: MAINT-12-web, MAINT-12-ios, MAINT-12-android

## Skill
.claude/skills/node-backend/SKILL.md

## Open questions
none

# MAINT-12-web — Seasonal checklists

## Summary
A person can apply a global or household checklist and get the tasks.

## Spec references
SPEC §4 MAINT-12; DESIGN E14; TESTING §3 client rows

## Scope
In: The template screen (E14), including Apply.
Out: The tasks that appear (MAINT-03). The apply route (MAINT-12-api).

## Acceptance criteria
- [ ] AC1 Given only global templates exist, when I open the screen, then I see the global-only state.
- [ ] AC2 Given I open a template, when the preview shows, then I see its items.
- [ ] AC3 Given I tap Apply to house, when the request succeeds, then the tasks exist and a toast says so.
- [ ] AC4 Given the device is offline, when I open a global template, then the preview is unavailable and Apply is disabled.

## Design
Screen: DESIGN E14 — States: global-only, with-custom, applying. Interactions: Apply to house.
Deviations from the plan: none

## Offline behaviour
DESIGN E14: preview of household templates can be local; apply needs the API. Global templates are not in the sync list, so their preview needs a network under the MAINT-12-db default.

## Module-off behaviour
Maintenance off (X7): the Maintenance tab is hidden and a maintenance-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: checklist_template, checklist_template_item, maint_task  Endpoints: POST /templates/{id}/apply  Types: ChecklistTemplate, MaintTask
Schema change: no  Contract change: no

## Tests required
Unit: web/src/maintenance/Templates.test.ts → AC4 offline disables Apply
Component: web/src/maintenance/Templates.test.tsx → AC1 global-only, AC2 preview, AC3 applying. Interaction: Apply.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: n/a (TESTING §3 asks for the offline pattern on shopping and pantry)

## Dependencies
Blocked by: MAINT-12-db, MAINT-12-api  Blocks: none

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
§4 does not say the member can author a household template, and DESIGN E14 has a household section. Proposed default: the member can add a household template on this screen.

# MAINT-12-ios — Seasonal checklists

## Summary
A person can apply a global or household checklist and get the tasks.

## Spec references
SPEC §4 MAINT-12; DESIGN E14; TESTING §3 client rows

## Scope
In: The template screen (E14), including Apply.
Out: The tasks that appear (MAINT-03). The apply route (MAINT-12-api).

## Acceptance criteria
- [ ] AC1 Given only global templates exist, when I open the screen, then I see the global-only state.
- [ ] AC2 Given I open a template, when the preview shows, then I see its items.
- [ ] AC3 Given I tap Apply to house, when the request succeeds, then the tasks exist and a toast says so.
- [ ] AC4 Given the device is offline, when I open a global template, then the preview is unavailable and Apply is disabled.

## Design
Screen: DESIGN E14 — States: global-only, with-custom, applying. Interactions: Apply to house.
Deviations from the plan: none

## Offline behaviour
DESIGN E14: preview of household templates can be local; apply needs the API. Global templates are not in the sync list, so their preview needs a network under the MAINT-12-db default.

## Module-off behaviour
Maintenance off (X7): the Maintenance tab is hidden and a maintenance-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: checklist_template, checklist_template_item, maint_task  Endpoints: POST /templates/{id}/apply  Types: ChecklistTemplate, MaintTask
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/maintenance/Tests/TemplatesTests.swift → AC4 offline disables Apply
Component: apps/ios/AbodeNestlyTests/TemplatesViewTests.swift → AC1 global-only, AC2 preview, AC3 applying. Interaction: Apply.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: n/a (TESTING §3 asks for the offline pattern on shopping and pantry)

## Dependencies
Blocked by: MAINT-12-db, MAINT-12-api  Blocks: none

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
§4 does not say the member can author a household template, and DESIGN E14 has a household section. Proposed default: the member can add a household template on this screen.

# MAINT-12-android — Seasonal checklists

## Summary
A person can apply a global or household checklist and get the tasks.

## Spec references
SPEC §4 MAINT-12; DESIGN E14; TESTING §3 client rows

## Scope
In: The template screen (E14), including Apply.
Out: The tasks that appear (MAINT-03). The apply route (MAINT-12-api).

## Acceptance criteria
- [ ] AC1 Given only global templates exist, when I open the screen, then I see the global-only state.
- [ ] AC2 Given I open a template, when the preview shows, then I see its items.
- [ ] AC3 Given I tap Apply to house, when the request succeeds, then the tasks exist and a toast says so.
- [ ] AC4 Given the device is offline, when I open a global template, then the preview is unavailable and Apply is disabled.

## Design
Screen: DESIGN E14 — States: global-only, with-custom, applying. Interactions: Apply to house.
Deviations from the plan: none

## Offline behaviour
DESIGN E14: preview of household templates can be local; apply needs the API. Global templates are not in the sync list, so their preview needs a network under the MAINT-12-db default.

## Module-off behaviour
Maintenance off (X7): the Maintenance tab is hidden and a maintenance-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: checklist_template, checklist_template_item, maint_task  Endpoints: POST /templates/{id}/apply  Types: ChecklistTemplate, MaintTask
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/maintenance/src/test/java/app/abodenestly/maintenance/TemplatesTest.kt → AC4 offline disables Apply
Component: apps/android/maintenance/src/test/java/app/abodenestly/maintenance/TemplatesComposeTest.kt → AC1 global-only, AC2 preview, AC3 applying. Interaction: Apply.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: n/a (TESTING §3 asks for the offline pattern on shopping and pantry)

## Dependencies
Blocked by: MAINT-12-db, MAINT-12-api  Blocks: none

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
§4 does not say the member can author a household template, and DESIGN E14 has a household section. Proposed default: the member can add a household template on this screen.

# MAINT-13-api — Export the maintenance log to PDF

## Summary
The household can download the maintenance log as a PDF.

## Spec references
SPEC §4 MAINT-13; §7 GET /export/maintenance.pdf; TESTING §8 E2E-MAINT-3

## Scope
In: The PDF endpoint, with optional from and to dates.
Out: The buttons that call it (MAINT-13 clients).

## Acceptance criteria
- [ ] AC1 Given logs exist, when GET runs, then the response is application/pdf and the body is not empty.
- [ ] AC2 Given from and to, when GET runs, then entries outside that range are not in the document.
- [ ] AC3 Given no JWT, when GET runs, then the response is 401.

## Design
Screen: n/a — this layer has no DESIGN screen.
Deviations from the plan: none

## Offline behaviour
Generating the file needs a network. The log rows may have been written offline.

## Module-off behaviour
Maintenance off: the export is rejected.

## Data and contract
Tables: maint_log, asset, maint_task  Endpoints: GET /export/maintenance.pdf  Types: none (PDF bytes)
Schema change: no  Contract change: yes

## Tests required
Unit: api/test/MAINT-13-pdf.test.ts → AC1 the body starts with the PDF marker; AC2 the range filter
Component: n/a (no UI)
Integration: api/test/MAINT-13-pdf.int.test.ts → AC1 a non-empty PDF; AC3 401.
E2E: n/a (client journeys cover the user path)
Offline test: n/a (server-side; queued client writes are covered by the client offline test)

## Dependencies
Blocked by: MAINT-06-db  Blocks: MAINT-13-web, MAINT-13-ios, MAINT-13-android

## Skill
.claude/skills/node-backend/SKILL.md

## Open questions
none

# MAINT-13-web — Export the log to PDF

## Summary
A person can export the maintenance log as a PDF.

## Spec references
SPEC §4 MAINT-13; DESIGN E6, E15; TESTING §8 E2E-MAINT-3

## Scope
In: The Export PDF actions on the log and on the cost report.
Out: The PDF bytes (MAINT-13-api). The log list (MAINT-06).

## Acceptance criteria
- [ ] AC1 Given I tap Export PDF, when the file returns, then a non-empty PDF is saved or shared.
- [ ] AC2 Given the request is in flight, when I look at the screen, then the exporting state is shown.
- [ ] AC3 Given the device is offline, when I look at the action, then it is disabled.
- [ ] AC4 Given the export fails, when the error returns, then the log list is unchanged and I can retry.

## Design
Screen: DESIGN E6 — States: populated, exporting. Interactions: Export PDF. The same action is on E15..
Deviations from the plan: none

## Offline behaviour
The list is readable offline. The export needs a network (DESIGN E6).

## Module-off behaviour
Maintenance off (X7): the Maintenance tab is hidden and a maintenance-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: maint_log  Endpoints: GET /export/maintenance.pdf  Types: none
Schema change: no  Contract change: no

## Tests required
Unit: web/src/maintenance/ExportPdf.test.ts → AC3 offline does not call the endpoint
Component: web/src/maintenance/ExportPdf.test.tsx → AC1 the file is handed to the platform, AC2 exporting, AC4 error. Interaction: Export PDF.
Integration: n/a (no API handler in this story)
E2E: E2E-MAINT-3 web/e2e/ExportPdf.spec.ts
Offline test: n/a (TESTING §3 asks for the offline pattern on shopping and pantry)

## Dependencies
Blocked by: MAINT-13-api, MAINT-06-web  Blocks: none

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
none

# MAINT-13-ios — Export the log to PDF

## Summary
A person can export the maintenance log as a PDF.

## Spec references
SPEC §4 MAINT-13; DESIGN E6, E15; TESTING §8 E2E-MAINT-3

## Scope
In: The Export PDF actions on the log and on the cost report.
Out: The PDF bytes (MAINT-13-api). The log list (MAINT-06).

## Acceptance criteria
- [ ] AC1 Given I tap Export PDF, when the file returns, then a non-empty PDF is saved or shared.
- [ ] AC2 Given the request is in flight, when I look at the screen, then the exporting state is shown.
- [ ] AC3 Given the device is offline, when I look at the action, then it is disabled.
- [ ] AC4 Given the export fails, when the error returns, then the log list is unchanged and I can retry.

## Design
Screen: DESIGN E6 — States: populated, exporting. Interactions: Export PDF. The same action is on E15..
Deviations from the plan: none

## Offline behaviour
The list is readable offline. The export needs a network (DESIGN E6).

## Module-off behaviour
Maintenance off (X7): the Maintenance tab is hidden and a maintenance-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: maint_log  Endpoints: GET /export/maintenance.pdf  Types: none
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/maintenance/Tests/ExportPdfTests.swift → AC3 offline does not call the endpoint
Component: apps/ios/AbodeNestlyTests/ExportPdfViewTests.swift → AC1 the file is handed to the platform, AC2 exporting, AC4 error. Interaction: Export PDF.
Integration: n/a (no API handler in this story)
E2E: E2E-MAINT-3 apps/ios/AbodeNestlyUITests/ExportPdfUITests.swift
Offline test: n/a (TESTING §3 asks for the offline pattern on shopping and pantry)

## Dependencies
Blocked by: MAINT-13-api, MAINT-06-ios  Blocks: none

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
none

# MAINT-13-android — Export the log to PDF

## Summary
A person can export the maintenance log as a PDF.

## Spec references
SPEC §4 MAINT-13; DESIGN E6, E15; TESTING §8 E2E-MAINT-3

## Scope
In: The Export PDF actions on the log and on the cost report.
Out: The PDF bytes (MAINT-13-api). The log list (MAINT-06).

## Acceptance criteria
- [ ] AC1 Given I tap Export PDF, when the file returns, then a non-empty PDF is saved or shared.
- [ ] AC2 Given the request is in flight, when I look at the screen, then the exporting state is shown.
- [ ] AC3 Given the device is offline, when I look at the action, then it is disabled.
- [ ] AC4 Given the export fails, when the error returns, then the log list is unchanged and I can retry.

## Design
Screen: DESIGN E6 — States: populated, exporting. Interactions: Export PDF. The same action is on E15..
Deviations from the plan: none

## Offline behaviour
The list is readable offline. The export needs a network (DESIGN E6).

## Module-off behaviour
Maintenance off (X7): the Maintenance tab is hidden and a maintenance-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: maint_log  Endpoints: GET /export/maintenance.pdf  Types: none
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/maintenance/src/test/java/app/abodenestly/maintenance/ExportPdfTest.kt → AC3 offline does not call the endpoint
Component: apps/android/maintenance/src/test/java/app/abodenestly/maintenance/ExportPdfComposeTest.kt → AC1 the file is handed to the platform, AC2 exporting, AC4 error. Interaction: Export PDF.
Integration: n/a (no API handler in this story)
E2E: E2E-MAINT-3 apps/android/maintenance/src/androidTest/java/app/abodenestly/maintenance/ExportPdfE2ETest.kt
Offline test: n/a (TESTING §3 asks for the offline pattern on shopping and pantry)

## Dependencies
Blocked by: MAINT-13-api, MAINT-06-android  Blocks: none

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
none

# MAINT-14-web — Asset QR labels

## Summary
A person can print a QR label, and scanning it opens that asset.

## Spec references
SPEC §4 MAINT-14; DESIGN E10, E8; TESTING §3 client rows

## Scope
In: The QR sheet (E10) and Scan QR on the asset list.
Out: The asset itself (MAINT-02). Skipped layers: -db, -api — qr_code is generated on the asset in MAINT-02-db. §7 has no QR endpoint. The code is drawn on the device.

## Acceptance criteria
- [ ] AC1 Given I open QR label, when the sheet loads, then I see a QR code and the asset name.
- [ ] AC2 Given I tap Print, when the platform sheet opens, then the label is what it offers to print.
- [ ] AC3 Given I tap Save image, when it finishes, then an image of the label is stored on the device.
- [ ] AC4 Given I scan that code, when the scan succeeds, then that asset opens.
- [ ] AC5 Given the device is offline, when I open the sheet for an asset already on the device, then the code still renders.

## Design
Screen: DESIGN E10 — States: the sheet, which has one populated state. Interactions: Print, Save image. Scan QR is on E8..
Deviations from the plan: none

## Offline behaviour
DESIGN E10 generates the code locally, so it works offline for assets already stored. SPEC §6.5 reads the asset offline.

## Module-off behaviour
Maintenance off (X7): the Maintenance tab is hidden and a maintenance-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: asset  Endpoints: none  Types: Asset
Schema change: no  Contract change: no

## Tests required
Unit: web/src/maintenance/QrLabel.test.ts → AC4 the payload of the code resolves to the asset id
Component: web/src/maintenance/QrLabel.test.tsx → AC1 the code and the name, AC2 print, AC3 save, AC5 offline. Interactions: Print, Save image, Scan QR.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: n/a (TESTING §3 asks for the offline pattern on shopping and pantry)

## Dependencies
Blocked by: MAINT-02-web  Blocks: none

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
§4 does not say what the QR encodes. Proposed default: the asset id, which MAINT-02-db already stores in qr_code, so a scan inside the app can open the asset without a new endpoint.

# MAINT-14-ios — Asset QR labels

## Summary
A person can print a QR label, and scanning it opens that asset.

## Spec references
SPEC §4 MAINT-14; DESIGN E10, E8; TESTING §3 client rows

## Scope
In: The QR sheet (E10) and Scan QR on the asset list.
Out: The asset itself (MAINT-02). Skipped layers: -db, -api — qr_code is generated on the asset in MAINT-02-db. §7 has no QR endpoint. The code is drawn on the device.

## Acceptance criteria
- [ ] AC1 Given I open QR label, when the sheet loads, then I see a QR code and the asset name.
- [ ] AC2 Given I tap Print, when the platform sheet opens, then the label is what it offers to print.
- [ ] AC3 Given I tap Save image, when it finishes, then an image of the label is stored on the device.
- [ ] AC4 Given I scan that code, when the scan succeeds, then that asset opens.
- [ ] AC5 Given the device is offline, when I open the sheet for an asset already on the device, then the code still renders.

## Design
Screen: DESIGN E10 — States: the sheet, which has one populated state. Interactions: Print, Save image. Scan QR is on E8..
Deviations from the plan: none

## Offline behaviour
DESIGN E10 generates the code locally, so it works offline for assets already stored. SPEC §6.5 reads the asset offline.

## Module-off behaviour
Maintenance off (X7): the Maintenance tab is hidden and a maintenance-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: asset  Endpoints: none  Types: Asset
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/maintenance/Tests/QrLabelTests.swift → AC4 the payload of the code resolves to the asset id
Component: apps/ios/AbodeNestlyTests/QrLabelViewTests.swift → AC1 the code and the name, AC2 print, AC3 save, AC5 offline. Interactions: Print, Save image, Scan QR.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: n/a (TESTING §3 asks for the offline pattern on shopping and pantry)

## Dependencies
Blocked by: MAINT-02-ios  Blocks: none

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
§4 does not say what the QR encodes. Proposed default: the asset id, which MAINT-02-db already stores in qr_code, so a scan inside the app can open the asset without a new endpoint.

# MAINT-14-android — Asset QR labels

## Summary
A person can print a QR label, and scanning it opens that asset.

## Spec references
SPEC §4 MAINT-14; DESIGN E10, E8; TESTING §3 client rows

## Scope
In: The QR sheet (E10) and Scan QR on the asset list.
Out: The asset itself (MAINT-02). Skipped layers: -db, -api — qr_code is generated on the asset in MAINT-02-db. §7 has no QR endpoint. The code is drawn on the device.

## Acceptance criteria
- [ ] AC1 Given I open QR label, when the sheet loads, then I see a QR code and the asset name.
- [ ] AC2 Given I tap Print, when the platform sheet opens, then the label is what it offers to print.
- [ ] AC3 Given I tap Save image, when it finishes, then an image of the label is stored on the device.
- [ ] AC4 Given I scan that code, when the scan succeeds, then that asset opens.
- [ ] AC5 Given the device is offline, when I open the sheet for an asset already on the device, then the code still renders.

## Design
Screen: DESIGN E10 — States: the sheet, which has one populated state. Interactions: Print, Save image. Scan QR is on E8..
Deviations from the plan: none

## Offline behaviour
DESIGN E10 generates the code locally, so it works offline for assets already stored. SPEC §6.5 reads the asset offline.

## Module-off behaviour
Maintenance off (X7): the Maintenance tab is hidden and a maintenance-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: asset  Endpoints: none  Types: Asset
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/maintenance/src/test/java/app/abodenestly/maintenance/QrLabelTest.kt → AC4 the payload of the code resolves to the asset id
Component: apps/android/maintenance/src/test/java/app/abodenestly/maintenance/QrLabelComposeTest.kt → AC1 the code and the name, AC2 print, AC3 save, AC5 offline. Interactions: Print, Save image, Scan QR.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: n/a (TESTING §3 asks for the offline pattern on shopping and pantry)

## Dependencies
Blocked by: MAINT-02-android  Blocks: none

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
§4 does not say what the QR encodes. Proposed default: the asset id, which MAINT-02-db already stores in qr_code, so a scan inside the app can open the asset without a new endpoint.

# MAINT-15-web — Assign a task

## Summary
A job can be given to one person, and the reminder for it goes only to them.

## Spec references
SPEC §4 MAINT-15; DESIGN E3, E5; TESTING §3 client rows

## Scope
In: The assignee picker on the task.
Out: The reminder query (MAINT-08-db). Skipped layers: -db, -api — assigned_to is a column on maint_task from MAINT-03-db. The reminder filter is MAINT-08-db.

## Acceptance criteria
- [ ] AC1 Given I pick a member, when I save, then assigned_to is that member and their avatar shows on the task.
- [ ] AC2 Given I clear the assignee, when I save, then assigned_to is null.
- [ ] AC3 Given the task is assigned, when the daily reminder runs, then only that member is a recipient.
- [ ] AC4 Given the device is offline, when I assign someone, then the change is queued.

## Design
Screen: DESIGN E5 and E2 — States: E5 edit; E2 populated. Interactions: pick an assignee.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Maintenance: tasks are readable offline and creating or editing a task is queued in the outbox.

## Module-off behaviour
Maintenance off (X7): the Maintenance tab is hidden and a maintenance-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: maint_task, member, profile  Endpoints: none  Types: MaintTask, Member
Schema change: no  Contract change: no

## Tests required
Unit: web/src/maintenance/Assignee.test.ts → AC1 assigned_to is the chosen member; AC2 clearing it writes null
Component: web/src/maintenance/Assignee.test.tsx → AC4 offline, AC3 the view model explains the reminder goes only to them. Interaction: pick and clear.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: n/a (TESTING §3 asks for the offline pattern on shopping and pantry)

## Dependencies
Blocked by: MAINT-03-web, MAINT-08-db, CORE-04-web  Blocks: none

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
none

# MAINT-15-ios — Assign a task

## Summary
A job can be given to one person, and the reminder for it goes only to them.

## Spec references
SPEC §4 MAINT-15; DESIGN E3, E5; TESTING §3 client rows

## Scope
In: The assignee picker on the task.
Out: The reminder query (MAINT-08-db). Skipped layers: -db, -api — assigned_to is a column on maint_task from MAINT-03-db. The reminder filter is MAINT-08-db.

## Acceptance criteria
- [ ] AC1 Given I pick a member, when I save, then assigned_to is that member and their avatar shows on the task.
- [ ] AC2 Given I clear the assignee, when I save, then assigned_to is null.
- [ ] AC3 Given the task is assigned, when the daily reminder runs, then only that member is a recipient.
- [ ] AC4 Given the device is offline, when I assign someone, then the change is queued.

## Design
Screen: DESIGN E5 and E2 — States: E5 edit; E2 populated. Interactions: pick an assignee.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Maintenance: tasks are readable offline and creating or editing a task is queued in the outbox.

## Module-off behaviour
Maintenance off (X7): the Maintenance tab is hidden and a maintenance-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: maint_task, member, profile  Endpoints: none  Types: MaintTask, Member
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/maintenance/Tests/AssigneeTests.swift → AC1 assigned_to is the chosen member; AC2 clearing it writes null
Component: apps/ios/AbodeNestlyTests/AssigneeViewTests.swift → AC4 offline, AC3 the view model explains the reminder goes only to them. Interaction: pick and clear.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: n/a (TESTING §3 asks for the offline pattern on shopping and pantry)

## Dependencies
Blocked by: MAINT-03-ios, MAINT-08-db, CORE-04-ios  Blocks: none

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
none

# MAINT-15-android — Assign a task

## Summary
A job can be given to one person, and the reminder for it goes only to them.

## Spec references
SPEC §4 MAINT-15; DESIGN E3, E5; TESTING §3 client rows

## Scope
In: The assignee picker on the task.
Out: The reminder query (MAINT-08-db). Skipped layers: -db, -api — assigned_to is a column on maint_task from MAINT-03-db. The reminder filter is MAINT-08-db.

## Acceptance criteria
- [ ] AC1 Given I pick a member, when I save, then assigned_to is that member and their avatar shows on the task.
- [ ] AC2 Given I clear the assignee, when I save, then assigned_to is null.
- [ ] AC3 Given the task is assigned, when the daily reminder runs, then only that member is a recipient.
- [ ] AC4 Given the device is offline, when I assign someone, then the change is queued.

## Design
Screen: DESIGN E5 and E2 — States: E5 edit; E2 populated. Interactions: pick an assignee.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Maintenance: tasks are readable offline and creating or editing a task is queued in the outbox.

## Module-off behaviour
Maintenance off (X7): the Maintenance tab is hidden and a maintenance-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: maint_task, member, profile  Endpoints: none  Types: MaintTask, Member
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/maintenance/src/test/java/app/abodenestly/maintenance/AssigneeTest.kt → AC1 assigned_to is the chosen member; AC2 clearing it writes null
Component: apps/android/maintenance/src/test/java/app/abodenestly/maintenance/AssigneeComposeTest.kt → AC4 offline, AC3 the view model explains the reminder goes only to them. Interaction: pick and clear.
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: n/a (TESTING §3 asks for the offline pattern on shopping and pantry)

## Dependencies
Blocked by: MAINT-03-android, MAINT-08-db, CORE-04-android  Blocks: none

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
none

# MAINT-16-db — Task consumables

## Summary
A task can list the things that should be bought before the job.

## Spec references
SPEC §4 MAINT-16; §8 TaskConsumable; DESIGN E5; TESTING §3 -db row

## Scope
In: task_consumable rows with name, quantity, and unit.
Out: The editor (MAINT-16 clients). Sending them (X3-api).

## Acceptance criteria
- [ ] AC1 Given a member, when they insert a consumable on their task, then the row is stored.
- [ ] AC2 Given quantity is null, when they insert, then the row is stored.
- [ ] AC3 Given a non-member, when they select the consumable, then they see nothing.

## Design
Screen: n/a — this layer has no DESIGN screen.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 Maintenance: tasks are readable offline and creating or editing a task is queued in the outbox.

## Module-off behaviour
Maintenance off (X7): the Maintenance tab is hidden and a maintenance-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: task_consumable  Endpoints: none  Types: TaskConsumable
Schema change: yes — supabase/migrations/20261108130800_MAINT-16_consumable.sql  Contract change: no

## Tests required
Unit: n/a (database behaviour is asserted in pgTAP, TESTING §2)
Component: n/a (no UI)
Integration: supabase/tests/MAINT-16_consumable.sql → AC1 insert; AC2 null quantity; AC3 non-member denied
E2E: n/a (covered by the client journeys for this ID)
Offline test: n/a (server-side)

## Dependencies
Blocked by: MAINT-03-db  Blocks: MAINT-16-api, MAINT-16-web, MAINT-16-ios, MAINT-16-android, X3-api

## Skill
.claude/skills/sql-supabase/SKILL.md

## Open questions
§4's acceptance is the send button, and DESIGN E5 includes the editor. Proposed default: editing the rows is in this story, and the send is X3.

# MAINT-16-api — Send consumables is X3

## Summary
Sending a task's consumables to a list is the cross-module call, owned by X3 so it is not built twice.

## Spec references
SPEC §4 MAINT-16; §9 X3; TESTING §3 -api row

## Scope
In: No handler in this story. The acceptance here is that MAINT-16 does not add a second route.
Out: X3-api implements POST /tasks/{id}/consumables-to-list.

## Acceptance criteria
- [ ] AC1 Given this story's package, when the routes are listed, then consumables-to-list appears once.
- [ ] AC2 Given shopping is off, when the X3 route is called, then it rejects and this story does not catch that error and retry.
- [ ] AC3 Given a task has no consumables, when the route runs, then the response is an empty list.

## Design
Screen: n/a — this layer has no DESIGN screen.
Deviations from the plan: none

## Offline behaviour
The send needs a network (DESIGN E3). This story adds no second path.

## Module-off behaviour
Shopping off hides the button (the §9 maintenance-only row). That behaviour is X3 and MAINT-16 clients.

## Data and contract
Tables: task_consumable, list_item  Endpoints: POST /tasks/{id}/consumables-to-list (implemented by X3-api)  Types: ListItem, TaskConsumable
Schema change: no  Contract change: no

## Tests required
Unit: api/test/MAINT-16-no-duplicate.test.ts → AC1 the route table contains the path once
Component: n/a (no UI)
Integration: n/a (the endpoint tests are X3-api). AC2 and AC3 are asserted in X3-api's integration test.
E2E: n/a (client journeys cover the user path)
Offline test: n/a (server-side; queued client writes are covered by the client offline test)

## Dependencies
Blocked by: MAINT-16-db, X3-api  Blocks: MAINT-16-web, MAINT-16-ios, MAINT-16-android

## Skill
.claude/skills/node-backend/SKILL.md

## Open questions
This story exists so MAINT-16 has an api layer without a second implementation. Proposed default: do not add code beyond a test that the route is not duplicated. AC2 and AC3 are owned by X3-api.

# MAINT-16-web — Send consumables to a list

## Summary
From a task, a person can send its consumables onto a shopping list.

## Spec references
SPEC §4 MAINT-16; DESIGN E3, E5; TESTING §8 E2E-MAINT-2

## Scope
In: The consumables editor and the Send to shopping list button.
Out: The route (X3-api). The badge on the list (SHOP-17). Maintenance only (the §9 matrix): the button is hidden. Shopping off: the button is hidden.

## Acceptance criteria
- [ ] AC1 Given the task has consumables and shopping is on, when I tap Send to shopping list, then the toast says how many items were added.
- [ ] AC2 Given shopping is off, when I open the task, then the button is hidden.
- [ ] AC3 Given the device is offline, when I look at the button, then it is disabled and the offline hint is shown.
- [ ] AC4 Given I add a consumable row, when I save the task, then the row is kept.
- [ ] AC5 Given the task has no consumables, when I open it, then the no-consumables state is shown.

## Design
Screen: DESIGN E3 and E5 — States: E3 recurring, one-off, overdue, no-consumables; E5 edit. Interactions: Send to shopping list; edit consumable rows.
Deviations from the plan: none

## Offline behaviour
DESIGN E3: the button needs the API. Editing the consumable rows follows the task offline rule (SPEC §6.5).

## Module-off behaviour
Maintenance off (X7): the Maintenance tab is hidden and a maintenance-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: task_consumable, list_item  Endpoints: POST /tasks/{id}/consumables-to-list  Types: TaskConsumable, ListItem
Schema change: no  Contract change: no

## Tests required
Unit: web/src/maintenance/Consumables.test.ts → AC3 offline does not call the route; AC4 the consumable upsert is queued
Component: web/src/maintenance/Consumables.test.tsx → AC1 toast, AC2 button hidden, AC5 no-consumables. Interactions: Send and edit rows.
Integration: n/a (no API handler in this story)
E2E: E2E-MAINT-2 web/e2e/Consumables.spec.ts
Offline test: n/a (TESTING §3 asks for the offline pattern on shopping and pantry)

## Dependencies
Blocked by: MAINT-16-api, X3-api, MAINT-16-db, SHOP-01-web  Blocks: none

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
none

# MAINT-16-ios — Send consumables to a list

## Summary
From a task, a person can send its consumables onto a shopping list.

## Spec references
SPEC §4 MAINT-16; DESIGN E3, E5; TESTING §8 E2E-MAINT-2

## Scope
In: The consumables editor and the Send to shopping list button.
Out: The route (X3-api). The badge on the list (SHOP-17). Maintenance only (the §9 matrix): the button is hidden. Shopping off: the button is hidden.

## Acceptance criteria
- [ ] AC1 Given the task has consumables and shopping is on, when I tap Send to shopping list, then the toast says how many items were added.
- [ ] AC2 Given shopping is off, when I open the task, then the button is hidden.
- [ ] AC3 Given the device is offline, when I look at the button, then it is disabled and the offline hint is shown.
- [ ] AC4 Given I add a consumable row, when I save the task, then the row is kept.
- [ ] AC5 Given the task has no consumables, when I open it, then the no-consumables state is shown.

## Design
Screen: DESIGN E3 and E5 — States: E3 recurring, one-off, overdue, no-consumables; E5 edit. Interactions: Send to shopping list; edit consumable rows.
Deviations from the plan: none

## Offline behaviour
DESIGN E3: the button needs the API. Editing the consumable rows follows the task offline rule (SPEC §6.5).

## Module-off behaviour
Maintenance off (X7): the Maintenance tab is hidden and a maintenance-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: task_consumable, list_item  Endpoints: POST /tasks/{id}/consumables-to-list  Types: TaskConsumable, ListItem
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/maintenance/Tests/ConsumablesTests.swift → AC3 offline does not call the route; AC4 the consumable upsert is queued
Component: apps/ios/AbodeNestlyTests/ConsumablesViewTests.swift → AC1 toast, AC2 button hidden, AC5 no-consumables. Interactions: Send and edit rows.
Integration: n/a (no API handler in this story)
E2E: E2E-MAINT-2 apps/ios/AbodeNestlyUITests/ConsumablesUITests.swift
Offline test: n/a (TESTING §3 asks for the offline pattern on shopping and pantry)

## Dependencies
Blocked by: MAINT-16-api, X3-api, MAINT-16-db, SHOP-01-ios  Blocks: none

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
none

# MAINT-16-android — Send consumables to a list

## Summary
From a task, a person can send its consumables onto a shopping list.

## Spec references
SPEC §4 MAINT-16; DESIGN E3, E5; TESTING §8 E2E-MAINT-2

## Scope
In: The consumables editor and the Send to shopping list button.
Out: The route (X3-api). The badge on the list (SHOP-17). Maintenance only (the §9 matrix): the button is hidden. Shopping off: the button is hidden.

## Acceptance criteria
- [ ] AC1 Given the task has consumables and shopping is on, when I tap Send to shopping list, then the toast says how many items were added.
- [ ] AC2 Given shopping is off, when I open the task, then the button is hidden.
- [ ] AC3 Given the device is offline, when I look at the button, then it is disabled and the offline hint is shown.
- [ ] AC4 Given I add a consumable row, when I save the task, then the row is kept.
- [ ] AC5 Given the task has no consumables, when I open it, then the no-consumables state is shown.

## Design
Screen: DESIGN E3 and E5 — States: E3 recurring, one-off, overdue, no-consumables; E5 edit. Interactions: Send to shopping list; edit consumable rows.
Deviations from the plan: none

## Offline behaviour
DESIGN E3: the button needs the API. Editing the consumable rows follows the task offline rule (SPEC §6.5).

## Module-off behaviour
Maintenance off (X7): the Maintenance tab is hidden and a maintenance-table mutation is rejected with module_disabled. This screen is not reachable and must not error.

## Data and contract
Tables: task_consumable, list_item  Endpoints: POST /tasks/{id}/consumables-to-list  Types: TaskConsumable, ListItem
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/maintenance/src/test/java/app/abodenestly/maintenance/ConsumablesTest.kt → AC3 offline does not call the route; AC4 the consumable upsert is queued
Component: apps/android/maintenance/src/test/java/app/abodenestly/maintenance/ConsumablesComposeTest.kt → AC1 toast, AC2 button hidden, AC5 no-consumables. Interactions: Send and edit rows.
Integration: n/a (no API handler in this story)
E2E: E2E-MAINT-2 apps/android/maintenance/src/androidTest/java/app/abodenestly/maintenance/ConsumablesE2ETest.kt
Offline test: n/a (TESTING §3 asks for the offline pattern on shopping and pantry)

## Dependencies
Blocked by: MAINT-16-api, X3-api, MAINT-16-db, SHOP-01-android  Blocks: none

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
none
