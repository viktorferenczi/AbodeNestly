# Core stories

Source of truth: `docs/SPEC.md` §4.1. Offline lines are §6.5 (Core row). Module-off lines are §9 (rule X7 and the matrix). One story per feature ID. Where the feature is more than one of API, web, iOS, and Android, it is split with the suffix `-api`, `-web`, `-ios`, or `-android`. Order inside a feature is API, then web, then iOS, then Android.

An open question is unresolved in the spec. Do not pick an answer while implementing the story.

## CORE-01-api — Sign-in creates a profile

**User value:** The first time a person signs in, the household can already see who they are.

**Acceptance criteria:**
- [ ] A `profile` row is created on that user's first sign-in.

**Offline:** Signing in needs a network. §6.5's offline profile edit does not cover the first sign-in.

**Module off:** Core is not behind a module flag. It stays available when shopping, pantry, or maintenance is off.

**Dependencies:** none

**Skill:** `.claude/skills/sql-supabase/SKILL.md`

**Open question:** §4 requires a profile row on first sign-in. §14 defines `profile` and its RLS and does not show a trigger or auth hook that inserts the row. What creates it?

## CORE-01-web — Sign in with email magic link, Apple, and Google

**User value:** A person can get into the household with the sign-in they already use.

**Acceptance criteria:**
- [ ] The user can sign in with an email magic link.
- [ ] The user can sign in with Apple.
- [ ] The user can sign in with Google.
- [ ] The session is a Supabase Auth session.

**Offline:** Signing in needs a network. After a session exists, §6.5 still allows later profile edits offline.

**Module off:** Core is not behind a module flag. It stays available when shopping, pantry, or maintenance is off.

**Dependencies:** `CORE-01-api`

**Skill:** `.claude/skills/react-web/SKILL.md`

## CORE-01-ios — Sign in with email magic link, Apple, and Google

**User value:** A person can get into the household with the sign-in they already use.

**Acceptance criteria:**
- [ ] The user can sign in with an email magic link.
- [ ] The user can sign in with Apple.
- [ ] The user can sign in with Google.
- [ ] The session is a Supabase Auth session.

**Offline:** Signing in needs a network. After a session exists, §6.5 still allows later profile edits offline.

**Module off:** Core is not behind a module flag. It stays available when shopping, pantry, or maintenance is off.

**Dependencies:** `CORE-01-api`

**Skill:** `.claude/skills/swift-ios/SKILL.md`

## CORE-01-android — Sign in with email magic link, Apple, and Google

**User value:** A person can get into the household with the sign-in they already use.

**Acceptance criteria:**
- [ ] The user can sign in with an email magic link.
- [ ] The user can sign in with Apple.
- [ ] The user can sign in with Google.
- [ ] The session is a Supabase Auth session.

**Offline:** Signing in needs a network. After a session exists, §6.5 still allows later profile edits offline.

**Module off:** Core is not behind a module flag. It stays available when shopping, pantry, or maintenance is off.

**Dependencies:** `CORE-01-api`

**Skill:** `.claude/skills/kotlin-android/SKILL.md`

## CORE-02-api — Create a household

**User value:** Starting a household gives that person an owned home with a groceries list and the usual food places already there.

**Acceptance criteria:**
- [ ] The caller becomes the owner of the new household.
- [ ] A default shopping list named Groceries is created.
- [ ] Default locations Fridge, Freezer, and Pantry are created.

**Offline:** §6.5 Core: readable offline once it is in the local database. Household and member changes require a network.

**Module off:** Core is not behind a module flag. It stays available when shopping, pantry, or maintenance is off.

**Dependencies:** `CORE-01-api`

**Skill:** `.claude/skills/node-backend/SKILL.md`; `.claude/skills/sql-supabase/SKILL.md`

**Open question:** §14 `create_household` inserts the household and the owner membership only. It does not insert the Groceries list or the Fridge, Freezer, and Pantry locations that §4 requires. Where do those default rows get written?

## CORE-02-web — Create a household

**User value:** A person can start a household and land in it as the owner.

**Acceptance criteria:**
- [ ] The user can create a household by name.
- [ ] After creation they are the owner.
- [ ] The Groceries list and the Fridge, Freezer, and Pantry locations from CORE-02-api are present.

**Offline:** §6.5 Core: readable offline once it is in the local database. Household and member changes require a network.

**Module off:** Core is not behind a module flag. It stays available when shopping, pantry, or maintenance is off.

**Dependencies:** `CORE-01-web`, `CORE-02-api`

