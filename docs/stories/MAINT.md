# Maintenance stories

Source of truth: `docs/SPEC.md` §4.4. Offline lines are §6.5 (Maintenance row). Module-off lines are §9 (rule X7 and the matrix). One story per feature ID. Where the feature is more than one of API, web, iOS, and Android, it is split with the suffix `-api`, `-web`, `-ios`, or `-android`. Order inside a feature is API, then web, then iOS, then Android.

An open question is unresolved in the spec. Do not pick an answer while implementing the story.

## MAINT-01-web — House profile

**User value:** The household can record the house's name, address, build year, and notes.

**Acceptance criteria:**
- [ ] The user can set the house name, address, build year, and notes.

**Offline:** §6.5 Maintenance: readable offline. Creating or editing this record is not listed as an offline write (offline writes are log entries and tasks; attachments queue until online).

**Module off:** Maintenance off (X7): the maintenance tab is hidden and a maintenance-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `CORE-05-web`, `CORE-07-web`

**Skill:** `.claude/skills/react-web/SKILL.md`

**Open question:** §6.5 lists offline writes for log entries and tasks only. Confirm house-profile edits are online-only.

## MAINT-01-ios — House profile

**User value:** The household can record the house's name, address, build year, and notes.

**Acceptance criteria:**
- [ ] The user can set the house name, address, build year, and notes.

**Offline:** §6.5 Maintenance: readable offline. Creating or editing this record is not listed as an offline write (offline writes are log entries and tasks; attachments queue until online).

**Module off:** Maintenance off (X7): the maintenance tab is hidden and a maintenance-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `CORE-05-ios`, `CORE-07-ios`

**Skill:** `.claude/skills/swift-ios/SKILL.md`

**Open question:** §6.5 lists offline writes for log entries and tasks only. Confirm house-profile edits are online-only.

## MAINT-01-android — House profile

**User value:** The household can record the house's name, address, build year, and notes.

**Acceptance criteria:**
- [ ] The user can set the house name, address, build year, and notes.

**Offline:** §6.5 Maintenance: readable offline. Creating or editing this record is not listed as an offline write (offline writes are log entries and tasks; attachments queue until online).

**Module off:** Maintenance off (X7): the maintenance tab is hidden and a maintenance-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `CORE-05-android`, `CORE-07-android`

**Skill:** `.claude/skills/kotlin-android/SKILL.md`

**Open question:** §6.5 lists offline writes for log entries and tasks only. Confirm house-profile edits are online-only.

## MAINT-02-web — Assets

**User value:** The household can keep a record of a thing it owns, including warranty, cost, and files.

**Acceptance criteria:**
- [ ] The user can record kind, brand, model, serial, room, purchase date, purchase price, warranty end, and notes.
- [ ] The user can attach files (CORE-11).

**Offline:** §6.5 Maintenance: readable offline. Creating or editing this record is not listed as an offline write (offline writes are log entries and tasks; attachments queue until online). Attachments queue until online.

**Module off:** Maintenance off (X7): the maintenance tab is hidden and a maintenance-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `MAINT-01-web`, `CORE-11-web`

**Skill:** `.claude/skills/react-web/SKILL.md`

**Open question:** §6.5 does not list asset create or edit as an offline write. Confirm those edits are online-only.

## MAINT-02-ios — Assets

**User value:** The household can keep a record of a thing it owns, including warranty, cost, and files.

**Acceptance criteria:**
- [ ] The user can record kind, brand, model, serial, room, purchase date, purchase price, warranty end, and notes.
- [ ] The user can attach files (CORE-11).

**Offline:** §6.5 Maintenance: readable offline. Creating or editing this record is not listed as an offline write (offline writes are log entries and tasks; attachments queue until online). Attachments queue until online.

**Module off:** Maintenance off (X7): the maintenance tab is hidden and a maintenance-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `MAINT-01-ios`, `CORE-11-ios`

**Skill:** `.claude/skills/swift-ios/SKILL.md`

**Open question:** §6.5 does not list asset create or edit as an offline write. Confirm those edits are online-only.

## MAINT-02-android — Assets

**User value:** The household can keep a record of a thing it owns, including warranty, cost, and files.

**Acceptance criteria:**
- [ ] The user can record kind, brand, model, serial, room, purchase date, purchase price, warranty end, and notes.
- [ ] The user can attach files (CORE-11).

**Offline:** §6.5 Maintenance: readable offline. Creating or editing this record is not listed as an offline write (offline writes are log entries and tasks; attachments queue until online). Attachments queue until online.

**Module off:** Maintenance off (X7): the maintenance tab is hidden and a maintenance-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `MAINT-01-android`, `CORE-11-android`

**Skill:** `.claude/skills/kotlin-android/SKILL.md`

**Open question:** §6.5 does not list asset create or edit as an offline write. Confirm those edits are online-only.

## MAINT-03-api — Roll a recurring task forward

**User value:** Finishing a repeating job moves the next due date forward instead of leaving it in the past.