**Skill:** `.claude/skills/react-web/SKILL.md`

## CORE-02-ios — Create a household

**User value:** A person can start a household and land in it as the owner.

**Acceptance criteria:**
- [ ] The user can create a household by name.
- [ ] After creation they are the owner.
- [ ] The Groceries list and the Fridge, Freezer, and Pantry locations from CORE-02-api are present.

**Offline:** §6.5 Core: readable offline once it is in the local database. Household and member changes require a network.

**Module off:** Core is not behind a module flag. It stays available when shopping, pantry, or maintenance is off.

**Dependencies:** `CORE-01-ios`, `CORE-02-api`

**Skill:** `.claude/skills/swift-ios/SKILL.md`

## CORE-02-android — Create a household

**User value:** A person can start a household and land in it as the owner.

**Acceptance criteria:**
- [ ] The user can create a household by name.
- [ ] After creation they are the owner.
- [ ] The Groceries list and the Fridge, Freezer, and Pantry locations from CORE-02-api are present.

**Offline:** §6.5 Core: readable offline once it is in the local database. Household and member changes require a network.

**Module off:** Core is not behind a module flag. It stays available when shopping, pantry, or maintenance is off.

**Dependencies:** `CORE-01-android`, `CORE-02-api`

**Skill:** `.claude/skills/kotlin-android/SKILL.md`

## CORE-03-api — Invite by link or code

**User value:** Someone who has the link or code can join the household, and an old or used-up invite cannot be reused.

**Acceptance criteria:**
- [ ] An invite has an expiry and a maximum number of uses.
- [ ] Accepting a live invite joins the user as a member.
- [ ] An expired invite or one that has reached its maximum uses is rejected.

**Offline:** §6.5 Core: readable offline once it is in the local database. Household and member changes require a network.

**Module off:** Core is not behind a module flag. It stays available when shopping, pantry, or maintenance is off.

**Dependencies:** `CORE-02-api`

**Skill:** `.claude/skills/node-backend/SKILL.md`; `.claude/skills/sql-supabase/SKILL.md`

**Open question:** §4 says accepting joins as a member. §7 `POST /households/{id}/invites` takes an optional role, and §14 stores that role on the invite. Is a non-member role in v1, or does accept always create a member?

## CORE-03-web — Invite by link or code

**User value:** A member can hand someone a link or a code and that person joins the household.

**Acceptance criteria:**
- [ ] The user can create an invite and share it as a link or a code.
- [ ] The invite shows an expiry and a maximum number of uses.
- [ ] Opening a valid invite and accepting it joins the signed-in user as a member.

**Offline:** §6.5 Core: readable offline once it is in the local database. Household and member changes require a network.

**Module off:** Core is not behind a module flag. It stays available when shopping, pantry, or maintenance is off.

**Dependencies:** `CORE-02-web`, `CORE-03-api`

**Skill:** `.claude/skills/react-web/SKILL.md`

## CORE-03-ios — Invite by link or code

**User value:** A member can hand someone a link or a code and that person joins the household.

**Acceptance criteria:**
- [ ] The user can create an invite and share it as a link or a code.
- [ ] The invite shows an expiry and a maximum number of uses.
- [ ] Opening a valid invite and accepting it joins the signed-in user as a member.

**Offline:** §6.5 Core: readable offline once it is in the local database. Household and member changes require a network.

**Module off:** Core is not behind a module flag. It stays available when shopping, pantry, or maintenance is off.

**Dependencies:** `CORE-02-ios`, `CORE-03-api`

**Skill:** `.claude/skills/swift-ios/SKILL.md`

## CORE-03-android — Invite by link or code

**User value:** A member can hand someone a link or a code and that person joins the household.

**Acceptance criteria:**
- [ ] The user can create an invite and share it as a link or a code.
- [ ] The invite shows an expiry and a maximum number of uses.
- [ ] Opening a valid invite and accepting it joins the signed-in user as a member.

**Offline:** §6.5 Core: readable offline once it is in the local database. Household and member changes require a network.

**Module off:** Core is not behind a module flag. It stays available when shopping, pantry, or maintenance is off.

**Dependencies:** `CORE-02-android`, `CORE-03-api`

**Skill:** `.claude/skills/kotlin-android/SKILL.md`

## CORE-04-api — Members, roles, remove, and leave

**User value:** The household can see who is in it, an owner can remove someone, and the last owner cannot walk away and leave the household with nobody in charge.

**Acceptance criteria:**
- [ ] Members are returned with role owner or member.
- [ ] An owner can remove a member.
- [ ] A member can leave.
- [ ] The last owner cannot leave.

**Offline:** §6.5 Core: readable offline once it is in the local database. Household and member changes require a network.

**Module off:** Core is not behind a module flag. It stays available when shopping, pantry, or maintenance is off.

**Dependencies:** `CORE-02-api`

**Skill:** `.claude/skills/node-backend/SKILL.md`; `.claude/skills/sql-supabase/SKILL.md`

**Open question:** §4 names roles and specifies remove and leave. It does not say an owner can change an existing member's role. Is role change in v1?

## CORE-04-web — Member list, roles, remove, and leave

**User value:** Everyone can see who lives in the household, an owner can remove someone, and a person can leave.

**Acceptance criteria:**
- [ ] The member list shows each person and their role, owner or member.
- [ ] An owner can remove a member.
- [ ] The signed-in user can leave the household.
- [ ] The last owner is blocked from leaving.

**Offline:** §6.5 Core: readable offline once it is in the local database. Household and member changes require a network.

**Module off:** Core is not behind a module flag. It stays available when shopping, pantry, or maintenance is off.

**Dependencies:** `CORE-02-web`, `CORE-04-api`

**Skill:** `.claude/skills/react-web/SKILL.md`

## CORE-04-ios — Member list, roles, remove, and leave

**User value:** Everyone can see who lives in the household, an owner can remove someone, and a person can leave.

**Acceptance criteria:**
- [ ] The member list shows each person and their role, owner or member.
- [ ] An owner can remove a member.
- [ ] The signed-in user can leave the household.
- [ ] The last owner is blocked from leaving.

**Offline:** §6.5 Core: readable offline once it is in the local database. Household and member changes require a network.

**Module off:** Core is not behind a module flag. It stays available when shopping, pantry, or maintenance is off.

**Dependencies:** `CORE-02-ios`, `CORE-04-api`

**Skill:** `.claude/skills/swift-ios/SKILL.md`

## CORE-04-android — Member list, roles, remove, and leave

**User value:** Everyone can see who lives in the household, an owner can remove someone, and a person can leave.

**Acceptance criteria:**
- [ ] The member list shows each person and their role, owner or member.
- [ ] An owner can remove a member.
- [ ] The signed-in user can leave the household.
- [ ] The last owner is blocked from leaving.

**Offline:** §6.5 Core: readable offline once it is in the local database. Household and member changes require a network.

**Module off:** Core is not behind a module flag. It stays available when shopping, pantry, or maintenance is off.

**Dependencies:** `CORE-02-android`, `CORE-04-api`

**Skill:** `.claude/skills/kotlin-android/SKILL.md`

## CORE-05-api — Module toggles per household

**User value:** An owner can turn shopping, pantry, or maintenance off for that household, and the server stops treating a switched-off module as writable.

**Acceptance criteria:**
- [ ] An owner can set shopping, pantry, and maintenance independently.
- [ ] A non-owner cannot change the flags.
- [ ] The stored flags are what X7 and the module triggers read.

**Offline:** §6.5 Core: readable offline once it is in the local database. Household and member changes require a network.

**Module off:** This story is the switch. With a flag off, X7 hides that module's tab on clients and sync rejects its table mutations with `module_disabled`. The other modules keep working.

**Dependencies:** `CORE-04-api`

**Skill:** `.claude/skills/node-backend/SKILL.md`

**Open question:** X7's `module_disabled` rejection lives on `POST /sync`. The only feature whose acceptance is queued sync is SHOP-08, which is sequenced later. Does CORE-05-api only store the flags, with SHOP-08-api enforcing the rejection once sync exists?

## CORE-05-web — Module toggles per household

**User value:** An owner can turn a module off and the household stops seeing that part of the app.

**Acceptance criteria:**
- [ ] An owner can toggle shopping, pantry, and maintenance for this household.
- [ ] A non-owner does not get the toggles.
- [ ] Turning a module off hides its tab.
- [ ] The other modules stay usable.

**Offline:** §6.5 Core: readable offline once it is in the local database. Household and member changes require a network.

**Module off:** Turning a module off hides that module's tab (X7) and leaves the other tabs in place. The matrix in §9 is the picture of what remains.

**Dependencies:** `CORE-04-web`, `CORE-05-api`

**Skill:** `.claude/skills/react-web/SKILL.md`

## CORE-05-ios — Module toggles per household

**User value:** An owner can turn a module off and the household stops seeing that part of the app.