**Acceptance criteria:**
- [ ] A recurring task stores an RRULE and a next due date.
- [ ] On completion, next due rolls forward by that RRULE.

**Offline:** §6.5 allows creating and editing tasks offline. The roll runs when the completion is accepted on the server, including a completion that was queued.

**Module off:** Maintenance off (X7): the completion is rejected, so the due date does not roll.

**Dependencies:** none

**Skill:** `.claude/skills/node-backend/SKILL.md`; `.claude/skills/sql-supabase/SKILL.md`

**Open question:** §14 says rolling next due is done in the API because it needs `rrule`, on the explicit done path. That path is `POST /tasks/{id}/done` (MAINT-05). Does this story own the roll helper, with MAINT-05-api calling it?

## MAINT-03-web — Recurring tasks

**User value:** The household can set a job that repeats and always shows the next time it is due.

**Acceptance criteria:**
- [ ] The user can create a task with an RRULE.
- [ ] The task shows its next due date.
- [ ] Completing it rolls next due forward (MAINT-03-api).

**Offline:** §6.5 Maintenance: readable offline. Creating and editing tasks works offline and is queued until sync.

**Module off:** Maintenance off (X7): the maintenance tab is hidden and a maintenance-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `MAINT-03-api`, `MAINT-01-web`, `SHOP-08-web`

**Skill:** `.claude/skills/react-web/SKILL.md`

## MAINT-03-ios — Recurring tasks

**User value:** The household can set a job that repeats and always shows the next time it is due.

**Acceptance criteria:**
- [ ] The user can create a task with an RRULE.
- [ ] The task shows its next due date.
- [ ] Completing it rolls next due forward (MAINT-03-api).

**Offline:** §6.5 Maintenance: readable offline. Creating and editing tasks works offline and is queued until sync.

**Module off:** Maintenance off (X7): the maintenance tab is hidden and a maintenance-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `MAINT-03-api`, `MAINT-01-ios`, `SHOP-08-ios`

**Skill:** `.claude/skills/swift-ios/SKILL.md`

## MAINT-03-android — Recurring tasks

**User value:** The household can set a job that repeats and always shows the next time it is due.

**Acceptance criteria:**
- [ ] The user can create a task with an RRULE.
- [ ] The task shows its next due date.
- [ ] Completing it rolls next due forward (MAINT-03-api).

**Offline:** §6.5 Maintenance: readable offline. Creating and editing tasks works offline and is queued until sync.

**Module off:** Maintenance off (X7): the maintenance tab is hidden and a maintenance-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `MAINT-03-api`, `MAINT-01-android`, `SHOP-08-android`

**Skill:** `.claude/skills/kotlin-android/SKILL.md`

## MAINT-04-web — One-off tasks

**User value:** The household can add a job that happens once and does not reschedule itself.

**Acceptance criteria:**
- [ ] The user can create a task with `rrule` null.
- [ ] Completing it does not roll a next due date forward.

**Offline:** §6.5 Maintenance: readable offline. Creating and editing tasks works offline and is queued until sync.

**Module off:** Maintenance off (X7): the maintenance tab is hidden and a maintenance-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `MAINT-03-web`

**Skill:** `.claude/skills/react-web/SKILL.md`

## MAINT-04-ios — One-off tasks

**User value:** The household can add a job that happens once and does not reschedule itself.

**Acceptance criteria:**
- [ ] The user can create a task with `rrule` null.
- [ ] Completing it does not roll a next due date forward.

**Offline:** §6.5 Maintenance: readable offline. Creating and editing tasks works offline and is queued until sync.

**Module off:** Maintenance off (X7): the maintenance tab is hidden and a maintenance-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `MAINT-03-ios`

**Skill:** `.claude/skills/swift-ios/SKILL.md`

## MAINT-04-android — One-off tasks

**User value:** The household can add a job that happens once and does not reschedule itself.

**Acceptance criteria:**
- [ ] The user can create a task with `rrule` null.
- [ ] Completing it does not roll a next due date forward.

**Offline:** §6.5 Maintenance: readable offline. Creating and editing tasks works offline and is queued until sync.

**Module off:** Maintenance off (X7): the maintenance tab is hidden and a maintenance-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `MAINT-03-android`

**Skill:** `.claude/skills/kotlin-android/SKILL.md`

## MAINT-05-api — Mark a task done into a log entry

**User value:** Marking a job done records when, who, the contractor, the cost, and the notes, and a repeating job moves its due date.

**Acceptance criteria:**
- [ ] `POST /tasks/{id}/done` accepts date, who, contractor, cost, and notes.
- [ ] It writes a `maint_log` row.
- [ ] For a recurring task it returns the task with next due rolled (MAINT-03-api).

**Offline:** §6.5 allows the log entry and the task edit offline. See the open question for how that meets this endpoint.

**Module off:** Maintenance off (X7): the completion is rejected with `module_disabled`.

**Dependencies:** `MAINT-03-api`

**Skill:** `.claude/skills/node-backend/SKILL.md`