**Acceptance criteria:**
- [ ] An owner can toggle shopping, pantry, and maintenance for this household.
- [ ] A non-owner does not get the toggles.
- [ ] Turning a module off hides its tab.
- [ ] The other modules stay usable.

**Offline:** §6.5 Core: readable offline once it is in the local database. Household and member changes require a network.

**Module off:** Turning a module off hides that module's tab (X7) and leaves the other tabs in place. The matrix in §9 is the picture of what remains.

**Dependencies:** `CORE-04-ios`, `CORE-05-api`

**Skill:** `.claude/skills/swift-ios/SKILL.md`

## CORE-05-android — Module toggles per household

**User value:** An owner can turn a module off and the household stops seeing that part of the app.

**Acceptance criteria:**
- [ ] An owner can toggle shopping, pantry, and maintenance for this household.
- [ ] A non-owner does not get the toggles.
- [ ] Turning a module off hides its tab.
- [ ] The other modules stay usable.

**Offline:** §6.5 Core: readable offline once it is in the local database. Household and member changes require a network.

**Module off:** Turning a module off hides that module's tab (X7) and leaves the other tabs in place. The matrix in §9 is the picture of what remains.

**Dependencies:** `CORE-04-android`, `CORE-05-api`

**Skill:** `.claude/skills/kotlin-android/SKILL.md`

## CORE-06-api — Profile avatar in Storage

**User value:** A person can put a face on their profile and the rest of the household sees that picture.

**Acceptance criteria:**
- [ ] The avatar file is stored in Supabase Storage.
- [ ] `profile.avatar_path` points at that file.

**Offline:** §6.5 Core: profile edits are writable offline. Household and member changes require a network.

**Module off:** Core is not behind a module flag. It stays available when shopping, pantry, or maintenance is off.

**Dependencies:** `CORE-01-api`

**Skill:** `.claude/skills/sql-supabase/SKILL.md`

**Open question:** §3's Storage path is `households/{household_id}/{entity}/{entity_id}/{filename}`, and a profile has no household. §7's signed upload is CORE-11 and is household-scoped. Which path and which API does the avatar use? §6.5 says profile edits are writable offline, and `profile` is not in the §8 `SyncTable` list. How does an offline name or avatar edit reach the server?

## CORE-06-web — Profile name and avatar

**User value:** A person can set the name and picture the household sees.

**Acceptance criteria:**
- [ ] The user can edit their display name.
- [ ] The user can set an avatar that is stored via CORE-06-api.
- [ ] Household members see the name and avatar.

**Offline:** §6.5 Core: profile edits are writable offline. Household and member changes require a network.

**Module off:** Core is not behind a module flag. It stays available when shopping, pantry, or maintenance is off.

**Dependencies:** `CORE-01-web`, `CORE-06-api`

**Skill:** `.claude/skills/react-web/SKILL.md`

**Open question:** Same open point as CORE-06-api: offline profile edits versus `profile` not being a sync table, and which Storage path the avatar uses.

## CORE-06-ios — Profile name and avatar

**User value:** A person can set the name and picture the household sees.

**Acceptance criteria:**
- [ ] The user can edit their display name.
- [ ] The user can set an avatar that is stored via CORE-06-api.
- [ ] Household members see the name and avatar.

**Offline:** §6.5 Core: profile edits are writable offline. Household and member changes require a network.

**Module off:** Core is not behind a module flag. It stays available when shopping, pantry, or maintenance is off.

**Dependencies:** `CORE-01-ios`, `CORE-06-api`

**Skill:** `.claude/skills/swift-ios/SKILL.md`

**Open question:** Same open point as CORE-06-api: offline profile edits versus `profile` not being a sync table, and which Storage path the avatar uses.

## CORE-06-android — Profile name and avatar

**User value:** A person can set the name and picture the household sees.

**Acceptance criteria:**
- [ ] The user can edit their display name.
- [ ] The user can set an avatar that is stored via CORE-06-api.
- [ ] Household members see the name and avatar.

**Offline:** §6.5 Core: profile edits are writable offline. Household and member changes require a network.

**Module off:** Core is not behind a module flag. It stays available when shopping, pantry, or maintenance is off.

**Dependencies:** `CORE-01-android`, `CORE-06-api`

**Skill:** `.claude/skills/kotlin-android/SKILL.md`

**Open question:** Same open point as CORE-06-api: offline profile edits versus `profile` not being a sync table, and which Storage path the avatar uses.

## CORE-07-web — Multiple households per user

**User value:** A person who belongs to more than one household can switch, and each household keeps its own data on the device.