**Open question:** §6.5 says log entries and tasks are writable offline, and §7 completes a task through `POST /tasks/{id}/done`. Does an offline completion queue a log and a task update through the outbox, with the server applying the same roll, or is completion online-only?

## MAINT-05-web — Mark done

**User value:** Marking a task done asks for the date, who did it, the contractor, the cost, the notes, and photos.

**Acceptance criteria:**
- [ ] Marking a task done prompts for date, who, contractor, cost, notes, and photos.
- [ ] Confirming creates the log entry from MAINT-05-api.
- [ ] Photos use CORE-11 and queue until online (§6.5).

**Offline:** §6.5 Maintenance: readable offline. Creating and editing log entries works offline and is queued until sync. Photos queue until online.

**Module off:** Maintenance off (X7): the maintenance tab is hidden and a maintenance-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `MAINT-05-api`, `MAINT-03-web`, `CORE-11-web`

**Skill:** `.claude/skills/react-web/SKILL.md`

**Open question:** Same offline-completion question as MAINT-05-api.

## MAINT-05-ios — Mark done

**User value:** Marking a task done asks for the date, who did it, the contractor, the cost, the notes, and photos.

**Acceptance criteria:**
- [ ] Marking a task done prompts for date, who, contractor, cost, notes, and photos.
- [ ] Confirming creates the log entry from MAINT-05-api.
- [ ] Photos use CORE-11 and queue until online (§6.5).

**Offline:** §6.5 Maintenance: readable offline. Creating and editing log entries works offline and is queued until sync. Photos queue until online.

**Module off:** Maintenance off (X7): the maintenance tab is hidden and a maintenance-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `MAINT-05-api`, `MAINT-03-ios`, `CORE-11-ios`

**Skill:** `.claude/skills/swift-ios/SKILL.md`

**Open question:** Same offline-completion question as MAINT-05-api.

## MAINT-05-android — Mark done

**User value:** Marking a task done asks for the date, who did it, the contractor, the cost, the notes, and photos.

**Acceptance criteria:**
- [ ] Marking a task done prompts for date, who, contractor, cost, notes, and photos.
- [ ] Confirming creates the log entry from MAINT-05-api.
- [ ] Photos use CORE-11 and queue until online (§6.5).

**Offline:** §6.5 Maintenance: readable offline. Creating and editing log entries works offline and is queued until sync. Photos queue until online.

**Module off:** Maintenance off (X7): the maintenance tab is hidden and a maintenance-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `MAINT-05-api`, `MAINT-03-android`, `CORE-11-android`

**Skill:** `.claude/skills/kotlin-android/SKILL.md`

**Open question:** Same offline-completion question as MAINT-05-api.

## MAINT-06-web — Log entry CRUD

**User value:** A person can fix or remove a log entry after the job was recorded.

**Acceptance criteria:**
- [ ] The user can create a log entry.
- [ ] The user can read log entries.
- [ ] The user can update a log entry.
- [ ] The user can delete a log entry.

**Offline:** §6.5 Maintenance: readable offline. Creating and editing log entries works offline and is queued until sync.

**Module off:** Maintenance off (X7): the maintenance tab is hidden and a maintenance-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `MAINT-05-web`

**Skill:** `.claude/skills/react-web/SKILL.md`

**Open question:** The §4 acceptance summary is empty. Confirm the editable fields are the `maint_log` columns (done date, who, contractor, cost, notes), with photos covered by CORE-11 rather than this story.

## MAINT-06-ios — Log entry CRUD

**User value:** A person can fix or remove a log entry after the job was recorded.

**Acceptance criteria:**
- [ ] The user can create a log entry.
- [ ] The user can read log entries.
- [ ] The user can update a log entry.
- [ ] The user can delete a log entry.

**Offline:** §6.5 Maintenance: readable offline. Creating and editing log entries works offline and is queued until sync.

**Module off:** Maintenance off (X7): the maintenance tab is hidden and a maintenance-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `MAINT-05-ios`

**Skill:** `.claude/skills/swift-ios/SKILL.md`

**Open question:** The §4 acceptance summary is empty. Confirm the editable fields are the `maint_log` columns (done date, who, contractor, cost, notes), with photos covered by CORE-11 rather than this story.

## MAINT-06-android — Log entry CRUD

**User value:** A person can fix or remove a log entry after the job was recorded.

**Acceptance criteria:**
- [ ] The user can create a log entry.
- [ ] The user can read log entries.
- [ ] The user can update a log entry.
- [ ] The user can delete a log entry.

**Offline:** §6.5 Maintenance: readable offline. Creating and editing log entries works offline and is queued until sync.

**Module off:** Maintenance off (X7): the maintenance tab is hidden and a maintenance-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `MAINT-05-android`

**Skill:** `.claude/skills/kotlin-android/SKILL.md`

**Open question:** The §4 acceptance summary is empty. Confirm the editable fields are the `maint_log` columns (done date, who, contractor, cost, notes), with photos covered by CORE-11 rather than this story.