**Acceptance criteria:**
- [ ] The user can belong to more than one household.
- [ ] A switcher changes the active household.
- [ ] Each household has its own local database.
- [ ] Each household has its own sync cursor.

**Offline:** §6.5 Core: each household's already synced data is readable offline. Creating or joining a household is a household change and needs a network.

**Module off:** Core is not behind a module flag. It stays available when shopping, pantry, or maintenance is off.

**Dependencies:** `CORE-02-web`, `CORE-03-web`

**Skill:** `.claude/skills/react-web/SKILL.md`

**Open question:** §4 requires a local database and a sync cursor per household. §6 describes the pull loop that moves the cursor, and the feature that names queued sync is SHOP-08. Does CORE-07 include the pull loop, or only the database and the cursor storage?

## CORE-07-ios — Multiple households per user

**User value:** A person who belongs to more than one household can switch, and each household keeps its own data on the device.

**Acceptance criteria:**
- [ ] The user can belong to more than one household.
- [ ] A switcher changes the active household.
- [ ] Each household has its own local database.
- [ ] Each household has its own sync cursor.

**Offline:** §6.5 Core: each household's already synced data is readable offline. Creating or joining a household is a household change and needs a network.

**Module off:** Core is not behind a module flag. It stays available when shopping, pantry, or maintenance is off.

**Dependencies:** `CORE-02-ios`, `CORE-03-ios`

**Skill:** `.claude/skills/swift-ios/SKILL.md`

**Open question:** §4 requires a local database and a sync cursor per household. §6 describes the pull loop that moves the cursor, and the feature that names queued sync is SHOP-08. Does CORE-07 include the pull loop, or only the database and the cursor storage?

## CORE-07-android — Multiple households per user

**User value:** A person who belongs to more than one household can switch, and each household keeps its own data on the device.

**Acceptance criteria:**
- [ ] The user can belong to more than one household.
- [ ] A switcher changes the active household.
- [ ] Each household has its own local database.
- [ ] Each household has its own sync cursor.

**Offline:** §6.5 Core: each household's already synced data is readable offline. Creating or joining a household is a household change and needs a network.

**Module off:** Core is not behind a module flag. It stays available when shopping, pantry, or maintenance is off.

**Dependencies:** `CORE-02-android`, `CORE-03-android`

**Skill:** `.claude/skills/kotlin-android/SKILL.md`

**Open question:** §4 requires a local database and a sync cursor per household. §6 describes the pull loop that moves the cursor, and the feature that names queued sync is SHOP-08. Does CORE-07 include the pull loop, or only the database and the cursor storage?

## CORE-08-api — Activity rows for visible actions

**User value:** The household gets a sentence for the things people actually did, such as checking off milk.

**Acceptance criteria:**
- [ ] A user-visible check-off, pantry add, pantry use, task completion, or member join writes an `activity` row (X6).
- [ ] The summary is a sentence of the form in §4, such as "Anna checked off Milk".
- [ ] The row is scoped to the household.

**Offline:** Activity is produced on the server when the action syncs or is accepted. Reading it back is a Core read and works offline once pulled.

**Module off:** X6 does not add a module flag of its own. An action that X7 rejects because its module is off does not write a new activity row.

**Dependencies:** `CORE-02-api`

**Skill:** `.claude/skills/node-backend/SKILL.md`

**Open question:** X6 says sync writes these rows, including "member joined", and joining is `POST /invites/{code}/accept` rather than a sync mutation. Which path writes the member-joined row? §10 also lists an `activity` notification type and no job that sends it.

## CORE-08-web — Activity feed

**User value:** A person can open the household and see what the others just did.

**Acceptance criteria:**
- [ ] The feed is per household.
- [ ] Entries read as sentences, such as "Anna checked off Milk".
- [ ] Only the last 30 days are shown.

**Offline:** §6.5 Core: readable offline once it is in the local database. New sentences appear after the action has synced.

**Module off:** The feed itself stays, because Core is not a module. Rows for an action that cannot run while its module is off are not created (see CORE-08-api).

**Dependencies:** `CORE-07-web`, `CORE-08-api`

**Skill:** `.claude/skills/react-web/SKILL.md`

**Open question:** §4 does not say whether old activity for a module stays visible after that module is switched off.

## CORE-08-ios — Activity feed

**User value:** A person can open the household and see what the others just did.

**Acceptance criteria:**
- [ ] The feed is per household.
- [ ] Entries read as sentences, such as "Anna checked off Milk".
- [ ] Only the last 30 days are shown.