## MAINT-07-web — Due and overdue

**User value:** The household can see which jobs are due and which are already late.

**Acceptance criteria:**
- [ ] The view shows due tasks.
- [ ] The view shows overdue tasks.

**Offline:** §6.5 Maintenance: this view is readable offline.

**Module off:** Maintenance off (X7): the maintenance tab is hidden and a maintenance-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `MAINT-03-web`, `MAINT-04-web`

**Skill:** `.claude/skills/react-web/SKILL.md`

**Open question:** The §4 acceptance summary is empty. It does not say whether due means `next_due` on today, or today and earlier, and the overdue cut is the other half of that.

## MAINT-07-ios — Due and overdue

**User value:** The household can see which jobs are due and which are already late.

**Acceptance criteria:**
- [ ] The view shows due tasks.
- [ ] The view shows overdue tasks.

**Offline:** §6.5 Maintenance: this view is readable offline.

**Module off:** Maintenance off (X7): the maintenance tab is hidden and a maintenance-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `MAINT-03-ios`, `MAINT-04-ios`

**Skill:** `.claude/skills/swift-ios/SKILL.md`

**Open question:** The §4 acceptance summary is empty. It does not say whether due means `next_due` on today, or today and earlier, and the overdue cut is the other half of that.

## MAINT-07-android — Due and overdue

**User value:** The household can see which jobs are due and which are already late.

**Acceptance criteria:**
- [ ] The view shows due tasks.
- [ ] The view shows overdue tasks.

**Offline:** §6.5 Maintenance: this view is readable offline.

**Module off:** Maintenance off (X7): the maintenance tab is hidden and a maintenance-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `MAINT-03-android`, `MAINT-04-android`

**Skill:** `.claude/skills/kotlin-android/SKILL.md`

**Open question:** The §4 acceptance summary is empty. It does not say whether due means `next_due` on today, or today and earlier, and the overdue cut is the other half of that.

## MAINT-08-api — Daily task reminders

**User value:** People get a daily reminder for jobs that are due or overdue, and an assigned job pings only the person it was given to.

**Acceptance criteria:**
- [ ] A daily job enqueues a task-due notice for tasks due or overdue (§10).
- [ ] The notice goes to the assignee when `assigned_to` is set, and to every member when it is not.
- [ ] The job is deduplicated per task per window and respects `notification_pref`.
- [ ] The household's maintenance module must be on.
- [ ] Delivery uses the sender from PANT-16-api.

**Offline:** Sending needs a network. Task due dates may have been edited offline (§6.5).

**Module off:** Maintenance off: the task-due insert does not run. A user with the preference off is skipped.

**Dependencies:** `PANT-16-api`, `MAINT-03-api`, `CORE-12-web`, `CORE-12-ios`, `CORE-12-android`

**Skill:** `.claude/skills/node-backend/SKILL.md`; `.claude/skills/sql-supabase/SKILL.md`

## MAINT-08-web — Push reminders for tasks

**User value:** A person gets a daily push when a maintenance job is due or overdue.

**Acceptance criteria:**
- [ ] The device receives the daily task reminder from MAINT-08-api.
- [ ] The notice respects the per-household preference from CORE-12.

**Offline:** Receiving a push needs a network. The task list is readable offline (§6.5).

**Module off:** Maintenance off (X7): the maintenance tab is hidden and a maintenance-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error. No task reminder is sent while maintenance is off.

**Dependencies:** `MAINT-08-api`, `CORE-12-web`, `MAINT-07-web`

**Skill:** `.claude/skills/react-web/SKILL.md`

**Open question:** Same inbox question as PANT-16: §10 mentions an in-app inbox and §4 does not.

## MAINT-08-ios — Push reminders for tasks

**User value:** A person gets a daily push when a maintenance job is due or overdue.

**Acceptance criteria:**
- [ ] The device receives the daily task reminder from MAINT-08-api.
- [ ] The notice respects the per-household preference from CORE-12.

**Offline:** Receiving a push needs a network. The task list is readable offline (§6.5).

**Module off:** Maintenance off (X7): the maintenance tab is hidden and a maintenance-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error. No task reminder is sent while maintenance is off.

**Dependencies:** `MAINT-08-api`, `CORE-12-ios`, `MAINT-07-ios`

**Skill:** `.claude/skills/swift-ios/SKILL.md`

**Open question:** Same inbox question as PANT-16: §10 mentions an in-app inbox and §4 does not.

## MAINT-08-android — Push reminders for tasks

**User value:** A person gets a daily push when a maintenance job is due or overdue.

**Acceptance criteria:**
- [ ] The device receives the daily task reminder from MAINT-08-api.
- [ ] The notice respects the per-household preference from CORE-12.

**Offline:** Receiving a push needs a network. The task list is readable offline (§6.5).

**Module off:** Maintenance off (X7): the maintenance tab is hidden and a maintenance-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error. No task reminder is sent while maintenance is off.

**Dependencies:** `MAINT-08-api`, `CORE-12-android`, `MAINT-07-android`

**Skill:** `.claude/skills/kotlin-android/SKILL.md`

**Open question:** Same inbox question as PANT-16: §10 mentions an in-app inbox and §4 does not.

## MAINT-09-web — Contractor contacts

**User value:** The household can keep a contractor's name, trade, phone, and email.

**Acceptance criteria:**
- [ ] The user can record a contractor name, trade, phone, and email.

**Offline:** §6.5 Maintenance: readable offline. Creating or editing this record is not listed as an offline write (offline writes are log entries and tasks; attachments queue until online).

**Module off:** Maintenance off (X7): the maintenance tab is hidden and a maintenance-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `MAINT-01-web`

**Skill:** `.claude/skills/react-web/SKILL.md`

**Open question:** §6.5 does not list contractor edits as offline writes. Confirm they are online-only. §8 also has contractor notes, which §4's acceptance summary does not mention.

## MAINT-09-ios — Contractor contacts

**User value:** The household can keep a contractor's name, trade, phone, and email.

**Acceptance criteria:**
- [ ] The user can record a contractor name, trade, phone, and email.

**Offline:** §6.5 Maintenance: readable offline. Creating or editing this record is not listed as an offline write (offline writes are log entries and tasks; attachments queue until online).

**Module off:** Maintenance off (X7): the maintenance tab is hidden and a maintenance-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `MAINT-01-ios`

**Skill:** `.claude/skills/swift-ios/SKILL.md`

**Open question:** §6.5 does not list contractor edits as offline writes. Confirm they are online-only. §8 also has contractor notes, which §4's acceptance summary does not mention.

## MAINT-09-android — Contractor contacts

**User value:** The household can keep a contractor's name, trade, phone, and email.

**Acceptance criteria:**
- [ ] The user can record a contractor name, trade, phone, and email.

**Offline:** §6.5 Maintenance: readable offline. Creating or editing this record is not listed as an offline write (offline writes are log entries and tasks; attachments queue until online).

**Module off:** Maintenance off (X7): the maintenance tab is hidden and a maintenance-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `MAINT-01-android`

**Skill:** `.claude/skills/kotlin-android/SKILL.md`

**Open question:** §6.5 does not list contractor edits as offline writes. Confirm they are online-only. §8 also has contractor notes, which §4's acceptance summary does not mention.

## MAINT-10-api — Maintenance cost report

**User value:** The household can see what upkeep cost in a year, and what each asset cost.

**Acceptance criteria:**
- [ ] `GET /reports/maintenance-costs` returns totals per year and per asset.

**Offline:** The report needs a network. The log costs it sums may have been written offline (§6.5).

**Module off:** Maintenance off: the report is rejected.

**Dependencies:** `MAINT-05-api`

**Skill:** `.claude/skills/node-backend/SKILL.md`

**Open question:** §8 `CostReport` also has a per-month breakdown and a currency. §4 says per year and per asset only. Is the monthly breakdown part of this story?

## MAINT-10-web — Cost reports

**User value:** A person can see maintenance spend for a year and for each asset.

**Acceptance criteria:**
- [ ] The screen shows cost per year.
- [ ] The screen shows cost per asset.
- [ ] The figures come from MAINT-10-api.

**Offline:** §6.5: the log is readable offline. Fetching the report needs a network.

**Module off:** Maintenance off (X7): the maintenance tab is hidden and a maintenance-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `MAINT-10-api`, `MAINT-06-web`

**Skill:** `.claude/skills/react-web/SKILL.md`

## MAINT-10-ios — Cost reports

**User value:** A person can see maintenance spend for a year and for each asset.

**Acceptance criteria:**
- [ ] The screen shows cost per year.
- [ ] The screen shows cost per asset.
- [ ] The figures come from MAINT-10-api.

**Offline:** §6.5: the log is readable offline. Fetching the report needs a network.

**Module off:** Maintenance off (X7): the maintenance tab is hidden and a maintenance-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `MAINT-10-api`, `MAINT-06-ios`

**Skill:** `.claude/skills/swift-ios/SKILL.md`

## MAINT-10-android — Cost reports

**User value:** A person can see maintenance spend for a year and for each asset.

**Acceptance criteria:**
- [ ] The screen shows cost per year.
- [ ] The screen shows cost per asset.
- [ ] The figures come from MAINT-10-api.

**Offline:** §6.5: the log is readable offline. Fetching the report needs a network.

**Module off:** Maintenance off (X7): the maintenance tab is hidden and a maintenance-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `MAINT-10-api`, `MAINT-06-android`

**Skill:** `.claude/skills/kotlin-android/SKILL.md`

## MAINT-11-api — Warranty expiry reminders

**User value:** Thirty days before a warranty ends, the household is told.

**Acceptance criteria:**
- [ ] A notice is enqueued 30 days before `warranty_until` (§10).
- [ ] Delivery uses the sender from PANT-16-api.
- [ ] The notice respects `notification_pref`.