**Offline:** §6.5 Core: readable offline once it is in the local database. New sentences appear after the action has synced.

**Module off:** The feed itself stays, because Core is not a module. Rows for an action that cannot run while its module is off are not created (see CORE-08-api).

**Dependencies:** `CORE-07-ios`, `CORE-08-api`

**Skill:** `.claude/skills/swift-ios/SKILL.md`

**Open question:** §4 does not say whether old activity for a module stays visible after that module is switched off.

## CORE-08-android — Activity feed

**User value:** A person can open the household and see what the others just did.

**Acceptance criteria:**
- [ ] The feed is per household.
- [ ] Entries read as sentences, such as "Anna checked off Milk".
- [ ] Only the last 30 days are shown.

**Offline:** §6.5 Core: readable offline once it is in the local database. New sentences appear after the action has synced.

**Module off:** The feed itself stays, because Core is not a module. Rows for an action that cannot run while its module is off are not created (see CORE-08-api).

**Dependencies:** `CORE-07-android`, `CORE-08-api`

**Skill:** `.claude/skills/kotlin-android/SKILL.md`

**Open question:** §4 does not say whether old activity for a module stays visible after that module is switched off.

## CORE-09-web — Household-wide search

**User value:** A person can search the household by name and only the modules that are turned on are included.

**Acceptance criteria:**
- [ ] Search matches names.
- [ ] It covers every module that is enabled for the household.
- [ ] It reads the local database only.

**Offline:** §6.5 Core reads work offline. §4 says this search is local, so it works with no network.

**Module off:** A module that is off is excluded. Core data, which has no flag, stays searchable. §9's matrix is the list of what "enabled" means.

**Dependencies:** `CORE-05-web`, `CORE-07-web`

**Skill:** `.claude/skills/react-web/SKILL.md`

**Open question:** §4 says the search covers names and does not list the columns. Which name fields are in the index (list item, pantry item, recipe, asset, task title, store, location, contractor, and so on)?

## CORE-09-ios — Household-wide search

**User value:** A person can search the household by name and only the modules that are turned on are included.

**Acceptance criteria:**
- [ ] Search matches names.
- [ ] It covers every module that is enabled for the household.
- [ ] It reads the local database only.

**Offline:** §6.5 Core reads work offline. §4 says this search is local, so it works with no network.

**Module off:** A module that is off is excluded. Core data, which has no flag, stays searchable. §9's matrix is the list of what "enabled" means.

**Dependencies:** `CORE-05-ios`, `CORE-07-ios`

**Skill:** `.claude/skills/swift-ios/SKILL.md`

**Open question:** §4 says the search covers names and does not list the columns. Which name fields are in the index (list item, pantry item, recipe, asset, task title, store, location, contractor, and so on)?

## CORE-09-android — Household-wide search

**User value:** A person can search the household by name and only the modules that are turned on are included.

**Acceptance criteria:**
- [ ] Search matches names.
- [ ] It covers every module that is enabled for the household.
- [ ] It reads the local database only.

**Offline:** §6.5 Core reads work offline. §4 says this search is local, so it works with no network.

**Module off:** A module that is off is excluded. Core data, which has no flag, stays searchable. §9's matrix is the list of what "enabled" means.

**Dependencies:** `CORE-05-android`, `CORE-07-android`

**Skill:** `.claude/skills/kotlin-android/SKILL.md`

**Open question:** §4 says the search covers names and does not list the columns. Which name fields are in the index (list item, pantry item, recipe, asset, task title, store, location, contractor, and so on)?

## CORE-10-api — Purge soft-deleted rows after 30 days

**User value:** Something deleted by mistake can come back for 30 days, and then it is actually gone.

**Acceptance criteria:**
- [ ] A row with `deleted_at` set is still restorable until 30 days have passed.
- [ ] A scheduled job hard-deletes soft-deleted rows older than 30 days.

**Offline:** The purge runs on the server. Soft delete of shopping and pantry rows is an offline write (§6.5). Household and member changes still need a network.

**Module off:** The purge does not depend on a module flag. Restoring or deleting a row in a module that is off is rejected the same way as any other write to that module (X7).

**Dependencies:** none

**Skill:** `.claude/skills/sql-supabase/SKILL.md`

**Open question:** §14 `purge_soft_deleted` names a fixed table list. §4 says a Recently deleted screen per module and does not list the tables. Are the §14 tables the complete restore set? Restore itself is not given a separate endpoint; §5 says `deleted_at` null means live. Is restore a sync upsert that clears `deleted_at`?