**Offline:** Sending needs a network. Warranty dates may have been edited online only if asset edits are online (see MAINT-02).

**Module off:** §9 does not give warranties a separate switch. Maintenance off should stop maintenance notices; see the open question.

**Dependencies:** `PANT-16-api`, `CORE-12-web`, `CORE-12-ios`, `CORE-12-android`

**Skill:** `.claude/skills/node-backend/SKILL.md`; `.claude/skills/sql-supabase/SKILL.md`

**Open question:** §10 says the daily job respects `notification_pref`. The warranty insert in §14 does not join `notification_pref` and does not check `module_maintenance`. Which rule wins?

## MAINT-11-web — Warranty expiry reminders

**User value:** A person is warned 30 days before a warranty runs out.

**Acceptance criteria:**
- [ ] The device receives the warranty notice from MAINT-11-api.
- [ ] The notice respects the per-household preference from CORE-12.

**Offline:** Receiving a push needs a network. The asset is readable offline (§6.5).

**Module off:** Maintenance off (X7): the maintenance tab is hidden and a maintenance-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `MAINT-11-api`, `CORE-12-web`, `MAINT-02-web`

**Skill:** `.claude/skills/react-web/SKILL.md`

## MAINT-11-ios — Warranty expiry reminders

**User value:** A person is warned 30 days before a warranty runs out.

**Acceptance criteria:**
- [ ] The device receives the warranty notice from MAINT-11-api.
- [ ] The notice respects the per-household preference from CORE-12.

**Offline:** Receiving a push needs a network. The asset is readable offline (§6.5).

**Module off:** Maintenance off (X7): the maintenance tab is hidden and a maintenance-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `MAINT-11-api`, `CORE-12-ios`, `MAINT-02-ios`

**Skill:** `.claude/skills/swift-ios/SKILL.md`

## MAINT-11-android — Warranty expiry reminders

**User value:** A person is warned 30 days before a warranty runs out.

**Acceptance criteria:**
- [ ] The device receives the warranty notice from MAINT-11-api.
- [ ] The notice respects the per-household preference from CORE-12.

**Offline:** Receiving a push needs a network. The asset is readable offline (§6.5).

**Module off:** Maintenance off (X7): the maintenance tab is hidden and a maintenance-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `MAINT-11-api`, `CORE-12-android`, `MAINT-02-android`

**Skill:** `.claude/skills/kotlin-android/SKILL.md`

## MAINT-12-api — Apply a checklist template

**User value:** Applying a seasonal checklist creates the tasks, whether the checklist is one of the shared ones or one the household wrote.

**Acceptance criteria:**
- [ ] Global templates and household templates can be applied.
- [ ] `POST /templates/{id}/apply` creates tasks from the template.

**Offline:** §6.5 allows the created tasks to be edited offline after they exist. Applying the template is an API call.

**Module off:** Maintenance off (X7): applying a template is rejected, so no tasks are created.

**Dependencies:** `MAINT-03-api`

**Skill:** `.claude/skills/node-backend/SKILL.md`; `.claude/skills/sql-supabase/SKILL.md`

**Open question:** `checklist_template` is not in the §8 `SyncTable` list. Confirm apply is online-only. §14 lets members write household templates and only read global ones. §4 does not say who may create a household template.

## MAINT-12-web — Seasonal checklist templates

**User value:** The household can apply a global or household checklist and get the tasks.

**Acceptance criteria:**
- [ ] The user can see global templates and household templates.
- [ ] Apply creates tasks (MAINT-12-api).

**Offline:** Created tasks follow §6.5 and can be edited offline. Loading templates is the open question on MAINT-12-api.

**Module off:** Maintenance off (X7): the maintenance tab is hidden and a maintenance-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `MAINT-12-api`, `MAINT-03-web`, `MAINT-04-web`

**Skill:** `.claude/skills/react-web/SKILL.md`

**Open question:** §4 says household templates exist and does not say whether this story includes creating or editing them.

## MAINT-12-ios — Seasonal checklist templates

**User value:** The household can apply a global or household checklist and get the tasks.

**Acceptance criteria:**
- [ ] The user can see global templates and household templates.
- [ ] Apply creates tasks (MAINT-12-api).

**Offline:** Created tasks follow §6.5 and can be edited offline. Loading templates is the open question on MAINT-12-api.

**Module off:** Maintenance off (X7): the maintenance tab is hidden and a maintenance-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `MAINT-12-api`, `MAINT-03-ios`, `MAINT-04-ios`

**Skill:** `.claude/skills/swift-ios/SKILL.md`

**Open question:** §4 says household templates exist and does not say whether this story includes creating or editing them.

## MAINT-12-android — Seasonal checklist templates

**User value:** The household can apply a global or household checklist and get the tasks.

**Acceptance criteria:**
- [ ] The user can see global templates and household templates.
- [ ] Apply creates tasks (MAINT-12-api).

**Offline:** Created tasks follow §6.5 and can be edited offline. Loading templates is the open question on MAINT-12-api.