## CORE-10-web — Soft delete with 30-day restore

**User value:** A person can open Recently deleted for a module and put something back within 30 days.

**Acceptance criteria:**
- [ ] Each module has a Recently deleted screen.
- [ ] A soft-deleted row from that module appears there.
- [ ] The user can restore it within 30 days.
- [ ] After the CORE-10-api purge it is no longer listed.

**Offline:** Shopping and pantry deletes and restores are offline writes (§6.5). Maintenance log and task deletes follow the maintenance write rule. Household and member changes need a network.

**Module off:** The Recently deleted screen for a module is hidden while that module is off (X7). The other modules keep theirs.

**Dependencies:** `CORE-05-web`, `CORE-10-api`

**Skill:** `.claude/skills/react-web/SKILL.md`

## CORE-10-ios — Soft delete with 30-day restore

**User value:** A person can open Recently deleted for a module and put something back within 30 days.

**Acceptance criteria:**
- [ ] Each module has a Recently deleted screen.
- [ ] A soft-deleted row from that module appears there.
- [ ] The user can restore it within 30 days.
- [ ] After the CORE-10-api purge it is no longer listed.

**Offline:** Shopping and pantry deletes and restores are offline writes (§6.5). Maintenance log and task deletes follow the maintenance write rule. Household and member changes need a network.

**Module off:** The Recently deleted screen for a module is hidden while that module is off (X7). The other modules keep theirs.

**Dependencies:** `CORE-05-ios`, `CORE-10-api`

**Skill:** `.claude/skills/swift-ios/SKILL.md`

## CORE-10-android — Soft delete with 30-day restore

**User value:** A person can open Recently deleted for a module and put something back within 30 days.

**Acceptance criteria:**
- [ ] Each module has a Recently deleted screen.
- [ ] A soft-deleted row from that module appears there.
- [ ] The user can restore it within 30 days.
- [ ] After the CORE-10-api purge it is no longer listed.

**Offline:** Shopping and pantry deletes and restores are offline writes (§6.5). Maintenance log and task deletes follow the maintenance write rule. Household and member changes need a network.

**Module off:** The Recently deleted screen for a module is hidden while that module is off (X7). The other modules keep theirs.

**Dependencies:** `CORE-05-android`, `CORE-10-api`

**Skill:** `.claude/skills/kotlin-android/SKILL.md`

## CORE-11-api — Signed attachment upload

**User value:** A photo or PDF stays with the pantry item, asset, or log entry it belongs to, and the file is uploaded with a signed URL rather than through an open bucket.

**Acceptance criteria:**
- [ ] `POST /attachments/sign` returns an attachment id, an upload URL, and a storage path.
- [ ] The path matches §3: `households/{household_id}/{entity}/{entity_id}/{filename}`.
- [ ] Photos and PDFs are accepted for a pantry item, an asset, and a log entry.

**Offline:** §6.5 Maintenance: attachment bytes queue until online, so the signed URL is requested when the device is online. Pantry writes are offline in the same section; see the open question.

**Module off:** Signing an upload for an entity whose module is off is rejected. Storage policies follow the table RLS (§11).

**Dependencies:** `CORE-02-api`

**Skill:** `.claude/skills/node-backend/SKILL.md`; `.claude/skills/sql-supabase/SKILL.md`

**Open question:** §6.5 says pantry writes all work offline, and says maintenance attachments queue until online. Does a pantry photo queue the same way, or must it upload immediately? Profile avatars are CORE-06, not this entity list.

## CORE-11-web — Attachments on pantry items, assets, and log entries

**User value:** A person can attach a photo or a PDF to a pantry item, an asset, or a log entry.

**Acceptance criteria:**
- [ ] The user can attach a photo or a PDF to a pantry item, an asset, and a maintenance log entry.
- [ ] The file is uploaded with the signed URL from CORE-11-api.
- [ ] The attachment is shown on that entity.

**Offline:** §6.5 Maintenance: attachment bytes queue until the device is online. The entity itself stays readable offline.

**Module off:** The attach action for a module is hidden while that module is off (X7).

**Dependencies:** `CORE-11-api`, `CORE-07-web`

**Skill:** `.claude/skills/react-web/SKILL.md`

**Open question:** Same pantry-versus-maintenance attachment question as CORE-11-api.

## CORE-11-ios — Attachments on pantry items, assets, and log entries

**User value:** A person can attach a photo or a PDF to a pantry item, an asset, or a log entry.

**Acceptance criteria:**
- [ ] The user can attach a photo or a PDF to a pantry item, an asset, and a maintenance log entry.
- [ ] The file is uploaded with the signed URL from CORE-11-api.
- [ ] The attachment is shown on that entity.

**Offline:** §6.5 Maintenance: attachment bytes queue until the device is online. The entity itself stays readable offline.

**Module off:** The attach action for a module is hidden while that module is off (X7).

**Dependencies:** `CORE-11-api`, `CORE-07-ios`

**Skill:** `.claude/skills/swift-ios/SKILL.md`

**Open question:** Same pantry-versus-maintenance attachment question as CORE-11-api.

## CORE-11-android — Attachments on pantry items, assets, and log entries

**User value:** A person can attach a photo or a PDF to a pantry item, an asset, or a log entry.

**Acceptance criteria:**
- [ ] The user can attach a photo or a PDF to a pantry item, an asset, and a maintenance log entry.
- [ ] The file is uploaded with the signed URL from CORE-11-api.
- [ ] The attachment is shown on that entity.

**Offline:** §6.5 Maintenance: attachment bytes queue until the device is online. The entity itself stays readable offline.

**Module off:** The attach action for a module is hidden while that module is off (X7).

**Dependencies:** `CORE-11-api`, `CORE-07-android`

**Skill:** `.claude/skills/kotlin-android/SKILL.md`

**Open question:** Same pantry-versus-maintenance attachment question as CORE-11-api.

## CORE-12-web — Notification preferences

**User value:** Each person can choose, per household, which notices they want.

**Acceptance criteria:**
- [ ] Preferences are stored per household and per notification type.
- [ ] The types are eat soon, low stock, task due, warranty, and activity (§8).
- [ ] A later send for that household respects the preference (§10).

**Offline:** §6.5 does not list notification preferences as an offline write. §7 allows a direct Supabase write for `notification_pref` on screens that need a network.

**Module off:** Core is not behind a module flag. It stays available when shopping, pantry, or maintenance is off. A preference cannot produce a notice for a module that is off, because that module's jobs and triggers do not fire (X7, §10).

**Dependencies:** `CORE-05-web`, `CORE-07-web`

**Skill:** `.claude/skills/react-web/SKILL.md`

**Open question:** `notification_pref` is not in the §8 `SyncTable` list, and §6.5 does not say the edit is offline. Confirm these screens are online-only. §8 includes an `activity` type, and §10's daily job does not enqueue an activity notice. What does that preference control?

## CORE-12-ios — Notification preferences

**User value:** Each person can choose, per household, which notices they want.

**Acceptance criteria:**
- [ ] Preferences are stored per household and per notification type.
- [ ] The types are eat soon, low stock, task due, warranty, and activity (§8).
- [ ] A later send for that household respects the preference (§10).

**Offline:** §6.5 does not list notification preferences as an offline write. §7 allows a direct Supabase write for `notification_pref` on screens that need a network.

**Module off:** Core is not behind a module flag. It stays available when shopping, pantry, or maintenance is off. A preference cannot produce a notice for a module that is off, because that module's jobs and triggers do not fire (X7, §10).

**Dependencies:** `CORE-05-ios`, `CORE-07-ios`

**Skill:** `.claude/skills/swift-ios/SKILL.md`

**Open question:** `notification_pref` is not in the §8 `SyncTable` list, and §6.5 does not say the edit is offline. Confirm these screens are online-only. §8 includes an `activity` type, and §10's daily job does not enqueue an activity notice. What does that preference control?

## CORE-12-android — Notification preferences

**User value:** Each person can choose, per household, which notices they want.

**Acceptance criteria:**
- [ ] Preferences are stored per household and per notification type.
- [ ] The types are eat soon, low stock, task due, warranty, and activity (§8).
- [ ] A later send for that household respects the preference (§10).

**Offline:** §6.5 does not list notification preferences as an offline write. §7 allows a direct Supabase write for `notification_pref` on screens that need a network.

**Module off:** Core is not behind a module flag. It stays available when shopping, pantry, or maintenance is off. A preference cannot produce a notice for a module that is off, because that module's jobs and triggers do not fire (X7, §10).

**Dependencies:** `CORE-05-android`, `CORE-07-android`

**Skill:** `.claude/skills/kotlin-android/SKILL.md`

**Open question:** `notification_pref` is not in the §8 `SyncTable` list, and §6.5 does not say the edit is offline. Confirm these screens are online-only. §8 includes an `activity` type, and §10's daily job does not enqueue an activity notice. What does that preference control?