**Module off:** Maintenance off (X7): the maintenance tab is hidden and a maintenance-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `MAINT-12-api`, `MAINT-03-android`, `MAINT-04-android`

**Skill:** `.claude/skills/kotlin-android/SKILL.md`

**Open question:** §4 says household templates exist and does not say whether this story includes creating or editing them.

## MAINT-13-api — Export the maintenance log to PDF

**User value:** The household can download the maintenance log as a PDF.

**Acceptance criteria:**
- [ ] `GET /export/maintenance.pdf` returns `application/pdf` for the household.
- [ ] Optional `from` and `to` limit the range (§7).

**Offline:** Generating the file needs a network. The log rows may have been written offline (§6.5).

**Module off:** Maintenance off: the export is rejected.

**Dependencies:** `MAINT-05-api`

**Skill:** `.claude/skills/node-backend/SKILL.md`

**Open question:** §4 does not say whether `from` and `to` are required in v1 or only the unfiltered log. §7 lists both as optional.

## MAINT-13-web — Export the maintenance log to PDF

**User value:** A person can export the full maintenance log as a PDF.

**Acceptance criteria:**
- [ ] The user can request the PDF from `GET /export/maintenance.pdf`.
- [ ] The file that comes back is the maintenance log.

**Offline:** The export request needs a network. The log it is built from is readable offline (§6.5).

**Module off:** Maintenance off (X7): the maintenance tab is hidden and a maintenance-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `MAINT-13-api`, `MAINT-06-web`

**Skill:** `.claude/skills/react-web/SKILL.md`

## MAINT-13-ios — Export the maintenance log to PDF

**User value:** A person can export the full maintenance log as a PDF.

**Acceptance criteria:**
- [ ] The user can request the PDF from `GET /export/maintenance.pdf`.
- [ ] The file that comes back is the maintenance log.

**Offline:** The export request needs a network. The log it is built from is readable offline (§6.5).

**Module off:** Maintenance off (X7): the maintenance tab is hidden and a maintenance-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `MAINT-13-api`, `MAINT-06-ios`

**Skill:** `.claude/skills/swift-ios/SKILL.md`

## MAINT-13-android — Export the maintenance log to PDF

**User value:** A person can export the full maintenance log as a PDF.

**Acceptance criteria:**
- [ ] The user can request the PDF from `GET /export/maintenance.pdf`.
- [ ] The file that comes back is the maintenance log.

**Offline:** The export request needs a network. The log it is built from is readable offline (§6.5).

**Module off:** Maintenance off (X7): the maintenance tab is hidden and a maintenance-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `MAINT-13-api`, `MAINT-06-android`

**Skill:** `.claude/skills/kotlin-android/SKILL.md`

## MAINT-14-web — Asset QR labels

**User value:** The household can print a QR label and scanning it opens that asset.

**Acceptance criteria:**
- [ ] The user can print a QR label for an asset.
- [ ] Scanning that QR opens the asset.

**Offline:** §6.5: the asset is readable offline, so a scan that this device can resolve locally opens it offline. Printing is not listed as an offline write.

**Module off:** Maintenance off (X7): the maintenance tab is hidden and a maintenance-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `MAINT-02-web`

**Skill:** `.claude/skills/react-web/SKILL.md`

**Open question:** §8 gives `asset.qr_code` a server default. §4 does not say what the QR encodes, or whether a scan from outside the app must deep-link in. There is no API story because §7 has no QR endpoint.

## MAINT-14-ios — Asset QR labels

**User value:** The household can print a QR label and scanning it opens that asset.

**Acceptance criteria:**
- [ ] The user can print a QR label for an asset.
- [ ] Scanning that QR opens the asset.

**Offline:** §6.5: the asset is readable offline, so a scan that this device can resolve locally opens it offline. Printing is not listed as an offline write.

**Module off:** Maintenance off (X7): the maintenance tab is hidden and a maintenance-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `MAINT-02-ios`

**Skill:** `.claude/skills/swift-ios/SKILL.md`

**Open question:** §8 gives `asset.qr_code` a server default. §4 does not say what the QR encodes, or whether a scan from outside the app must deep-link in. There is no API story because §7 has no QR endpoint.

## MAINT-14-android — Asset QR labels

**User value:** The household can print a QR label and scanning it opens that asset.

**Acceptance criteria:**
- [ ] The user can print a QR label for an asset.
- [ ] Scanning that QR opens the asset.

**Offline:** §6.5: the asset is readable offline, so a scan that this device can resolve locally opens it offline. Printing is not listed as an offline write.

**Module off:** Maintenance off (X7): the maintenance tab is hidden and a maintenance-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `MAINT-02-android`

**Skill:** `.claude/skills/kotlin-android/SKILL.md`

**Open question:** §8 gives `asset.qr_code` a server default. §4 does not say what the QR encodes, or whether a scan from outside the app must deep-link in. There is no API story because §7 has no QR endpoint.

## MAINT-15-web — Assign a task to a member

**User value:** A job can be given to one person, and the reminder for it goes only to them.

**Acceptance criteria:**
- [ ] The user can assign a task to a household member.
- [ ] The daily reminder for that task goes only to the assignee (MAINT-08-api).

**Offline:** §6.5 Maintenance: readable offline. Creating and editing tasks works offline and is queued until sync.

**Module off:** Maintenance off (X7): the maintenance tab is hidden and a maintenance-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `MAINT-03-web`, `MAINT-08-api`, `CORE-04-web`

**Skill:** `.claude/skills/react-web/SKILL.md`

## MAINT-15-ios — Assign a task to a member

**User value:** A job can be given to one person, and the reminder for it goes only to them.

**Acceptance criteria:**
- [ ] The user can assign a task to a household member.
- [ ] The daily reminder for that task goes only to the assignee (MAINT-08-api).

**Offline:** §6.5 Maintenance: readable offline. Creating and editing tasks works offline and is queued until sync.

**Module off:** Maintenance off (X7): the maintenance tab is hidden and a maintenance-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `MAINT-03-ios`, `MAINT-08-api`, `CORE-04-ios`

**Skill:** `.claude/skills/swift-ios/SKILL.md`

## MAINT-15-android — Assign a task to a member

**User value:** A job can be given to one person, and the reminder for it goes only to them.

**Acceptance criteria:**
- [ ] The user can assign a task to a household member.
- [ ] The daily reminder for that task goes only to the assignee (MAINT-08-api).

**Offline:** §6.5 Maintenance: readable offline. Creating and editing tasks works offline and is queued until sync.

**Module off:** Maintenance off (X7): the maintenance tab is hidden and a maintenance-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `MAINT-03-android`, `MAINT-08-api`, `CORE-04-android`

**Skill:** `.claude/skills/kotlin-android/SKILL.md`

## MAINT-16-api — Send task consumables to a shopping list

**User value:** One action puts a task's consumables on the shopping list, tagged with that task, without duplicating a line that is already there.

**Acceptance criteria:**
- [ ] `POST /tasks/{id}/consumables-to-list` inserts one `list_item` per `task_consumable` into the given list (X3).
- [ ] Each line has source maintenance and `maint_task_id` set.
- [ ] An unchecked line with the same `name_key` and `maint_task_id` on that list is skipped.
- [ ] The call runs only when shopping is on.

**Offline:** §6.5 does not list this send as an offline write. The shopping lines it creates are normal shopping rows and follow shopping offline rules after they exist.

**Module off:** Shopping off: the button's API refuses (X3). Maintenance off: the task mutation path is rejected (X7). Pantry plus maintenance has no coupling (§9).

**Dependencies:** `MAINT-03-api`, `CORE-02-api`

**Skill:** `.claude/skills/node-backend/SKILL.md`

**Open question:** §4's acceptance is the button and §9. It does not say the user can edit the consumable rows themselves. Is consumable CRUD part of this story?

## MAINT-16-web — Send consumables to a shopping list

**User value:** From a task, a person can send its consumables onto a shopping list.

**Acceptance criteria:**
- [ ] The task shows a Send to list button.
- [ ] The button calls MAINT-16-api and the lines appear on the chosen list.

**Offline:** §6.5 does not list Send to list as an offline write. It needs a network.

**Module off:** Maintenance only (§9 matrix): the Send to list button is hidden. Shopping off: the button stays hidden. Maintenance off: the task is hidden with the module (X7).

**Dependencies:** `MAINT-16-api`, `SHOP-01-web`, `MAINT-03-web`

**Skill:** `.claude/skills/react-web/SKILL.md`

## MAINT-16-ios — Send consumables to a shopping list

**User value:** From a task, a person can send its consumables onto a shopping list.

**Acceptance criteria:**
- [ ] The task shows a Send to list button.
- [ ] The button calls MAINT-16-api and the lines appear on the chosen list.

**Offline:** §6.5 does not list Send to list as an offline write. It needs a network.

**Module off:** Maintenance only (§9 matrix): the Send to list button is hidden. Shopping off: the button stays hidden. Maintenance off: the task is hidden with the module (X7).

**Dependencies:** `MAINT-16-api`, `SHOP-01-ios`, `MAINT-03-ios`

**Skill:** `.claude/skills/swift-ios/SKILL.md`

## MAINT-16-android — Send consumables to a shopping list

**User value:** From a task, a person can send its consumables onto a shopping list.

**Acceptance criteria:**
- [ ] The task shows a Send to list button.
- [ ] The button calls MAINT-16-api and the lines appear on the chosen list.

**Offline:** §6.5 does not list Send to list as an offline write. It needs a network.

**Module off:** Maintenance only (§9 matrix): the Send to list button is hidden. Shopping off: the button stays hidden. Maintenance off: the task is hidden with the module (X7).

**Dependencies:** `MAINT-16-api`, `SHOP-01-android`, `MAINT-03-android`

**Skill:** `.claude/skills/kotlin-android/SKILL.md`
