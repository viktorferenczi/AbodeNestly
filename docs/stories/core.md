# Core stories

One story per feature ID per layer that has work. Each body is `docs/STORY-TEMPLATE.md` verbatim. Order inside an ID is db, api, web, ios, android.

# CORE-01-db — Create the profile on first sign-in

## Summary
The first time a person signs in, AbodeNestly already has a profile row so the household can show their name.

## Spec references
SPEC §4 CORE-01; §11 profile trigger; §14 handle_new_user; TESTING §3 -db row

## Scope
In: Trigger `on_auth_user_created` on `auth.users` insert. Display name from `full_name`, else `name`, else the email local part. `avatar_url` copied when present. `ON CONFLICT (id) DO NOTHING`. Policies `profile_self` and `profile_household`.
Out: Sign-in screens (CORE-01-web, CORE-01-ios, CORE-01-android). Editing the profile (CORE-06).

## Acceptance criteria
- [ ] AC1 Given a new auth.users row whose metadata has full_name, when the trigger runs, then profile.display_name equals that full_name and profile.id equals the user id.
- [ ] AC2 Given metadata has no full_name and no name, when the trigger runs, then display_name is the email local part.
- [ ] AC3 Given a profile row already exists for that id, when the trigger runs again, then display_name is unchanged.
- [ ] AC4 Given two users share a household, when one selects the other's profile, then the row is visible, and a user who shares no household selects nothing.

## Design
Screen: n/a — this layer has no DESIGN screen.
Deviations from the plan: none

## Offline behaviour
The trigger runs on the auth server during sign-in, which needs a network. It is not an offline write (SPEC §6.5).

## Module-off behaviour
Core is not gated. Nothing on this screen errors when shopping, pantry, or maintenance is off.

## Data and contract
Tables: profile  Endpoints: none (Supabase Auth insert)  Types: Profile
Schema change: yes — supabase/migrations/20261108100000_CORE-01_profile_trigger.sql  Contract change: no

## Tests required
Unit: n/a (database behaviour is asserted in pgTAP, TESTING §2)
Component: n/a (no UI)
Integration: supabase/tests/CORE-01_profile.sql → AC1 full_name; AC2 email local part; AC3 conflict does not overwrite; AC4 household peer can select, stranger cannot
E2E: n/a (covered by the client journeys for this ID)
Offline test: n/a (server-side)

## Dependencies
Blocked by: none  Blocks: CORE-01-api, CORE-01-web, CORE-01-ios, CORE-01-android, CORE-02-db, CORE-06-db

## Skill
.claude/skills/sql-supabase/SKILL.md

## Open questions
none

# CORE-01-api — Accept the Supabase session

## Summary
A session from email, Apple, or Google is a JWT the API will accept as that same person.

## Spec references
SPEC §4 CORE-01; §3 Auth; §11 sub matches auth.uid(); TESTING §3 -api row

## Scope
In: verifyJwt accepts a Supabase JWT from the project JWKS. sub is the profile id. No sign-in route is added.
Out: The profile trigger (CORE-01-db). The sign-in screens. Any product endpoint.

## Acceptance criteria
- [ ] AC1 Given no Authorization header, when a protected handler runs, then the response is 401 with an error code.
- [ ] AC2 Given a JWT signed by a different key, when verifyJwt runs, then it rejects the token.
- [ ] AC3 Given a valid Supabase JWT, when verifyJwt runs, then sub equals the user id the profile trigger used.

## Design
Screen: n/a — this layer has no DESIGN screen.
Deviations from the plan: none

## Offline behaviour
Verification needs the JWKS, so it needs a network. There is no offline sign-in on the server.

## Module-off behaviour
Core is not gated. Nothing on this screen errors when shopping, pantry, or maintenance is off.

## Data and contract
Tables: profile (read via RLS, not written here)  Endpoints: none  Types: Profile
Schema change: no  Contract change: no

## Tests required
Unit: api/test/CORE-01-auth.test.ts → AC2 wrong key rejected; AC3 sub extracted
Component: n/a (no UI)
Integration: api/test/CORE-01-auth.int.test.ts → AC1 missing header is 401; AC3 valid JWT passes RLS as that user. No new OpenAPI path, so the contract check is that openapi.yaml gains nothing.
E2E: n/a (client journeys cover the user path)
Offline test: n/a (server-side; queued client writes are covered by the client offline test)

## Dependencies
Blocked by: CORE-01-db  Blocks: CORE-01-web, CORE-01-ios, CORE-01-android, CORE-02-api

## Skill
.claude/skills/node-backend/SKILL.md

## Open questions
none

# CORE-01-web — Sign in with email magic link, Apple and Google

## Summary
A person can get into AbodeNestly on the web with a sign-in they already use, so the household can start sharing immediately.

## Spec references
SPEC §4 CORE-01; §3 Clients/Web, §3 Environments; §11; DESIGN B1, B2; TESTING §3 -web row, §5 fixtures

## Scope
In: Sign-in screen (B1) with magic link, Apple and Google; session persistence; redirect to Welcome (B2) when the user has no household, otherwise to the last used household's start tab; sign-out from Profile.
Out: Welcome screen content (CORE-02-web), household switcher (CORE-07-web), profile editing (CORE-06-web).

## Acceptance criteria
- [ ] AC1 Given I am signed out, when I open any route, then I am redirected to /signin and the return path is kept.
- [ ] AC2 Given I enter a valid email and tap "Send magic link", when the request succeeds, then the form is replaced by the link-sent state and "Resend" is disabled for 30 s.
- [ ] AC3 Given I enter an invalid email, when I submit, then an inline error appears under the field and no request is sent.
- [ ] AC4 Given I open a valid magic link, when the page loads, then a Supabase session exists and I land on /welcome if I have no household, else on the last household's start tab.
- [ ] AC5 Given I tap Sign in with Apple or Google, when the provider flow completes, then the same landing rule as AC4 applies.
- [ ] AC6 Given I have a session and reload the page, then I stay signed in without re-authenticating.
- [ ] AC7 Given the browser is offline, when I open /signin, then the buttons are disabled and the offline banner is shown.
- [ ] AC8 Given I have a session, when my profile row exists (created by the server trigger), then the TopBar shows my display name.

## Design
Screen: DESIGN B1 — States: idle, sending, link-sent, error, offline. Interactions: submit email, tap Apple, tap Google, deep link.
Deviations from the plan: none

## Offline behaviour
Sign-in requires network (SPEC §6.5 Core). The screen renders offline with disabled actions and the OfflineBanner. An existing session is usable offline.

## Module-off behaviour
Core is not gated. The landing rule picks the first enabled module's tab, or Home if all are off.

## Data and contract
Tables: profile (read)  Endpoints: none (Supabase Auth)  Types: Profile
Schema change: no  Contract change: no

## Tests required
Unit: web/src/core/auth/landing.test.ts → AC4 and AC8 landing: no household → /welcome; one household → start tab; all modules off → /home
Component: web/src/core/auth/SignIn.test.tsx → AC1 idle redirect, AC2 sending and link-sent (resend timer), AC3 error, AC5 Apple and Google clicks, AC7 offline
Integration: n/a (no API code)
E2E: E2E-CORE-1 (first half) web/e2e/CORE-01-signin.spec.ts → AC4 magic link via admin generateLink helper, lands on /welcome, AC6 reload keeps session
Offline test: n/a (core)

## Dependencies
Blocked by: CORE-01-db, CORE-01-api  Blocks: CORE-02-web, CORE-06-web

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
none

# CORE-01-ios — Sign in with email magic link, Apple and Google

## Summary
A person can get into AbodeNestly with a sign-in they already use, so the household can start sharing immediately.

## Spec references
SPEC §4 CORE-01; §3 Clients; §11; DESIGN B1, B2; TESTING §3 -ios and -android rows, §5 fixtures

## Scope
In: Sign-in screen (B1) with magic link, Apple and Google; session persistence; navigation to Welcome (B2) when the user has no household, otherwise to the last used household's start tab.
Out: Welcome content (CORE-02), the switcher (CORE-07), profile editing (CORE-06). The web route table.

## Acceptance criteria
- [ ] AC1 Given I am signed out, when I launch the app, then the sign-in screen is shown.
- [ ] AC2 Given I enter a valid email and tap "Send magic link", when the request succeeds, then the form is replaced by the link-sent state and "Resend" is disabled for 30 s.
- [ ] AC3 Given I enter an invalid email, when I submit, then an inline error appears under the field and no request is sent.
- [ ] AC4 Given I open a valid magic link, when the app handles it, then a Supabase session exists and I land on Welcome if I have no household, else on the last household's start tab.
- [ ] AC5 Given I tap Sign in with Apple or Google, when the provider flow completes, then the same landing rule as AC4 applies.
- [ ] AC6 Given I have a session and the app is killed and reopened, then I stay signed in without re-authenticating.
- [ ] AC7 Given the device is offline, when I open the sign-in screen, then the buttons are disabled and the offline banner is shown.
- [ ] AC8 Given I have a session, when my profile row exists (created by the server trigger), then the TopBar shows my display name.

## Design
Screen: DESIGN B1 — States: idle, sending, link-sent, error, offline. Interactions: submit email, tap Apple, tap Google, deep link.
Deviations from the plan: none

## Offline behaviour
Sign-in requires network (SPEC §6.5 Core). The screen renders offline with disabled actions and the OfflineBanner. An existing session is usable offline.

## Module-off behaviour
Core is not gated. The landing rule picks the first enabled module's tab, or Home if all are off.

## Data and contract
Tables: profile (read)  Endpoints: none (Supabase Auth)  Types: Profile
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/core/Tests/SignInTests.swift → AC4 and AC8 landing: no household → welcome; one household → start tab; all modules off → home
Component: apps/ios/AbodeNestlyTests/SignInViewTests.swift → AC1 idle, AC2 sending and link-sent with resend timer, AC3 error, AC5 Apple and Google, AC7 offline; submit, Apple, Google call the auth client with the right provider. Also asserts AC6
Integration: n/a (no API handler in this story)
E2E: E2E-CORE-1 (first half) apps/ios/AbodeNestlyUITests/SignInUITests.swift
Offline test: n/a (core)

## Dependencies
Blocked by: CORE-01-db, CORE-01-api  Blocks: CORE-02-ios, CORE-06-ios

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
none

# CORE-01-android — Sign in with email magic link, Apple and Google

## Summary
A person can get into AbodeNestly with a sign-in they already use, so the household can start sharing immediately.

## Spec references
SPEC §4 CORE-01; §3 Clients; §11; DESIGN B1, B2; TESTING §3 -ios and -android rows, §5 fixtures

## Scope
In: Sign-in screen (B1) with magic link, Apple and Google; session persistence; navigation to Welcome (B2) when the user has no household, otherwise to the last used household's start tab.
Out: Welcome content (CORE-02), the switcher (CORE-07), profile editing (CORE-06). The web route table.

## Acceptance criteria
- [ ] AC1 Given I am signed out, when I launch the app, then the sign-in screen is shown.
- [ ] AC2 Given I enter a valid email and tap "Send magic link", when the request succeeds, then the form is replaced by the link-sent state and "Resend" is disabled for 30 s.
- [ ] AC3 Given I enter an invalid email, when I submit, then an inline error appears under the field and no request is sent.
- [ ] AC4 Given I open a valid magic link, when the app handles it, then a Supabase session exists and I land on Welcome if I have no household, else on the last household's start tab.
- [ ] AC5 Given I tap Sign in with Apple or Google, when the provider flow completes, then the same landing rule as AC4 applies.
- [ ] AC6 Given I have a session and the app is killed and reopened, then I stay signed in without re-authenticating.
- [ ] AC7 Given the device is offline, when I open the sign-in screen, then the buttons are disabled and the offline banner is shown.
- [ ] AC8 Given I have a session, when my profile row exists (created by the server trigger), then the TopBar shows my display name.

## Design
Screen: DESIGN B1 — States: idle, sending, link-sent, error, offline. Interactions: submit email, tap Apple, tap Google, deep link.
Deviations from the plan: none

## Offline behaviour
Sign-in requires network (SPEC §6.5 Core). The screen renders offline with disabled actions and the OfflineBanner. An existing session is usable offline.

## Module-off behaviour
Core is not gated. The landing rule picks the first enabled module's tab, or Home if all are off.

## Data and contract
Tables: profile (read)  Endpoints: none (Supabase Auth)  Types: Profile
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/core/src/test/java/app/abodenestly/core/SignInTest.kt → AC4 and AC8 landing: no household → welcome; one household → start tab; all modules off → home
Component: apps/android/core/src/test/java/app/abodenestly/core/SignInComposeTest.kt → AC1 idle, AC2 sending and link-sent with resend timer, AC3 error, AC5 Apple and Google, AC7 offline; submit, Apple, Google call the auth client with the right provider. Also asserts AC6
Integration: n/a (no API handler in this story)
E2E: E2E-CORE-1 (first half) apps/android/core/src/androidTest/java/app/abodenestly/core/SignInE2ETest.kt
Offline test: n/a (core)

## Dependencies
Blocked by: CORE-01-db, CORE-01-api  Blocks: CORE-02-android, CORE-06-android

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
none

# CORE-02-db — Seed the owner, Groceries, and default locations

## Summary
Creating a household makes that person the owner and already gives them a Groceries list and the usual food places.

## Spec references
SPEC §4 CORE-02; §7 POST /households; §14 create_household; TESTING §3 -db row

## Scope
In: Replace create_household so it inserts the household, an owner membership, a shopping list named Groceries, and locations Fridge, Freezer, and Pantry.
Out: The HTTP handler (CORE-02-api). The welcome screen (CORE-02 clients).

## Acceptance criteria
- [ ] AC1 Given an authenticated user, when create_household runs with a name, then a household row exists and that user is its only owner.
- [ ] AC2 Given create_household commits, when the lists are read, then one list named Groceries exists and it is not a template and not archived.
- [ ] AC3 Given create_household commits, when locations are read, then Fridge, Freezer, and Pantry exist.
- [ ] AC4 Given a user who is not the caller, when they select the new household, then RLS returns no row.

## Design
Screen: n/a — this layer has no DESIGN screen.
Deviations from the plan: none

## Offline behaviour
Creating a household is a household change and needs a network (SPEC §6.5). The function itself is server-side.

## Module-off behaviour
With any module flag later turned off, these seed rows remain; they are hidden by X7 rather than deleted.

## Data and contract
Tables: household, member, shopping_list, location  Endpoints: none (the function is called by CORE-02-api)  Types: Household, ShoppingList, Location
Schema change: yes — supabase/migrations/20261108100100_CORE-02_create_household.sql  Contract change: no

## Tests required
Unit: n/a (database behaviour is asserted in pgTAP, TESTING §2)
Component: n/a (no UI)
Integration: supabase/tests/CORE-02_create_household.sql → AC1 owner membership; AC2 Groceries list; AC3 three locations; AC4 stranger cannot select
E2E: n/a (covered by the client journeys for this ID)
Offline test: n/a (server-side)

## Dependencies
Blocked by: CORE-01-db  Blocks: CORE-02-api, CORE-02-web, CORE-02-ios, CORE-02-android, CORE-03-db, CORE-04-db, CORE-05-db, CORE-08-db, CORE-11-db, CORE-12-db, SHOP-01-db, PANT-01-db, PANT-08-db, PANT-13-db, MAINT-01-db, MAINT-09-db

## Skill
.claude/skills/sql-supabase/SKILL.md

## Open questions
§14 create_household inserts only the household and the owner. §4 requires the Groceries list and Fridge, Freezer, and Pantry. Proposed default: this migration replaces the function so the defaults are created in the same transaction.

# CORE-02-api — Create a household

## Summary
A person can name a household and get back the one they now own, with the defaults already in it.

## Spec references
SPEC §4 CORE-02; §7 POST /households; §8 Household; TESTING §3 -api row

## Scope
In: POST /households with { name } calls create_household and returns the Household.
Out: The SQL function (CORE-02-db). The welcome form (CORE-02 clients). Invites (CORE-03).

## Acceptance criteria
- [ ] AC1 Given a valid JWT and a non-empty name, when POST /households succeeds, then the response is that Household and the caller is the owner.
- [ ] AC2 Given no JWT, when POST /households is called, then the response is 401.
- [ ] AC3 Given a name that fails validation (empty), when POST /households is called, then the response is 400 with an error code and no household is created.
- [ ] AC4 Given the call succeeds, when the database is read, then Groceries, Fridge, Freezer, and Pantry exist for that household.

## Design
Screen: n/a — this layer has no DESIGN screen.
Deviations from the plan: none

## Offline behaviour
This endpoint is a household change and is only called online (SPEC §6.5).

## Module-off behaviour
Module flags default to on. Turning one off later is CORE-05, not this call.

## Data and contract
Tables: household, member, shopping_list, location  Endpoints: POST /households  Types: Household
Schema change: no  Contract change: yes — Household create request and response in packages/contract

## Tests required
Unit: api/test/CORE-02-households.test.ts → AC3 empty name rejected before SQL
Component: n/a (no UI)
Integration: api/test/CORE-02-households.int.test.ts → AC1 happy path returns Household; AC2 401; AC4 defaults exist. Contract check includes the new path.
E2E: n/a (client journeys cover the user path)
Offline test: n/a (server-side; queued client writes are covered by the client offline test)

## Dependencies
Blocked by: CORE-02-db, CORE-01-api  Blocks: CORE-02-web, CORE-02-ios, CORE-02-android

## Skill
.claude/skills/node-backend/SKILL.md

## Open questions
none

# CORE-02-web — Create or join from Welcome

## Summary
A person with no household yet can create one or join with a code, and lands in the new household.

## Spec references
SPEC §4 CORE-02, CORE-03; DESIGN B2; TESTING §3 client rows, §8 E2E-CORE-1

## Scope
In: Welcome (B2): create card and the create half of the landing. Join-with-a-code calls CORE-03 and is on this screen because B2 has both cards.
Out: Sign-in (CORE-01). The members screen (CORE-04). Module settings (CORE-05).

## Acceptance criteria
- [ ] AC1 Given I am signed in with no household, when Welcome loads, then I see my display name and both cards, in the idle state.
- [ ] AC2 Given I enter a household name and tap create, when the request succeeds, then I land in that household and Groceries, Fridge, Freezer, and Pantry are present.
- [ ] AC3 Given create fails, when the error returns, then the form shows the error state and the name I typed is still there.
- [ ] AC4 Given the device is offline, when I open Welcome, then both buttons are disabled and the OfflineBanner is shown.
- [ ] AC5 Given I enter a code and tap join, when the code is rejected, then the error-invalid-code state is shown. The successful join path is CORE-03.

## Design
Screen: DESIGN B2 — States: idle, creating, joining, error-invalid-code, offline. Interactions: create → B4 of the new household; join → B4.
Deviations from the plan: none

## Offline behaviour
Creating or joining a household needs a network (SPEC §6.5). Offline shows the banner and disables both buttons. Cached profile name still renders.

## Module-off behaviour
Core is not gated. Nothing on this screen errors when shopping, pantry, or maintenance is off.

## Data and contract
Tables: household, shopping_list, location  Endpoints: POST /households; POST /invites/{code}/accept  Types: Household
Schema change: no  Contract change: no

## Tests required
Unit: web/src/core/Welcome.test.ts → AC2 after a fake success the landing route is the new household home
Component: web/src/core/Welcome.test.tsx → AC1 idle, AC2 creating, AC3 error, AC4 offline, AC5 error-invalid-code; create tap and join tap
Integration: n/a (no API handler in this story)
E2E: E2E-CORE-1 web/e2e/Welcome.spec.ts
Offline test: n/a (core)

## Dependencies
Blocked by: CORE-02-db, CORE-02-api, CORE-01-web  Blocks: CORE-03-web, CORE-07-web, SHOP-01-web, PANT-01-web

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
none

# CORE-02-ios — Create or join from Welcome

## Summary
A person with no household yet can create one or join with a code, and lands in the new household.

## Spec references
SPEC §4 CORE-02, CORE-03; DESIGN B2; TESTING §3 client rows, §8 E2E-CORE-1

## Scope
In: Welcome (B2): create card and the create half of the landing. Join-with-a-code calls CORE-03 and is on this screen because B2 has both cards.
Out: Sign-in (CORE-01). The members screen (CORE-04). Module settings (CORE-05).

## Acceptance criteria
- [ ] AC1 Given I am signed in with no household, when Welcome loads, then I see my display name and both cards, in the idle state.
- [ ] AC2 Given I enter a household name and tap create, when the request succeeds, then I land in that household and Groceries, Fridge, Freezer, and Pantry are present.
- [ ] AC3 Given create fails, when the error returns, then the form shows the error state and the name I typed is still there.
- [ ] AC4 Given the device is offline, when I open Welcome, then both buttons are disabled and the OfflineBanner is shown.
- [ ] AC5 Given I enter a code and tap join, when the code is rejected, then the error-invalid-code state is shown. The successful join path is CORE-03.

## Design
Screen: DESIGN B2 — States: idle, creating, joining, error-invalid-code, offline. Interactions: create → B4 of the new household; join → B4.
Deviations from the plan: none

## Offline behaviour
Creating or joining a household needs a network (SPEC §6.5). Offline shows the banner and disables both buttons. Cached profile name still renders.

## Module-off behaviour
Core is not gated. Nothing on this screen errors when shopping, pantry, or maintenance is off.

## Data and contract
Tables: household, shopping_list, location  Endpoints: POST /households; POST /invites/{code}/accept  Types: Household
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/core/Tests/WelcomeTests.swift → AC2 after a fake success the landing route is the new household home
Component: apps/ios/AbodeNestlyTests/WelcomeViewTests.swift → AC1 idle, AC2 creating, AC3 error, AC4 offline, AC5 error-invalid-code; create tap and join tap
Integration: n/a (no API handler in this story)
E2E: E2E-CORE-1 apps/ios/AbodeNestlyUITests/WelcomeUITests.swift
Offline test: n/a (core)

## Dependencies
Blocked by: CORE-02-db, CORE-02-api, CORE-01-ios  Blocks: CORE-03-ios, CORE-07-ios, SHOP-01-ios, PANT-01-ios

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
none

# CORE-02-android — Create or join from Welcome

## Summary
A person with no household yet can create one or join with a code, and lands in the new household.

## Spec references
SPEC §4 CORE-02, CORE-03; DESIGN B2; TESTING §3 client rows, §8 E2E-CORE-1

## Scope
In: Welcome (B2): create card and the create half of the landing. Join-with-a-code calls CORE-03 and is on this screen because B2 has both cards.
Out: Sign-in (CORE-01). The members screen (CORE-04). Module settings (CORE-05).

## Acceptance criteria
- [ ] AC1 Given I am signed in with no household, when Welcome loads, then I see my display name and both cards, in the idle state.
- [ ] AC2 Given I enter a household name and tap create, when the request succeeds, then I land in that household and Groceries, Fridge, Freezer, and Pantry are present.
- [ ] AC3 Given create fails, when the error returns, then the form shows the error state and the name I typed is still there.
- [ ] AC4 Given the device is offline, when I open Welcome, then both buttons are disabled and the OfflineBanner is shown.
- [ ] AC5 Given I enter a code and tap join, when the code is rejected, then the error-invalid-code state is shown. The successful join path is CORE-03.

## Design
Screen: DESIGN B2 — States: idle, creating, joining, error-invalid-code, offline. Interactions: create → B4 of the new household; join → B4.
Deviations from the plan: none

## Offline behaviour
Creating or joining a household needs a network (SPEC §6.5). Offline shows the banner and disables both buttons. Cached profile name still renders.

## Module-off behaviour
Core is not gated. Nothing on this screen errors when shopping, pantry, or maintenance is off.

## Data and contract
Tables: household, shopping_list, location  Endpoints: POST /households; POST /invites/{code}/accept  Types: Household
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/core/src/test/java/app/abodenestly/core/WelcomeTest.kt → AC2 after a fake success the landing route is the new household home
Component: apps/android/core/src/test/java/app/abodenestly/core/WelcomeComposeTest.kt → AC1 idle, AC2 creating, AC3 error, AC4 offline, AC5 error-invalid-code; create tap and join tap
Integration: n/a (no API handler in this story)
E2E: E2E-CORE-1 apps/android/core/src/androidTest/java/app/abodenestly/core/WelcomeE2ETest.kt
Offline test: n/a (core)

## Dependencies
Blocked by: CORE-02-db, CORE-02-api, CORE-01-android  Blocks: CORE-03-android, CORE-07-android, SHOP-01-android, PANT-01-android

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
none

# CORE-03-db — Invites expire, run out, and join as the invited role

## Summary
A link or a code lets someone in only while it is still live, and then they are a member of that household.

## Spec references
SPEC §4 CORE-03; §7 invite endpoints; §14 accept_invite; TESTING §3 -db row

## Scope
In: invite table with expiry and max uses. accept_invite joins the caller, increments uses, and rejects expired, exhausted, or unknown codes.
Out: HTTP handlers (CORE-03-api). The invite sheet UI (CORE-03 clients).

## Acceptance criteria
- [ ] AC1 Given a live invite, when accept_invite runs, then the caller has a membership and uses increases by one.
- [ ] AC2 Given the invite is expired, when accept_invite runs, then it raises invalid_invite and no membership is created.
- [ ] AC3 Given uses already equals max_uses, when accept_invite runs, then it raises invalid_invite.
- [ ] AC4 Given the caller already left that household, when they accept a live invite, then left_at is cleared.

## Design
Screen: n/a — this layer has no DESIGN screen.
Deviations from the plan: none

## Offline behaviour
Accepting an invite is a membership change and needs a network (SPEC §6.5).

## Module-off behaviour
Invites are core. Module flags do not block joining.

## Data and contract
Tables: invite, member  Endpoints: none (called by CORE-03-api)  Types: Invite, Member
Schema change: yes — supabase/migrations/20261108100200_CORE-03_invite.sql  Contract change: no

## Tests required
Unit: n/a (database behaviour is asserted in pgTAP, TESTING §2)
Component: n/a (no UI)
Integration: supabase/tests/CORE-03_invite.sql → AC1 join increments uses; AC2 expired; AC3 exhausted; AC4 rejoin clears left_at
E2E: n/a (covered by the client journeys for this ID)
Offline test: n/a (server-side)

## Dependencies
Blocked by: CORE-02-db  Blocks: CORE-03-api, CORE-03-web, CORE-03-ios, CORE-03-android

## Skill
.claude/skills/sql-supabase/SKILL.md

## Open questions
§4 says accepting joins as a member. §7 and §14 allow the invite to carry a role. Proposed default: the invite's role is stored and accept_invite uses it, and the client sheet in this story only creates role member.

# CORE-03-api — Create and accept an invite

## Summary
A member can mint a code and someone else can spend it to join.

## Spec references
SPEC §4 CORE-03; §7 POST /households/{id}/invites and POST /invites/{code}/accept; TESTING §3 -api row

## Scope
In: Create-invite and accept-invite handlers, including expiry and max uses on the create body.
Out: The SQL function (CORE-03-db). Removing a member (CORE-04).

## Acceptance criteria
- [ ] AC1 Given an owner or member, when they POST an invite, then the response includes a code, an expiry, and a max uses.
- [ ] AC2 Given no JWT, when either route is called, then the response is 401.
- [ ] AC3 Given a missing or expired code, when accept is called, then the response is 400 invalid_invite and no membership is written.
- [ ] AC4 Given a live code, when accept is called, then the response is the household id and the caller is a member.

## Design
Screen: n/a — this layer has no DESIGN screen.
Deviations from the plan: none

## Offline behaviour
Both calls change membership and are online-only (SPEC §6.5).

## Module-off behaviour
Module flags do not gate these routes.

## Data and contract
Tables: invite, member  Endpoints: POST /households/{id}/invites; POST /invites/{code}/accept  Types: Invite
Schema change: no  Contract change: yes

## Tests required
Unit: api/test/CORE-03-invites.test.ts → AC3 invalid code shape rejected
Component: n/a (no UI)
Integration: api/test/CORE-03-invites.int.test.ts → AC1 create; AC2 401; AC4 accept joins. Contract check covers both paths.
E2E: n/a (client journeys cover the user path)
Offline test: n/a (server-side; queued client writes are covered by the client offline test)

## Dependencies
Blocked by: CORE-03-db  Blocks: CORE-03-web, CORE-03-ios, CORE-03-android

## Skill
.claude/skills/node-backend/SKILL.md

## Open questions
none

# CORE-03-web — Invite by link or code

## Summary
A member can hand someone a code or a link, and that person can join the household.

## Spec references
SPEC §4 CORE-03; DESIGN B2, B6; TESTING §8 E2E-CORE-1

## Scope
In: The invite sheet on B6 (code, share link, expiry, uses left, New code) and the join card on B2.
Out: Member rows, remove, and leave (CORE-04). Creating the household (CORE-02).

## Acceptance criteria
- [ ] AC1 Given I am a member, when I open Invite, then I see a large code, uses left, and expiry.
- [ ] AC2 Given I tap New code, when the request succeeds, then the sheet shows the new code and the previous code no longer joins.
- [ ] AC3 Given I tap share, when the sheet opens, then the platform share sheet is presented with the link.
- [ ] AC4 Given I am on Welcome and I enter a live code, when I tap join, then I land in that household.
- [ ] AC5 Given the device is offline, when I open the invite sheet, then New code and share are disabled and the OfflineBanner is shown.

## Design
Screen: DESIGN B6 — States: owner, member, invite-sheet, offline. Interactions: share → system share sheet; New code. Join itself is B2..
Deviations from the plan: none

## Offline behaviour
Creating or accepting an invite needs a network (SPEC §6.5). Existing member rows stay readable offline.

## Module-off behaviour
Core is not gated. Nothing on this screen errors when shopping, pantry, or maintenance is off.

## Data and contract
Tables: invite, member  Endpoints: POST /households/{id}/invites; POST /invites/{code}/accept  Types: Invite
Schema change: no  Contract change: no

## Tests required
Unit: web/src/core/InviteSheet.test.ts → AC4 a live code navigates to the joined household
Component: web/src/core/InviteSheet.test.tsx → AC1 invite-sheet, AC2 new code replaces the old, AC3 share tap, AC5 offline; owner and member both see the sheet
Integration: n/a (no API handler in this story)
E2E: E2E-CORE-1 web/e2e/InviteSheet.spec.ts
Offline test: n/a (core)

## Dependencies
Blocked by: CORE-03-db, CORE-03-api, CORE-02-web  Blocks: CORE-07-web

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
none

# CORE-03-ios — Invite by link or code

## Summary
A member can hand someone a code or a link, and that person can join the household.

## Spec references
SPEC §4 CORE-03; DESIGN B2, B6; TESTING §8 E2E-CORE-1

## Scope
In: The invite sheet on B6 (code, share link, expiry, uses left, New code) and the join card on B2.
Out: Member rows, remove, and leave (CORE-04). Creating the household (CORE-02).

## Acceptance criteria
- [ ] AC1 Given I am a member, when I open Invite, then I see a large code, uses left, and expiry.
- [ ] AC2 Given I tap New code, when the request succeeds, then the sheet shows the new code and the previous code no longer joins.
- [ ] AC3 Given I tap share, when the sheet opens, then the platform share sheet is presented with the link.
- [ ] AC4 Given I am on Welcome and I enter a live code, when I tap join, then I land in that household.
- [ ] AC5 Given the device is offline, when I open the invite sheet, then New code and share are disabled and the OfflineBanner is shown.

## Design
Screen: DESIGN B6 — States: owner, member, invite-sheet, offline. Interactions: share → system share sheet; New code. Join itself is B2..
Deviations from the plan: none

## Offline behaviour
Creating or accepting an invite needs a network (SPEC §6.5). Existing member rows stay readable offline.

## Module-off behaviour
Core is not gated. Nothing on this screen errors when shopping, pantry, or maintenance is off.

## Data and contract
Tables: invite, member  Endpoints: POST /households/{id}/invites; POST /invites/{code}/accept  Types: Invite
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/core/Tests/InviteSheetTests.swift → AC4 a live code navigates to the joined household
Component: apps/ios/AbodeNestlyTests/InviteSheetViewTests.swift → AC1 invite-sheet, AC2 new code replaces the old, AC3 share tap, AC5 offline; owner and member both see the sheet
Integration: n/a (no API handler in this story)
E2E: E2E-CORE-1 apps/ios/AbodeNestlyUITests/InviteSheetUITests.swift
Offline test: n/a (core)

## Dependencies
Blocked by: CORE-03-db, CORE-03-api, CORE-02-ios  Blocks: CORE-07-ios

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
none

# CORE-03-android — Invite by link or code

## Summary
A member can hand someone a code or a link, and that person can join the household.

## Spec references
SPEC §4 CORE-03; DESIGN B2, B6; TESTING §8 E2E-CORE-1

## Scope
In: The invite sheet on B6 (code, share link, expiry, uses left, New code) and the join card on B2.
Out: Member rows, remove, and leave (CORE-04). Creating the household (CORE-02).

## Acceptance criteria
- [ ] AC1 Given I am a member, when I open Invite, then I see a large code, uses left, and expiry.
- [ ] AC2 Given I tap New code, when the request succeeds, then the sheet shows the new code and the previous code no longer joins.
- [ ] AC3 Given I tap share, when the sheet opens, then the platform share sheet is presented with the link.
- [ ] AC4 Given I am on Welcome and I enter a live code, when I tap join, then I land in that household.
- [ ] AC5 Given the device is offline, when I open the invite sheet, then New code and share are disabled and the OfflineBanner is shown.

## Design
Screen: DESIGN B6 — States: owner, member, invite-sheet, offline. Interactions: share → system share sheet; New code. Join itself is B2..
Deviations from the plan: none

## Offline behaviour
Creating or accepting an invite needs a network (SPEC §6.5). Existing member rows stay readable offline.

## Module-off behaviour
Core is not gated. Nothing on this screen errors when shopping, pantry, or maintenance is off.

## Data and contract
Tables: invite, member  Endpoints: POST /households/{id}/invites; POST /invites/{code}/accept  Types: Invite
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/core/src/test/java/app/abodenestly/core/InviteSheetTest.kt → AC4 a live code navigates to the joined household
Component: apps/android/core/src/test/java/app/abodenestly/core/InviteSheetComposeTest.kt → AC1 invite-sheet, AC2 new code replaces the old, AC3 share tap, AC5 offline; owner and member both see the sheet
Integration: n/a (no API handler in this story)
E2E: E2E-CORE-1 apps/android/core/src/androidTest/java/app/abodenestly/core/InviteSheetE2ETest.kt
Offline test: n/a (core)

## Dependencies
Blocked by: CORE-03-db, CORE-03-api, CORE-02-android  Blocks: CORE-07-android

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
none

# CORE-04-db — Last owner cannot leave

## Summary
An owner can remove someone, and the household cannot be left with nobody in charge.

## Spec references
SPEC §4 CORE-04; §11 member policies; TESTING §3 -db row

## Scope
In: A guard that rejects removing or leaving when the caller is the only remaining owner. Owner-or-self update policy stays for non-final owners.
Out: The DELETE handler (CORE-04-api). The member rows UI (CORE-04 clients).

## Acceptance criteria
- [ ] AC1 Given two owners, when one leaves, then their left_at is set and the other remains owner.
- [ ] AC2 Given one owner, when that owner leaves, then the change is rejected and left_at stays null.
- [ ] AC3 Given an owner removes a member, when the statement commits, then that member's left_at is set.
- [ ] AC4 Given a member who is not an owner, when they try to remove someone else, then RLS or the guard rejects it.

## Design
Screen: n/a — this layer has no DESIGN screen.
Deviations from the plan: none

## Offline behaviour
Membership changes need a network (SPEC §6.5). The guard runs on the server so an offline queue cannot bypass it.

## Module-off behaviour
Membership is core. Module flags do not change who may leave.

## Data and contract
Tables: member  Endpoints: none  Types: Member
Schema change: yes — supabase/migrations/20261108100300_CORE-04_last_owner.sql  Contract change: no

## Tests required
Unit: n/a (database behaviour is asserted in pgTAP, TESTING §2)
Component: n/a (no UI)
Integration: supabase/tests/CORE-04_members.sql → AC1 second owner can leave; AC2 sole owner rejected; AC3 owner removes member; AC4 member cannot remove others
E2E: n/a (covered by the client journeys for this ID)
Offline test: n/a (server-side)

## Dependencies
Blocked by: CORE-02-db  Blocks: CORE-04-api, CORE-04-web, CORE-04-ios, CORE-04-android

## Skill
.claude/skills/sql-supabase/SKILL.md

## Open questions
§14 lets an owner or the user update member, and it does not yet reject the last owner. Proposed default: a BEFORE UPDATE trigger raises last_owner when the update would leave the household with zero owners.

# CORE-04-api — Remove a member or leave

## Summary
The household can take someone off the member list, and a person can leave, unless they are the last owner.

## Spec references
SPEC §4 CORE-04; §7 DELETE /households/{id}/members/{user_id}; TESTING §3 -api row

## Scope
In: DELETE handler for remove and leave, returning 204, and 409 when the last-owner guard rejects.
Out: The SQL guard (CORE-04-db). The overflow menu (CORE-04 clients).

## Acceptance criteria
- [ ] AC1 Given an owner and another member, when the owner DELETEs that member, then the response is 204 and left_at is set.
- [ ] AC2 Given no JWT, when DELETE is called, then the response is 401.
- [ ] AC3 Given a user id that is not a member, when DELETE is called, then the response is 404.
- [ ] AC4 Given the caller is the only owner, when they DELETE themselves, then the response is 409 last_owner and they are still owner.

## Design
Screen: n/a — this layer has no DESIGN screen.
Deviations from the plan: none

## Offline behaviour
Remove and leave need a network (SPEC §6.5).

## Module-off behaviour
Not gated by module flags.

## Data and contract
Tables: member  Endpoints: DELETE /households/{id}/members/{user_id}  Types: Member
Schema change: no  Contract change: yes

## Tests required
Unit: api/test/CORE-04-members.test.ts → AC3 unknown id is 404 before the guard
Component: n/a (no UI)
Integration: api/test/CORE-04-members.int.test.ts → AC1 204; AC2 401; AC4 409 last_owner. Contract check covers the route.
E2E: n/a (client journeys cover the user path)
Offline test: n/a (server-side; queued client writes are covered by the client offline test)

## Dependencies
Blocked by: CORE-04-db  Blocks: CORE-04-web, CORE-04-ios, CORE-04-android

## Skill
.claude/skills/node-backend/SKILL.md

## Open questions
none

# CORE-04-web — Members, roles, remove, and leave

## Summary
Everyone can see who is in the household, an owner can remove someone, and a person can leave.

## Spec references
SPEC §4 CORE-04; DESIGN B6; TESTING §8 E2E-CORE-1

## Scope
In: Member rows on B6: avatar, name, role, you, and the owner overflow actions that this story owns (remove, and make owner).
Out: The invite sheet contents (CORE-03). Sign-in (CORE-01).

## Acceptance criteria
- [ ] AC1 Given the household has members, when I open Members, then each row shows avatar, name, role, and a you marker on me.
- [ ] AC2 Given I am an owner, when I remove someone else, then an undo toast shows for 5 seconds and only then is DELETE called.
- [ ] AC3 Given I tap Undo within 5 seconds, when the toast is up, then DELETE is not called and the row stays.
- [ ] AC4 Given I am the only owner, when I try to leave, then I stay and I am told I am the last owner.
- [ ] AC5 Given I am a member and not an owner, when I view the list, then I have no remove action, and I can leave.
- [ ] AC6 Given the device is offline, when I open Members, then the list is readable and remove, leave, and make-owner are disabled with the OfflineBanner.

## Design
Screen: DESIGN B6 — States: owner, member, invite-sheet, offline. Interactions: remove → undo toast 5 s then DELETE; make owner.
Deviations from the plan: none

## Offline behaviour
The member list is readable offline (SPEC §6.5). Remove, leave, and role changes need a network. DESIGN B6's undo toast delays the DELETE by 5 seconds while online.

## Module-off behaviour
Core is not gated. Nothing on this screen errors when shopping, pantry, or maintenance is off.

## Data and contract
Tables: member, profile  Endpoints: DELETE /households/{id}/members/{user_id}  Types: Member, Profile
Schema change: no  Contract change: no

## Tests required
Unit: web/src/core/Members.test.ts → AC3 undo within 5 s does not call DELETE; AC4 last owner is blocked in the view model before the call
Component: web/src/core/Members.test.tsx → AC1 owner populated, AC2 remove starts the toast, AC5 member has no remove, AC6 offline; overflow remove and leave
Integration: n/a (no API handler in this story)
E2E: E2E-CORE-1 web/e2e/Members.spec.ts
Offline test: n/a (core)

## Dependencies
Blocked by: CORE-04-db, CORE-04-api  Blocks: MAINT-15-web

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
DESIGN B6 includes make owner, and §4 names the roles but does not say an existing member's role can be changed. Proposed default: the overflow action promotes a member to owner, because the screen plan lists it and §4 already has both roles.

# CORE-04-ios — Members, roles, remove, and leave

## Summary
Everyone can see who is in the household, an owner can remove someone, and a person can leave.

## Spec references
SPEC §4 CORE-04; DESIGN B6; TESTING §8 E2E-CORE-1

## Scope
In: Member rows on B6: avatar, name, role, you, and the owner overflow actions that this story owns (remove, and make owner).
Out: The invite sheet contents (CORE-03). Sign-in (CORE-01).

## Acceptance criteria
- [ ] AC1 Given the household has members, when I open Members, then each row shows avatar, name, role, and a you marker on me.
- [ ] AC2 Given I am an owner, when I remove someone else, then an undo toast shows for 5 seconds and only then is DELETE called.
- [ ] AC3 Given I tap Undo within 5 seconds, when the toast is up, then DELETE is not called and the row stays.
- [ ] AC4 Given I am the only owner, when I try to leave, then I stay and I am told I am the last owner.
- [ ] AC5 Given I am a member and not an owner, when I view the list, then I have no remove action, and I can leave.
- [ ] AC6 Given the device is offline, when I open Members, then the list is readable and remove, leave, and make-owner are disabled with the OfflineBanner.

## Design
Screen: DESIGN B6 — States: owner, member, invite-sheet, offline. Interactions: remove → undo toast 5 s then DELETE; make owner.
Deviations from the plan: none

## Offline behaviour
The member list is readable offline (SPEC §6.5). Remove, leave, and role changes need a network. DESIGN B6's undo toast delays the DELETE by 5 seconds while online.

## Module-off behaviour
Core is not gated. Nothing on this screen errors when shopping, pantry, or maintenance is off.

## Data and contract
Tables: member, profile  Endpoints: DELETE /households/{id}/members/{user_id}  Types: Member, Profile
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/core/Tests/MembersTests.swift → AC3 undo within 5 s does not call DELETE; AC4 last owner is blocked in the view model before the call
Component: apps/ios/AbodeNestlyTests/MembersViewTests.swift → AC1 owner populated, AC2 remove starts the toast, AC5 member has no remove, AC6 offline; overflow remove and leave
Integration: n/a (no API handler in this story)
E2E: E2E-CORE-1 apps/ios/AbodeNestlyUITests/MembersUITests.swift
Offline test: n/a (core)

## Dependencies
Blocked by: CORE-04-db, CORE-04-api  Blocks: MAINT-15-ios

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
DESIGN B6 includes make owner, and §4 names the roles but does not say an existing member's role can be changed. Proposed default: the overflow action promotes a member to owner, because the screen plan lists it and §4 already has both roles.

# CORE-04-android — Members, roles, remove, and leave

## Summary
Everyone can see who is in the household, an owner can remove someone, and a person can leave.

## Spec references
SPEC §4 CORE-04; DESIGN B6; TESTING §8 E2E-CORE-1

## Scope
In: Member rows on B6: avatar, name, role, you, and the owner overflow actions that this story owns (remove, and make owner).
Out: The invite sheet contents (CORE-03). Sign-in (CORE-01).

## Acceptance criteria
- [ ] AC1 Given the household has members, when I open Members, then each row shows avatar, name, role, and a you marker on me.
- [ ] AC2 Given I am an owner, when I remove someone else, then an undo toast shows for 5 seconds and only then is DELETE called.
- [ ] AC3 Given I tap Undo within 5 seconds, when the toast is up, then DELETE is not called and the row stays.
- [ ] AC4 Given I am the only owner, when I try to leave, then I stay and I am told I am the last owner.
- [ ] AC5 Given I am a member and not an owner, when I view the list, then I have no remove action, and I can leave.
- [ ] AC6 Given the device is offline, when I open Members, then the list is readable and remove, leave, and make-owner are disabled with the OfflineBanner.

## Design
Screen: DESIGN B6 — States: owner, member, invite-sheet, offline. Interactions: remove → undo toast 5 s then DELETE; make owner.
Deviations from the plan: none

## Offline behaviour
The member list is readable offline (SPEC §6.5). Remove, leave, and role changes need a network. DESIGN B6's undo toast delays the DELETE by 5 seconds while online.

## Module-off behaviour
Core is not gated. Nothing on this screen errors when shopping, pantry, or maintenance is off.

## Data and contract
Tables: member, profile  Endpoints: DELETE /households/{id}/members/{user_id}  Types: Member, Profile
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/core/src/test/java/app/abodenestly/core/MembersTest.kt → AC3 undo within 5 s does not call DELETE; AC4 last owner is blocked in the view model before the call
Component: apps/android/core/src/test/java/app/abodenestly/core/MembersComposeTest.kt → AC1 owner populated, AC2 remove starts the toast, AC5 member has no remove, AC6 offline; overflow remove and leave
Integration: n/a (no API handler in this story)
E2E: E2E-CORE-1 apps/android/core/src/androidTest/java/app/abodenestly/core/MembersE2ETest.kt
Offline test: n/a (core)

## Dependencies
Blocked by: CORE-04-db, CORE-04-api  Blocks: MAINT-15-android

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
DESIGN B6 includes make owner, and §4 names the roles but does not say an existing member's role can be changed. Proposed default: the overflow action promotes a member to owner, because the screen plan lists it and §4 already has both roles.

# CORE-05-db — Module flags are owner-writable

## Summary
An owner can turn shopping, pantry, or maintenance off for one household without touching the others.

## Spec references
SPEC §4 CORE-05; §9 X7; §14 household columns; TESTING §3 -db row

## Scope
In: household.module_shopping, module_pantry, module_maintenance. Update policy is owner-only. A non-owner update of those columns fails.
Out: PATCH handler (CORE-05-api). The sync rejection for a disabled module is X7-api. Tab hiding is X7 clients.

## Acceptance criteria
- [ ] AC1 Given an owner, when they set module_pantry to false, then the row stores false and the other two flags stay as they were.
- [ ] AC2 Given a member who is not an owner, when they update a module flag, then the update is rejected.
- [ ] AC3 Given a user outside the household, when they select the household, then they see no row.

## Design
Screen: n/a — this layer has no DESIGN screen.
Deviations from the plan: none

## Offline behaviour
Changing flags is a household change and needs a network (SPEC §6.5).

## Module-off behaviour
This story stores the flags X7 reads. It does not itself hide tabs.

## Data and contract
Tables: household  Endpoints: none  Types: Household
Schema change: yes — supabase/migrations/20261108100400_CORE-05_module_flags.sql  Contract change: no

## Tests required
Unit: n/a (database behaviour is asserted in pgTAP, TESTING §2)
Component: n/a (no UI)
Integration: supabase/tests/CORE-05_flags.sql → AC1 owner updates one flag; AC2 member rejected; AC3 non-member cannot select
E2E: n/a (covered by the client journeys for this ID)
Offline test: n/a (server-side)

## Dependencies
Blocked by: CORE-02-db  Blocks: CORE-05-api, CORE-05-web, CORE-05-ios, CORE-05-android, SHOP-08-api, X7-api

## Skill
.claude/skills/sql-supabase/SKILL.md

## Open questions
none

# CORE-05-api — Toggle modules

## Summary
An owner can switch a module off from settings and the household record comes back with the new flags.

## Spec references
SPEC §4 CORE-05; §7 PATCH /households/{id}/modules; TESTING §3 -api row

## Scope
In: PATCH /households/{id}/modules for an owner, partial body, returns Household.
Out: Flag columns (CORE-05-db). The settings switches (CORE-05 clients). Sync rejection (X7-api).

## Acceptance criteria
- [ ] AC1 Given an owner, when they PATCH pantry to false, then the response household has module_pantry false and the other flags unchanged.
- [ ] AC2 Given a non-owner member, when they PATCH, then the response is 403.
- [ ] AC3 Given no JWT, when they PATCH, then the response is 401.
- [ ] AC4 Given a body with no flags, when they PATCH, then the response is 400 and nothing changes.

## Design
Screen: n/a — this layer has no DESIGN screen.
Deviations from the plan: none

## Offline behaviour
The PATCH is a household change and is online-only (SPEC §6.5).

## Module-off behaviour
This route writes the flags. Hiding tabs and rejecting mutations is X7.

## Data and contract
Tables: household  Endpoints: PATCH /households/{id}/modules  Types: Household
Schema change: no  Contract change: yes

## Tests required
Unit: api/test/CORE-05-modules.test.ts → AC4 empty body is 400
Component: n/a (no UI)
Integration: api/test/CORE-05-modules.int.test.ts → AC1 owner patch; AC2 403; AC3 401. Contract check covers the route.
E2E: n/a (client journeys cover the user path)
Offline test: n/a (server-side; queued client writes are covered by the client offline test)

## Dependencies
Blocked by: CORE-05-db  Blocks: CORE-05-web, CORE-05-ios, CORE-05-android

## Skill
.claude/skills/node-backend/SKILL.md

## Open questions
none

# CORE-05-web — Module toggles

## Summary
An owner can turn a module off and the household stops seeing that part of the app.

## Spec references
SPEC §4 CORE-05; DESIGN B5; TESTING §8 E2E-CORE-3

## Scope
In: Household settings (B5): name field, the three module switches, currency picker, leave and delete.
Out: The tab shell that hides a tab is X7. Invites (CORE-03).

## Acceptance criteria
- [ ] AC1 Given I am an owner and online, when I open Household settings, then I see the name field, three switches, and the currency picker in the owner state.
- [ ] AC2 Given I am a member and not an owner, when I open settings, then the fields are read-only.
- [ ] AC3 Given I am an owner, when I turn pantry off, then PATCH succeeds and the pantry tab disappears without a reload.
- [ ] AC4 Given the device is offline, when I open settings, then the switches are disabled and the OfflineBanner says to connect to change household settings.
- [ ] AC5 Given I am a member, when I tap Leave and confirm, then I leave and land on Welcome or the switcher.

## Design
Screen: DESIGN B5 — States: owner, member, offline, saving. Interactions: toggle module → PATCH → tabs update; leave → confirm sheet → B2 or switcher.
Deviations from the plan: none

## Offline behaviour
Settings are readable offline. Changing the name, flags, currency, leave, or delete needs a network (SPEC §6.5). DESIGN B5 matches that: offline is read-only.

## Module-off behaviour
The settings screen itself stays, because core is not a module. Turning a module off hides that module's tab (X7) and leaves the others.

## Data and contract
Tables: household  Endpoints: PATCH /households/{id}/modules  Types: Household
Schema change: no  Contract change: no

## Tests required
Unit: web/src/core/HouseholdSettings.test.ts → AC3 a successful toggle updates the local flags used by the tab bar
Component: web/src/core/HouseholdSettings.test.tsx → AC1 owner, AC2 member read-only, AC3 saving, AC4 offline; toggle and leave. Also asserts AC5
Integration: n/a (no API handler in this story)
E2E: E2E-CORE-3 web/e2e/HouseholdSettings.spec.ts
Offline test: n/a (core)

## Dependencies
Blocked by: CORE-05-db, CORE-05-api  Blocks: CORE-09-web, CORE-10-web, CORE-12-web, X7-web

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
DESIGN B5 includes a currency picker and delete-household, and §4 CORE-05 only names the module toggles. Proposed default: both controls ship on this screen because they are part of B5; delete requires typing the household name and is owner-only.

# CORE-05-ios — Module toggles

## Summary
An owner can turn a module off and the household stops seeing that part of the app.

## Spec references
SPEC §4 CORE-05; DESIGN B5; TESTING §8 E2E-CORE-3

## Scope
In: Household settings (B5): name field, the three module switches, currency picker, leave and delete.
Out: The tab shell that hides a tab is X7. Invites (CORE-03).

## Acceptance criteria
- [ ] AC1 Given I am an owner and online, when I open Household settings, then I see the name field, three switches, and the currency picker in the owner state.
- [ ] AC2 Given I am a member and not an owner, when I open settings, then the fields are read-only.
- [ ] AC3 Given I am an owner, when I turn pantry off, then PATCH succeeds and the pantry tab disappears without a reload.
- [ ] AC4 Given the device is offline, when I open settings, then the switches are disabled and the OfflineBanner says to connect to change household settings.
- [ ] AC5 Given I am a member, when I tap Leave and confirm, then I leave and land on Welcome or the switcher.

## Design
Screen: DESIGN B5 — States: owner, member, offline, saving. Interactions: toggle module → PATCH → tabs update; leave → confirm sheet → B2 or switcher.
Deviations from the plan: none

## Offline behaviour
Settings are readable offline. Changing the name, flags, currency, leave, or delete needs a network (SPEC §6.5). DESIGN B5 matches that: offline is read-only.

## Module-off behaviour
The settings screen itself stays, because core is not a module. Turning a module off hides that module's tab (X7) and leaves the others.

## Data and contract
Tables: household  Endpoints: PATCH /households/{id}/modules  Types: Household
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/core/Tests/HouseholdSettingsTests.swift → AC3 a successful toggle updates the local flags used by the tab bar
Component: apps/ios/AbodeNestlyTests/HouseholdSettingsViewTests.swift → AC1 owner, AC2 member read-only, AC3 saving, AC4 offline; toggle and leave. Also asserts AC5
Integration: n/a (no API handler in this story)
E2E: E2E-CORE-3 apps/ios/AbodeNestlyUITests/HouseholdSettingsUITests.swift
Offline test: n/a (core)

## Dependencies
Blocked by: CORE-05-db, CORE-05-api  Blocks: CORE-09-ios, CORE-10-ios, CORE-12-ios, X7-ios

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
DESIGN B5 includes a currency picker and delete-household, and §4 CORE-05 only names the module toggles. Proposed default: both controls ship on this screen because they are part of B5; delete requires typing the household name and is owner-only.

# CORE-05-android — Module toggles

## Summary
An owner can turn a module off and the household stops seeing that part of the app.

## Spec references
SPEC §4 CORE-05; DESIGN B5; TESTING §8 E2E-CORE-3

## Scope
In: Household settings (B5): name field, the three module switches, currency picker, leave and delete.
Out: The tab shell that hides a tab is X7. Invites (CORE-03).

## Acceptance criteria
- [ ] AC1 Given I am an owner and online, when I open Household settings, then I see the name field, three switches, and the currency picker in the owner state.
- [ ] AC2 Given I am a member and not an owner, when I open settings, then the fields are read-only.
- [ ] AC3 Given I am an owner, when I turn pantry off, then PATCH succeeds and the pantry tab disappears without a reload.
- [ ] AC4 Given the device is offline, when I open settings, then the switches are disabled and the OfflineBanner says to connect to change household settings.
- [ ] AC5 Given I am a member, when I tap Leave and confirm, then I leave and land on Welcome or the switcher.

## Design
Screen: DESIGN B5 — States: owner, member, offline, saving. Interactions: toggle module → PATCH → tabs update; leave → confirm sheet → B2 or switcher.
Deviations from the plan: none

## Offline behaviour
Settings are readable offline. Changing the name, flags, currency, leave, or delete needs a network (SPEC §6.5). DESIGN B5 matches that: offline is read-only.

## Module-off behaviour
The settings screen itself stays, because core is not a module. Turning a module off hides that module's tab (X7) and leaves the others.

## Data and contract
Tables: household  Endpoints: PATCH /households/{id}/modules  Types: Household
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/core/src/test/java/app/abodenestly/core/HouseholdSettingsTest.kt → AC3 a successful toggle updates the local flags used by the tab bar
Component: apps/android/core/src/test/java/app/abodenestly/core/HouseholdSettingsComposeTest.kt → AC1 owner, AC2 member read-only, AC3 saving, AC4 offline; toggle and leave. Also asserts AC5
Integration: n/a (no API handler in this story)
E2E: E2E-CORE-3 apps/android/core/src/androidTest/java/app/abodenestly/core/HouseholdSettingsE2ETest.kt
Offline test: n/a (core)

## Dependencies
Blocked by: CORE-05-db, CORE-05-api  Blocks: CORE-09-android, CORE-10-android, CORE-12-android, X7-android

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
DESIGN B5 includes a currency picker and delete-household, and §4 CORE-05 only names the module toggles. Proposed default: both controls ship on this screen because they are part of B5; delete requires typing the household name and is owner-only.

# CORE-06-db — Profile updates and avatar storage

## Summary
A person can change the name and picture the household sees, and other members can see that picture.

## Spec references
SPEC §4 CORE-06; §3 Storage; §11 profile policies; DESIGN B7; TESTING §3 -db row

## Scope
In: profile update allowed only for the row's own user. Storage policy for avatars. Household peers can still select the profile.
Out: The profile screen (CORE-06 clients). Household-scoped attachments (CORE-11). Skipped layers: -api — no profile endpoint in §7; name and avatar use the profile row and Storage. Signed household uploads are CORE-11.

## Acceptance criteria
- [ ] AC1 Given my profile, when I update display_name, then the row stores the new name.
- [ ] AC2 Given someone else's profile, when I update it, then the update is rejected.
- [ ] AC3 Given I share a household with them, when I select their profile, then I can read display_name and avatar_path.

## Design
Screen: n/a — this layer has no DESIGN screen.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 says profile edits are writable offline. The policy still applies when the queued update is synced.

## Module-off behaviour
Core is not gated. Nothing on this screen errors when shopping, pantry, or maintenance is off.

## Data and contract
Tables: profile  Endpoints: none  Types: Profile
Schema change: yes — supabase/migrations/20261108100500_CORE-06_profile_avatar.sql  Contract change: no

## Tests required
Unit: n/a (database behaviour is asserted in pgTAP, TESTING §2)
Component: n/a (no UI)
Integration: supabase/tests/CORE-06_profile.sql → AC1 self update; AC2 other user rejected; AC3 peer can read
E2E: n/a (covered by the client journeys for this ID)
Offline test: n/a (server-side)

## Dependencies
Blocked by: CORE-01-db  Blocks: CORE-06-web, CORE-06-ios, CORE-06-android

## Skill
.claude/skills/sql-supabase/SKILL.md

## Open questions
§3's Storage path is households/{household_id}/… and a profile has no household. Proposed default: avatars live at profiles/{user_id}/{filename} under a policy that lets the user write their own folder and household peers read it.

# CORE-06-web — Profile name and avatar

## Summary
A person can set the name and picture the household sees, and can sign out.

## Spec references
SPEC §4 CORE-06; §6.5 profile edits; DESIGN B7; TESTING §3 client rows

## Scope
In: Profile screen (B7): avatar, display name, read-only email, link to notification preferences, sign out.
Out: Notification preference switches (CORE-12). Sign-in (CORE-01). The avatar storage policy (CORE-06-db). Skipped layers: -api — no profile endpoint in §7; name and avatar use the profile row and Storage. Signed household uploads are CORE-11.

## Acceptance criteria
- [ ] AC1 Given I open Profile, when it loads, then I see my avatar, display name, and read-only email in the idle state.
- [ ] AC2 Given I change my display name, when I leave the field, then the new name is in the local profile and an outbox upsert exists.
- [ ] AC3 Given I tap the avatar and pick a photo, when the upload is in flight, then the uploading-avatar state is shown.
- [ ] AC4 Given the device is offline, when I edit my name, then the edit is kept and queued, and the avatar upload is queued rather than failing the screen.
- [ ] AC5 Given I tap sign out, when it completes, then the session is gone and I am on the sign-in screen.

## Design
Screen: DESIGN B7 — States: idle, uploading-avatar, offline. Interactions: tap avatar to change; edit display name; sign out.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5: profile edits are writable offline. DESIGN B7: the name goes through the outbox, and the avatar upload is queued until the device is online. The sync transport that drains the outbox is SHOP-08.

## Module-off behaviour
Core is not gated. Nothing on this screen errors when shopping, pantry, or maintenance is off.

## Data and contract
Tables: profile  Endpoints: none (Storage upload to the path from CORE-06-db)  Types: Profile
Schema change: no  Contract change: no

## Tests required
Unit: web/src/core/Profile.test.ts → AC2 name edit appends one outbox upsert and does not call the network
Component: web/src/core/Profile.test.tsx → AC1 idle, AC3 uploading-avatar, AC4 offline; avatar tap, name edit, sign out. Also asserts AC5
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: n/a (core; the outbox assertion is the unit test for AC2, not the shopping offline pattern)

## Dependencies
Blocked by: CORE-06-db, CORE-01-web  Blocks: none

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
Email is shown on B7 and is not a column on profile in §8. Proposed default: the email is read from the Supabase Auth user, not stored on profile.

# CORE-06-ios — Profile name and avatar

## Summary
A person can set the name and picture the household sees, and can sign out.

## Spec references
SPEC §4 CORE-06; §6.5 profile edits; DESIGN B7; TESTING §3 client rows

## Scope
In: Profile screen (B7): avatar, display name, read-only email, link to notification preferences, sign out.
Out: Notification preference switches (CORE-12). Sign-in (CORE-01). The avatar storage policy (CORE-06-db). Skipped layers: -api — no profile endpoint in §7; name and avatar use the profile row and Storage. Signed household uploads are CORE-11.

## Acceptance criteria
- [ ] AC1 Given I open Profile, when it loads, then I see my avatar, display name, and read-only email in the idle state.
- [ ] AC2 Given I change my display name, when I leave the field, then the new name is in the local profile and an outbox upsert exists.
- [ ] AC3 Given I tap the avatar and pick a photo, when the upload is in flight, then the uploading-avatar state is shown.
- [ ] AC4 Given the device is offline, when I edit my name, then the edit is kept and queued, and the avatar upload is queued rather than failing the screen.
- [ ] AC5 Given I tap sign out, when it completes, then the session is gone and I am on the sign-in screen.

## Design
Screen: DESIGN B7 — States: idle, uploading-avatar, offline. Interactions: tap avatar to change; edit display name; sign out.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5: profile edits are writable offline. DESIGN B7: the name goes through the outbox, and the avatar upload is queued until the device is online. The sync transport that drains the outbox is SHOP-08.

## Module-off behaviour
Core is not gated. Nothing on this screen errors when shopping, pantry, or maintenance is off.

## Data and contract
Tables: profile  Endpoints: none (Storage upload to the path from CORE-06-db)  Types: Profile
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/core/Tests/ProfileTests.swift → AC2 name edit appends one outbox upsert and does not call the network
Component: apps/ios/AbodeNestlyTests/ProfileViewTests.swift → AC1 idle, AC3 uploading-avatar, AC4 offline; avatar tap, name edit, sign out. Also asserts AC5
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: n/a (core; the outbox assertion is the unit test for AC2, not the shopping offline pattern)

## Dependencies
Blocked by: CORE-06-db, CORE-01-ios  Blocks: none

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
Email is shown on B7 and is not a column on profile in §8. Proposed default: the email is read from the Supabase Auth user, not stored on profile.

# CORE-06-android — Profile name and avatar

## Summary
A person can set the name and picture the household sees, and can sign out.

## Spec references
SPEC §4 CORE-06; §6.5 profile edits; DESIGN B7; TESTING §3 client rows

## Scope
In: Profile screen (B7): avatar, display name, read-only email, link to notification preferences, sign out.
Out: Notification preference switches (CORE-12). Sign-in (CORE-01). The avatar storage policy (CORE-06-db). Skipped layers: -api — no profile endpoint in §7; name and avatar use the profile row and Storage. Signed household uploads are CORE-11.

## Acceptance criteria
- [ ] AC1 Given I open Profile, when it loads, then I see my avatar, display name, and read-only email in the idle state.
- [ ] AC2 Given I change my display name, when I leave the field, then the new name is in the local profile and an outbox upsert exists.
- [ ] AC3 Given I tap the avatar and pick a photo, when the upload is in flight, then the uploading-avatar state is shown.
- [ ] AC4 Given the device is offline, when I edit my name, then the edit is kept and queued, and the avatar upload is queued rather than failing the screen.
- [ ] AC5 Given I tap sign out, when it completes, then the session is gone and I am on the sign-in screen.

## Design
Screen: DESIGN B7 — States: idle, uploading-avatar, offline. Interactions: tap avatar to change; edit display name; sign out.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5: profile edits are writable offline. DESIGN B7: the name goes through the outbox, and the avatar upload is queued until the device is online. The sync transport that drains the outbox is SHOP-08.

## Module-off behaviour
Core is not gated. Nothing on this screen errors when shopping, pantry, or maintenance is off.

## Data and contract
Tables: profile  Endpoints: none (Storage upload to the path from CORE-06-db)  Types: Profile
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/core/src/test/java/app/abodenestly/core/ProfileTest.kt → AC2 name edit appends one outbox upsert and does not call the network
Component: apps/android/core/src/test/java/app/abodenestly/core/ProfileComposeTest.kt → AC1 idle, AC3 uploading-avatar, AC4 offline; avatar tap, name edit, sign out. Also asserts AC5
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: n/a (core; the outbox assertion is the unit test for AC2, not the shopping offline pattern)

## Dependencies
Blocked by: CORE-06-db, CORE-01-android  Blocks: none

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
Email is shown on B7 and is not a column on profile in §8. Proposed default: the email is read from the Supabase Auth user, not stored on profile.

# CORE-07-web — Household switcher

## Summary
A person who belongs to more than one household can switch, and each household's data stays on its own local database.

## Spec references
SPEC §4 CORE-07; §6.1 one local database and cursor per household; DESIGN B3; TESTING §8 E2E-CORE-2

## Scope
In: The switcher sheet (B3): households with name, role, and pending-sync count; the current one checked; Create or join another.
Out: Creating a household (CORE-02). The local sync engine that increments the pending count is SHOP-08; this story stores the cursor and opens the database. Skipped layers: -db, -api — no new table and no endpoint; each household is a local database plus the memberships CORE-02 and CORE-03 already create.

## Acceptance criteria
- [ ] AC1 Given I belong to one household, when I tap the TopBar title, then the sheet still opens in the one-household state and offers create or join.
- [ ] AC2 Given I belong to two households, when I tap the other one, then the current database closes, the target database opens, and I land on the same tab.
- [ ] AC3 Given the target database is opening, when the tap has been handled, then the switching state is shown until it is ready.
- [ ] AC4 Given both databases have different lists, when I switch back, then I see only the lists of the household I switched to.
- [ ] AC5 Given the device is offline, when I switch, then the switch still completes from the local databases.

## Design
Screen: DESIGN B3 — States: one-household, many, switching. Interactions: tap switches database and keeps the tab; long-press opens B5.
Deviations from the plan: none

## Offline behaviour
Switching works offline (DESIGN B3, SPEC §6.1). Creating or joining another household from the sheet needs a network and is CORE-02 and CORE-03.

## Module-off behaviour
Core is not gated. Nothing on this screen errors when shopping, pantry, or maintenance is off.

## Data and contract
Tables: none new (local database per household_id, cursor in that database)  Endpoints: none  Types: Household
Schema change: no  Contract change: no

## Tests required
Unit: web/src/core/Switcher.test.ts → AC2 and AC4 the open database name changes and a query does not see the other household's rows
Component: web/src/core/Switcher.test.tsx → AC1 one-household, AC2 many, AC3 switching; tap and long-press. Also asserts AC5
Integration: n/a (no API handler in this story)
E2E: E2E-CORE-2 web/e2e/Switcher.spec.ts
Offline test: n/a (core)

## Dependencies
Blocked by: CORE-02-web, CORE-03-web  Blocks: CORE-08-web, CORE-09-web

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
§6.4's pull loop is specified under sync, and the feature that names queued sync is SHOP-08. Proposed default: this story creates the per-household database and stores the cursor; SHOP-08 moves the cursor.

# CORE-07-ios — Household switcher

## Summary
A person who belongs to more than one household can switch, and each household's data stays on its own local database.

## Spec references
SPEC §4 CORE-07; §6.1 one local database and cursor per household; DESIGN B3; TESTING §8 E2E-CORE-2

## Scope
In: The switcher sheet (B3): households with name, role, and pending-sync count; the current one checked; Create or join another.
Out: Creating a household (CORE-02). The local sync engine that increments the pending count is SHOP-08; this story stores the cursor and opens the database. Skipped layers: -db, -api — no new table and no endpoint; each household is a local database plus the memberships CORE-02 and CORE-03 already create.

## Acceptance criteria
- [ ] AC1 Given I belong to one household, when I tap the TopBar title, then the sheet still opens in the one-household state and offers create or join.
- [ ] AC2 Given I belong to two households, when I tap the other one, then the current database closes, the target database opens, and I land on the same tab.
- [ ] AC3 Given the target database is opening, when the tap has been handled, then the switching state is shown until it is ready.
- [ ] AC4 Given both databases have different lists, when I switch back, then I see only the lists of the household I switched to.
- [ ] AC5 Given the device is offline, when I switch, then the switch still completes from the local databases.

## Design
Screen: DESIGN B3 — States: one-household, many, switching. Interactions: tap switches database and keeps the tab; long-press opens B5.
Deviations from the plan: none

## Offline behaviour
Switching works offline (DESIGN B3, SPEC §6.1). Creating or joining another household from the sheet needs a network and is CORE-02 and CORE-03.

## Module-off behaviour
Core is not gated. Nothing on this screen errors when shopping, pantry, or maintenance is off.

## Data and contract
Tables: none new (local database per household_id, cursor in that database)  Endpoints: none  Types: Household
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/core/Tests/SwitcherTests.swift → AC2 and AC4 the open database name changes and a query does not see the other household's rows
Component: apps/ios/AbodeNestlyTests/SwitcherViewTests.swift → AC1 one-household, AC2 many, AC3 switching; tap and long-press. Also asserts AC5
Integration: n/a (no API handler in this story)
E2E: E2E-CORE-2 apps/ios/AbodeNestlyUITests/SwitcherUITests.swift
Offline test: n/a (core)

## Dependencies
Blocked by: CORE-02-ios, CORE-03-ios  Blocks: CORE-08-ios, CORE-09-ios

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
§6.4's pull loop is specified under sync, and the feature that names queued sync is SHOP-08. Proposed default: this story creates the per-household database and stores the cursor; SHOP-08 moves the cursor.

# CORE-07-android — Household switcher

## Summary
A person who belongs to more than one household can switch, and each household's data stays on its own local database.

## Spec references
SPEC §4 CORE-07; §6.1 one local database and cursor per household; DESIGN B3; TESTING §8 E2E-CORE-2

## Scope
In: The switcher sheet (B3): households with name, role, and pending-sync count; the current one checked; Create or join another.
Out: Creating a household (CORE-02). The local sync engine that increments the pending count is SHOP-08; this story stores the cursor and opens the database. Skipped layers: -db, -api — no new table and no endpoint; each household is a local database plus the memberships CORE-02 and CORE-03 already create.

## Acceptance criteria
- [ ] AC1 Given I belong to one household, when I tap the TopBar title, then the sheet still opens in the one-household state and offers create or join.
- [ ] AC2 Given I belong to two households, when I tap the other one, then the current database closes, the target database opens, and I land on the same tab.
- [ ] AC3 Given the target database is opening, when the tap has been handled, then the switching state is shown until it is ready.
- [ ] AC4 Given both databases have different lists, when I switch back, then I see only the lists of the household I switched to.
- [ ] AC5 Given the device is offline, when I switch, then the switch still completes from the local databases.

## Design
Screen: DESIGN B3 — States: one-household, many, switching. Interactions: tap switches database and keeps the tab; long-press opens B5.
Deviations from the plan: none

## Offline behaviour
Switching works offline (DESIGN B3, SPEC §6.1). Creating or joining another household from the sheet needs a network and is CORE-02 and CORE-03.

## Module-off behaviour
Core is not gated. Nothing on this screen errors when shopping, pantry, or maintenance is off.

## Data and contract
Tables: none new (local database per household_id, cursor in that database)  Endpoints: none  Types: Household
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/core/src/test/java/app/abodenestly/core/SwitcherTest.kt → AC2 and AC4 the open database name changes and a query does not see the other household's rows
Component: apps/android/core/src/test/java/app/abodenestly/core/SwitcherComposeTest.kt → AC1 one-household, AC2 many, AC3 switching; tap and long-press. Also asserts AC5
Integration: n/a (no API handler in this story)
E2E: E2E-CORE-2 apps/android/core/src/androidTest/java/app/abodenestly/core/SwitcherE2ETest.kt
Offline test: n/a (core)

## Dependencies
Blocked by: CORE-02-android, CORE-03-android  Blocks: CORE-08-android, CORE-09-android

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
§6.4's pull loop is specified under sync, and the feature that names queued sync is SHOP-08. Proposed default: this story creates the per-household database and stores the cursor; SHOP-08 moves the cursor.

# CORE-08-db — Activity rows for the household

## Summary
The household can keep a sentence for something a member just did, scoped to that household and readable by its members.

## Spec references
SPEC §4 CORE-08; §8 Activity; §9 X6; TESTING §3 -db row

## Scope
In: activity table with the standard sync columns, RLS so only members read it, and an index for the last 30 days.
Out: Writing the sentences (X6-api). Showing them (CORE-08 clients). Skipped layers: -api — rows are written by X6-api. This feature is the table and the feed on Home.

## Acceptance criteria
- [ ] AC1 Given a member, when they insert an activity row for their household, then it is stored with actor, summary, and module.
- [ ] AC2 Given a user who is not a member, when they select that activity, then they see nothing.
- [ ] AC3 Given a row older than 30 days, when it is still in the table, then a query for the feed window excludes it. The screen applies the same window.

## Design
Screen: n/a — this layer has no DESIGN screen.
Deviations from the plan: none

## Offline behaviour
Activity is readable offline after it has been pulled (SPEC §6.5). Members do not write activity themselves.

## Module-off behaviour
A module that is off does not produce new rows, because X6 only writes for actions X7 allowed. Old rows stay in the table.

## Data and contract
Tables: activity  Endpoints: none  Types: Activity
Schema change: yes — supabase/migrations/20261108100600_CORE-08_activity.sql  Contract change: no

## Tests required
Unit: n/a (database behaviour is asserted in pgTAP, TESTING §2)
Component: n/a (no UI)
Integration: supabase/tests/CORE-08_activity.sql → AC1 member insert; AC2 non-member denied; AC3 30-day filter
E2E: n/a (covered by the client journeys for this ID)
Offline test: n/a (server-side)

## Dependencies
Blocked by: CORE-02-db  Blocks: CORE-08-web, CORE-08-ios, CORE-08-android, X6-api

## Skill
.claude/skills/sql-supabase/SKILL.md

## Open questions
§4 says the feed shows the last 30 days. DESIGN B4 says the last 20 rows. Proposed default: the query is the last 30 days, then the screen shows at most 20 of those.

# CORE-08-web — Activity feed on Home

## Summary
A person can open Home and read what the household just did, in sentences such as Anna checked off Milk.

## Spec references
SPEC §4 CORE-08; DESIGN B4; TESTING §3 client rows

## Scope
In: The activity section of Home (B4), including the empty state and pull to refresh.
Out: The Today cards' module screens. Writing activity (X6). The switcher (CORE-07). Skipped layers: -api — rows are written by X6-api. This feature is the table and the feed on Home.

## Acceptance criteria
- [ ] AC1 Given the household has no activity, when I open Home, then I see the empty-activity state.
- [ ] AC2 Given activity exists, when I open Home, then I see sentences of the form Anna checked off Milk, newest first, at most 20 rows from the last 30 days.
- [ ] AC3 Given every module is off, when I open Home, then I see the all-modules-off state and the settings prompt, and the feed still lists core events such as a member joining.
- [ ] AC4 Given I pull to refresh, when the gesture ends, then kick() runs.
- [ ] AC5 Given the device is offline, when I open Home, then the cached feed is shown.

## Design
Screen: DESIGN B4 — States: empty-activity, populated, all-modules-off. Interactions: tap a Today count opens that module view; pull to refresh calls kick().
Deviations from the plan: none

## Offline behaviour
The feed is readable offline from the local database (SPEC §6.5). New sentences appear after a pull. Pull to refresh needs a network and is a no-op that keeps the cached rows when offline.

## Module-off behaviour
Today cards for a module are hidden when that module is off (DESIGN B4). The feed itself stays.

## Data and contract
Tables: activity, profile  Endpoints: none (rows arrive through sync)  Types: Activity, Profile
Schema change: no  Contract change: no

## Tests required
Unit: web/src/core/HomeFeed.test.ts → AC2 the window drops rows older than 30 days and caps at 20
Component: web/src/core/HomeFeed.test.tsx → AC1 empty-activity, AC2 populated, AC3 all-modules-off; pull to refresh calls kick. Also asserts AC4, AC5
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: n/a (core)

## Dependencies
Blocked by: CORE-08-db, CORE-07-web  Blocks: none

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
none

# CORE-08-ios — Activity feed on Home

## Summary
A person can open Home and read what the household just did, in sentences such as Anna checked off Milk.

## Spec references
SPEC §4 CORE-08; DESIGN B4; TESTING §3 client rows

## Scope
In: The activity section of Home (B4), including the empty state and pull to refresh.
Out: The Today cards' module screens. Writing activity (X6). The switcher (CORE-07). Skipped layers: -api — rows are written by X6-api. This feature is the table and the feed on Home.

## Acceptance criteria
- [ ] AC1 Given the household has no activity, when I open Home, then I see the empty-activity state.
- [ ] AC2 Given activity exists, when I open Home, then I see sentences of the form Anna checked off Milk, newest first, at most 20 rows from the last 30 days.
- [ ] AC3 Given every module is off, when I open Home, then I see the all-modules-off state and the settings prompt, and the feed still lists core events such as a member joining.
- [ ] AC4 Given I pull to refresh, when the gesture ends, then kick() runs.
- [ ] AC5 Given the device is offline, when I open Home, then the cached feed is shown.

## Design
Screen: DESIGN B4 — States: empty-activity, populated, all-modules-off. Interactions: tap a Today count opens that module view; pull to refresh calls kick().
Deviations from the plan: none

## Offline behaviour
The feed is readable offline from the local database (SPEC §6.5). New sentences appear after a pull. Pull to refresh needs a network and is a no-op that keeps the cached rows when offline.

## Module-off behaviour
Today cards for a module are hidden when that module is off (DESIGN B4). The feed itself stays.

## Data and contract
Tables: activity, profile  Endpoints: none (rows arrive through sync)  Types: Activity, Profile
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/core/Tests/HomeFeedTests.swift → AC2 the window drops rows older than 30 days and caps at 20
Component: apps/ios/AbodeNestlyTests/HomeFeedViewTests.swift → AC1 empty-activity, AC2 populated, AC3 all-modules-off; pull to refresh calls kick. Also asserts AC4, AC5
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: n/a (core)

## Dependencies
Blocked by: CORE-08-db, CORE-07-ios  Blocks: none

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
none

# CORE-08-android — Activity feed on Home

## Summary
A person can open Home and read what the household just did, in sentences such as Anna checked off Milk.

## Spec references
SPEC §4 CORE-08; DESIGN B4; TESTING §3 client rows

## Scope
In: The activity section of Home (B4), including the empty state and pull to refresh.
Out: The Today cards' module screens. Writing activity (X6). The switcher (CORE-07). Skipped layers: -api — rows are written by X6-api. This feature is the table and the feed on Home.

## Acceptance criteria
- [ ] AC1 Given the household has no activity, when I open Home, then I see the empty-activity state.
- [ ] AC2 Given activity exists, when I open Home, then I see sentences of the form Anna checked off Milk, newest first, at most 20 rows from the last 30 days.
- [ ] AC3 Given every module is off, when I open Home, then I see the all-modules-off state and the settings prompt, and the feed still lists core events such as a member joining.
- [ ] AC4 Given I pull to refresh, when the gesture ends, then kick() runs.
- [ ] AC5 Given the device is offline, when I open Home, then the cached feed is shown.

## Design
Screen: DESIGN B4 — States: empty-activity, populated, all-modules-off. Interactions: tap a Today count opens that module view; pull to refresh calls kick().
Deviations from the plan: none

## Offline behaviour
The feed is readable offline from the local database (SPEC §6.5). New sentences appear after a pull. Pull to refresh needs a network and is a no-op that keeps the cached rows when offline.

## Module-off behaviour
Today cards for a module are hidden when that module is off (DESIGN B4). The feed itself stays.

## Data and contract
Tables: activity, profile  Endpoints: none (rows arrive through sync)  Types: Activity, Profile
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/core/src/test/java/app/abodenestly/core/HomeFeedTest.kt → AC2 the window drops rows older than 30 days and caps at 20
Component: apps/android/core/src/test/java/app/abodenestly/core/HomeFeedComposeTest.kt → AC1 empty-activity, AC2 populated, AC3 all-modules-off; pull to refresh calls kick. Also asserts AC4, AC5
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: n/a (core)

## Dependencies
Blocked by: CORE-08-db, CORE-07-android  Blocks: none

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
none

# CORE-09-web — Household-wide search

## Summary
A person can search the household by name and only the modules that are turned on are included.

## Spec references
SPEC §4 CORE-09; DESIGN B8; TESTING §3 client rows

## Scope
In: Search (B8): debounced field, results grouped by module, each row opens the entity.
Out: The entity screens the rows open. Indexing a module's rows is that module's list story. Skipped layers: -db, -api — search is local-only over names already synced; §4 specifies no endpoint and no new table.

## Acceptance criteria
- [ ] AC1 Given the query is empty, when I open Search, then I see recent searches in the empty-query state.
- [ ] AC2 Given I type a name that matches nothing, when the debounce of 150 ms has passed, then I see the no-results state.
- [ ] AC3 Given the name matches a list item and a pantry item and both modules are on, when results return, then they are grouped under Shopping and Pantry.
- [ ] AC4 Given pantry is off, when I search, then no Pantry section is shown, even if the local database still has pantry rows.
- [ ] AC5 Given the device is offline, when I search, then results still come from the local database.

## Design
Screen: DESIGN B8 — States: empty-query, no-results, results. Interactions: a result row navigates to the entity.
Deviations from the plan: none

## Offline behaviour
SPEC §4 says the search is the local database only, so it works offline. DESIGN B8 agrees.

## Module-off behaviour
Sections for a disabled module are omitted (DESIGN B8, X7).

## Data and contract
Tables: local copies of list_item, pantry_item, recipe, asset, maint_task, store, location, contractor  Endpoints: none  Types: none new
Schema change: no  Contract change: no

## Tests required
Unit: web/src/core/Search.test.ts → AC3 and AC4 grouping keeps only enabled modules
Component: web/src/core/Search.test.tsx → AC1 empty-query, AC2 no-results, AC3 results; tapping a row navigates. Also asserts AC5
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: n/a (core)

## Dependencies
Blocked by: CORE-05-web, CORE-07-web  Blocks: none

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
§4 says the search covers names and does not list the columns. Proposed default: list item, pantry item, recipe, asset, task title, store, location, and contractor names, which are the named records in §5.

# CORE-09-ios — Household-wide search

## Summary
A person can search the household by name and only the modules that are turned on are included.

## Spec references
SPEC §4 CORE-09; DESIGN B8; TESTING §3 client rows

## Scope
In: Search (B8): debounced field, results grouped by module, each row opens the entity.
Out: The entity screens the rows open. Indexing a module's rows is that module's list story. Skipped layers: -db, -api — search is local-only over names already synced; §4 specifies no endpoint and no new table.

## Acceptance criteria
- [ ] AC1 Given the query is empty, when I open Search, then I see recent searches in the empty-query state.
- [ ] AC2 Given I type a name that matches nothing, when the debounce of 150 ms has passed, then I see the no-results state.
- [ ] AC3 Given the name matches a list item and a pantry item and both modules are on, when results return, then they are grouped under Shopping and Pantry.
- [ ] AC4 Given pantry is off, when I search, then no Pantry section is shown, even if the local database still has pantry rows.
- [ ] AC5 Given the device is offline, when I search, then results still come from the local database.

## Design
Screen: DESIGN B8 — States: empty-query, no-results, results. Interactions: a result row navigates to the entity.
Deviations from the plan: none

## Offline behaviour
SPEC §4 says the search is the local database only, so it works offline. DESIGN B8 agrees.

## Module-off behaviour
Sections for a disabled module are omitted (DESIGN B8, X7).

## Data and contract
Tables: local copies of list_item, pantry_item, recipe, asset, maint_task, store, location, contractor  Endpoints: none  Types: none new
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/core/Tests/SearchTests.swift → AC3 and AC4 grouping keeps only enabled modules
Component: apps/ios/AbodeNestlyTests/SearchViewTests.swift → AC1 empty-query, AC2 no-results, AC3 results; tapping a row navigates. Also asserts AC5
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: n/a (core)

## Dependencies
Blocked by: CORE-05-ios, CORE-07-ios  Blocks: none

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
§4 says the search covers names and does not list the columns. Proposed default: list item, pantry item, recipe, asset, task title, store, location, and contractor names, which are the named records in §5.

# CORE-09-android — Household-wide search

## Summary
A person can search the household by name and only the modules that are turned on are included.

## Spec references
SPEC §4 CORE-09; DESIGN B8; TESTING §3 client rows

## Scope
In: Search (B8): debounced field, results grouped by module, each row opens the entity.
Out: The entity screens the rows open. Indexing a module's rows is that module's list story. Skipped layers: -db, -api — search is local-only over names already synced; §4 specifies no endpoint and no new table.

## Acceptance criteria
- [ ] AC1 Given the query is empty, when I open Search, then I see recent searches in the empty-query state.
- [ ] AC2 Given I type a name that matches nothing, when the debounce of 150 ms has passed, then I see the no-results state.
- [ ] AC3 Given the name matches a list item and a pantry item and both modules are on, when results return, then they are grouped under Shopping and Pantry.
- [ ] AC4 Given pantry is off, when I search, then no Pantry section is shown, even if the local database still has pantry rows.
- [ ] AC5 Given the device is offline, when I search, then results still come from the local database.

## Design
Screen: DESIGN B8 — States: empty-query, no-results, results. Interactions: a result row navigates to the entity.
Deviations from the plan: none

## Offline behaviour
SPEC §4 says the search is the local database only, so it works offline. DESIGN B8 agrees.

## Module-off behaviour
Sections for a disabled module are omitted (DESIGN B8, X7).

## Data and contract
Tables: local copies of list_item, pantry_item, recipe, asset, maint_task, store, location, contractor  Endpoints: none  Types: none new
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/core/src/test/java/app/abodenestly/core/SearchTest.kt → AC3 and AC4 grouping keeps only enabled modules
Component: apps/android/core/src/test/java/app/abodenestly/core/SearchComposeTest.kt → AC1 empty-query, AC2 no-results, AC3 results; tapping a row navigates. Also asserts AC5
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: n/a (core)

## Dependencies
Blocked by: CORE-05-android, CORE-07-android  Blocks: none

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
§4 says the search covers names and does not list the columns. Proposed default: list item, pantry item, recipe, asset, task title, store, location, and contractor names, which are the named records in §5.

# CORE-10-db — Purge soft-deleted rows after 30 days

## Summary
Something deleted by mistake can come back for 30 days, and then it is actually gone.

## Spec references
SPEC §4 CORE-10; §5 deleted_at; §14 purge_soft_deleted; TESTING §3 -db row

## Scope
In: The daily purge job hard-deletes rows whose deleted_at is older than 30 days, for the tables §14 lists.
Out: The Recently deleted screen (CORE-10 clients). Soft-deleting a row is the story that owns that row. Skipped layers: -api — purge is pg_cron in the database, not an HTTP route. Restore is a sync upsert.

## Acceptance criteria
- [ ] AC1 Given a list item with deleted_at 31 days ago, when purge_soft_deleted runs, then the row is gone.
- [ ] AC2 Given a list item with deleted_at 29 days ago, when the job runs, then the row remains.
- [ ] AC3 Given the job runs twice, when the second run finishes, then it deletes nothing further and does not error.

## Design
Screen: n/a — this layer has no DESIGN screen.
Deviations from the plan: none

## Offline behaviour
The job runs on the server. A restore before the purge is a client upsert and follows that module's offline rule.

## Module-off behaviour
The job does not look at module flags. A disabled module's already-deleted rows are still purged.

## Data and contract
Tables: the household tables listed in purge_soft_deleted  Endpoints: none  Types: none
Schema change: yes — supabase/migrations/20261108100700_CORE-10_purge.sql  Contract change: no

## Tests required
Unit: n/a (database behaviour is asserted in pgTAP, TESTING §2)
Component: n/a (no UI)
Integration: supabase/tests/CORE-10_purge.sql → AC1 older than 30 days deleted; AC2 newer kept; AC3 second run is a no-op
E2E: n/a (covered by the client journeys for this ID)
Offline test: n/a (server-side)

## Dependencies
Blocked by: none  Blocks: CORE-10-web, CORE-10-ios, CORE-10-android

## Skill
.claude/skills/sql-supabase/SKILL.md

## Open questions
§14's purge list is a fixed set of tables and §4 says a screen per module without naming tables. Proposed default: the §14 list is the complete set the screen can restore.

# CORE-10-web — Recently deleted

## Summary
A person can open Recently deleted for a module and put something back within 30 days.

## Spec references
SPEC §4 CORE-10; §5 deleted_at null means live; DESIGN B11; TESTING §3 client rows

## Scope
In: Recently deleted (B11): a segment per module, Restore, and days left.
Out: Deleting the row in the first place, which belongs to the module story. The purge job (CORE-10-db). Skipped layers: -api — purge is pg_cron in the database, not an HTTP route. Restore is a sync upsert.

## Acceptance criteria
- [ ] AC1 Given nothing is soft-deleted, when I open Recently deleted, then I see the empty state.
- [ ] AC2 Given a list item was deleted 2 days ago, when I open the Shopping segment, then the row shows Restore and 28 days left.
- [ ] AC3 Given I tap Restore, when the tap is handled, then an outbox upsert sets deleted_at to null and the row leaves this screen.
- [ ] AC4 Given the row was purged, when I open the screen, then it is not listed.
- [ ] AC5 Given pantry is off, when I open the screen, then the Pantry segment is hidden and the Shopping segment still works.

## Design
Screen: DESIGN B11 — States: empty, populated. Interactions: Restore.
Deviations from the plan: none

## Offline behaviour
DESIGN B11: restore is an outbox upsert with deleted_at null. Shopping and pantry deletes are offline writes (SPEC §6.5). Household and member changes are not restored here.

## Module-off behaviour
Segments for a disabled module are hidden (DESIGN B11, X7). Restoring a hidden module is not offered.

## Data and contract
Tables: the soft-deleted household rows  Endpoints: none  Types: none new
Schema change: no  Contract change: no

## Tests required
Unit: web/src/core/RecentlyDeleted.test.ts → AC2 days left is 30 minus the age in days; AC3 the outbox payload has deleted_at null
Component: web/src/core/RecentlyDeleted.test.tsx → AC1 empty, AC2 populated; Restore tap. Also asserts AC4, AC5
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: n/a (core; shopping restores still queue an outbox row, asserted in the unit test)

## Dependencies
Blocked by: CORE-10-db, CORE-05-web  Blocks: none

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
none

# CORE-10-ios — Recently deleted

## Summary
A person can open Recently deleted for a module and put something back within 30 days.

## Spec references
SPEC §4 CORE-10; §5 deleted_at null means live; DESIGN B11; TESTING §3 client rows

## Scope
In: Recently deleted (B11): a segment per module, Restore, and days left.
Out: Deleting the row in the first place, which belongs to the module story. The purge job (CORE-10-db). Skipped layers: -api — purge is pg_cron in the database, not an HTTP route. Restore is a sync upsert.

## Acceptance criteria
- [ ] AC1 Given nothing is soft-deleted, when I open Recently deleted, then I see the empty state.
- [ ] AC2 Given a list item was deleted 2 days ago, when I open the Shopping segment, then the row shows Restore and 28 days left.
- [ ] AC3 Given I tap Restore, when the tap is handled, then an outbox upsert sets deleted_at to null and the row leaves this screen.
- [ ] AC4 Given the row was purged, when I open the screen, then it is not listed.
- [ ] AC5 Given pantry is off, when I open the screen, then the Pantry segment is hidden and the Shopping segment still works.

## Design
Screen: DESIGN B11 — States: empty, populated. Interactions: Restore.
Deviations from the plan: none

## Offline behaviour
DESIGN B11: restore is an outbox upsert with deleted_at null. Shopping and pantry deletes are offline writes (SPEC §6.5). Household and member changes are not restored here.

## Module-off behaviour
Segments for a disabled module are hidden (DESIGN B11, X7). Restoring a hidden module is not offered.

## Data and contract
Tables: the soft-deleted household rows  Endpoints: none  Types: none new
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/core/Tests/RecentlyDeletedTests.swift → AC2 days left is 30 minus the age in days; AC3 the outbox payload has deleted_at null
Component: apps/ios/AbodeNestlyTests/RecentlyDeletedViewTests.swift → AC1 empty, AC2 populated; Restore tap. Also asserts AC4, AC5
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: n/a (core; shopping restores still queue an outbox row, asserted in the unit test)

## Dependencies
Blocked by: CORE-10-db, CORE-05-ios  Blocks: none

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
none

# CORE-10-android — Recently deleted

## Summary
A person can open Recently deleted for a module and put something back within 30 days.

## Spec references
SPEC §4 CORE-10; §5 deleted_at null means live; DESIGN B11; TESTING §3 client rows

## Scope
In: Recently deleted (B11): a segment per module, Restore, and days left.
Out: Deleting the row in the first place, which belongs to the module story. The purge job (CORE-10-db). Skipped layers: -api — purge is pg_cron in the database, not an HTTP route. Restore is a sync upsert.

## Acceptance criteria
- [ ] AC1 Given nothing is soft-deleted, when I open Recently deleted, then I see the empty state.
- [ ] AC2 Given a list item was deleted 2 days ago, when I open the Shopping segment, then the row shows Restore and 28 days left.
- [ ] AC3 Given I tap Restore, when the tap is handled, then an outbox upsert sets deleted_at to null and the row leaves this screen.
- [ ] AC4 Given the row was purged, when I open the screen, then it is not listed.
- [ ] AC5 Given pantry is off, when I open the screen, then the Pantry segment is hidden and the Shopping segment still works.

## Design
Screen: DESIGN B11 — States: empty, populated. Interactions: Restore.
Deviations from the plan: none

## Offline behaviour
DESIGN B11: restore is an outbox upsert with deleted_at null. Shopping and pantry deletes are offline writes (SPEC §6.5). Household and member changes are not restored here.

## Module-off behaviour
Segments for a disabled module are hidden (DESIGN B11, X7). Restoring a hidden module is not offered.

## Data and contract
Tables: the soft-deleted household rows  Endpoints: none  Types: none new
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/core/src/test/java/app/abodenestly/core/RecentlyDeletedTest.kt → AC2 days left is 30 minus the age in days; AC3 the outbox payload has deleted_at null
Component: apps/android/core/src/test/java/app/abodenestly/core/RecentlyDeletedComposeTest.kt → AC1 empty, AC2 populated; Restore tap. Also asserts AC4, AC5
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: n/a (core; shopping restores still queue an outbox row, asserted in the unit test)

## Dependencies
Blocked by: CORE-10-db, CORE-05-android  Blocks: none

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
none

# CORE-11-db — Attachment rows and storage policies

## Summary
A photo or a PDF stays tied to the pantry item, asset, or log entry it belongs to, and only that household can read it.

## Spec references
SPEC §4 CORE-11; §3 Storage path; §11 storage policies; TESTING §3 -db row

## Scope
In: attachment table and storage policies that mirror household membership for the households/{household_id}/… prefix.
Out: The signed URL handler (CORE-11-api). The upload control (CORE-11 clients).

## Acceptance criteria
- [ ] AC1 Given a member, when they insert an attachment for their household, then the row is stored with entity, entity_id, mime, and size.
- [ ] AC2 Given a non-member, when they select that attachment, then they see nothing.
- [ ] AC3 Given a storage object outside the household prefix, when a member tries to read it, then the storage policy denies it.

## Design
Screen: n/a — this layer has no DESIGN screen.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5: maintenance attachment bytes queue until online. The row can be written when the signed upload finishes.

## Module-off behaviour
An attachment whose entity belongs to a disabled module is not shown, because that module's screen is hidden (X7).

## Data and contract
Tables: attachment  Endpoints: none  Types: Attachment
Schema change: yes — supabase/migrations/20261108100800_CORE-11_attachment.sql  Contract change: no

## Tests required
Unit: n/a (database behaviour is asserted in pgTAP, TESTING §2)
Component: n/a (no UI)
Integration: supabase/tests/CORE-11_attachment.sql → AC1 member insert; AC2 non-member denied; AC3 storage prefix denied
E2E: n/a (covered by the client journeys for this ID)
Offline test: n/a (server-side)

## Dependencies
Blocked by: CORE-02-db  Blocks: CORE-11-api, CORE-11-web, CORE-11-ios, CORE-11-android

## Skill
.claude/skills/sql-supabase/SKILL.md

## Open questions
none

# CORE-11-api — Sign an attachment upload

## Summary
The app can upload a photo or a PDF with a signed URL instead of an open bucket.

## Spec references
SPEC §4 CORE-11; §7 POST /attachments/sign; TESTING §3 -api row

## Scope
In: POST /attachments/sign returns attachment_id, upload_url, and storage_path for a photo or PDF on a pantry item, asset, or log entry.
Out: The table and policies (CORE-11-db). The picker UI (CORE-11 clients).

## Acceptance criteria
- [ ] AC1 Given a member and a pantry item in their household, when they ask to sign a JPEG, then the response includes an upload URL and a path under households/{household_id}/pantry_item/{id}/.
- [ ] AC2 Given no JWT, when they call the route, then the response is 401.
- [ ] AC3 Given a mime type that is not a photo or PDF, when they call the route, then the response is 400 and no attachment row is inserted.
- [ ] AC4 Given the entity is in another household, when they call the route, then the response is 403.

## Design
Screen: n/a — this layer has no DESIGN screen.
Deviations from the plan: none

## Offline behaviour
Signing needs a network. SPEC §6.5 says maintenance attachment bytes queue until online, so the client calls this when the device is online.

## Module-off behaviour
Signing for an entity whose module is off is rejected with module_disabled.

## Data and contract
Tables: attachment  Endpoints: POST /attachments/sign  Types: Attachment
Schema change: no  Contract change: yes

## Tests required
Unit: api/test/CORE-11-sign.test.ts → AC3 bad mime is 400
Component: n/a (no UI)
Integration: api/test/CORE-11-sign.int.test.ts → AC1 path shape; AC2 401; AC4 other household 403. Contract check covers the route.
E2E: n/a (client journeys cover the user path)
Offline test: n/a (server-side; queued client writes are covered by the client offline test)

## Dependencies
Blocked by: CORE-11-db  Blocks: CORE-11-web, CORE-11-ios, CORE-11-android

## Skill
.claude/skills/node-backend/SKILL.md

## Open questions
§6.5 says every pantry write works offline, and also says maintenance attachments queue until online. Proposed default: the bytes for every attachment, including pantry photos, queue until online, and only the attachment row's metadata is an offline upsert.

# CORE-11-web — Attach a photo or PDF

## Summary
A person can attach a photo or a PDF to a pantry item, an asset, or a log entry, and see it on that record.

## Spec references
SPEC §4 CORE-11; DESIGN D2, E4, E9; TESTING §3 client rows

## Scope
In: The shared attachment control those screens host: pick a photo or PDF, upload with the signed URL, show the file.
Out: The rest of the pantry item (PANT-02, PANT-10), the asset (MAINT-02), and the mark-done sheet (MAINT-05).

## Acceptance criteria
- [ ] AC1 Given I am editing a pantry item, when I add a photo, then a signed URL is requested and the file is uploaded.
- [ ] AC2 Given the upload succeeds, when I return to the item, then the photo is shown.
- [ ] AC3 Given I pick a PDF for a log entry, when the upload succeeds, then the log entry lists that PDF.
- [ ] AC4 Given the device is offline, when I pick a file, then the upload is queued and the screen does not error.
- [ ] AC5 Given the sign request fails, when the error returns, then the file is not shown as attached and I can retry.

## Design
Screen: the attachment control hosted by DESIGN D2, E4, and E9 — States: idle, uploading, queued, error. Interactions: pick photo, pick PDF, retry.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 queues maintenance attachment bytes until online. This control uses that queue for every attachment. The host record stays readable offline.

## Module-off behaviour
The control is not offered on a screen whose module is off (X7).

## Data and contract
Tables: attachment  Endpoints: POST /attachments/sign  Types: Attachment
Schema change: no  Contract change: no

## Tests required
Unit: web/src/core/AttachmentPicker.test.ts → AC4 offline pick writes an outbox item and does not call sign
Component: web/src/core/AttachmentPicker.test.tsx → AC1 uploading, AC2 idle with a photo, AC4 queued, AC5 error; pick photo, pick PDF, retry. Also asserts AC3
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: n/a (the queue assertion is AC4's unit test; host screens in shopping and pantry add the TESTING §6 test)

## Dependencies
Blocked by: CORE-11-db, CORE-11-api  Blocks: PANT-10-web, MAINT-02-web, MAINT-05-web

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
none

# CORE-11-ios — Attach a photo or PDF

## Summary
A person can attach a photo or a PDF to a pantry item, an asset, or a log entry, and see it on that record.

## Spec references
SPEC §4 CORE-11; DESIGN D2, E4, E9; TESTING §3 client rows

## Scope
In: The shared attachment control those screens host: pick a photo or PDF, upload with the signed URL, show the file.
Out: The rest of the pantry item (PANT-02, PANT-10), the asset (MAINT-02), and the mark-done sheet (MAINT-05).

## Acceptance criteria
- [ ] AC1 Given I am editing a pantry item, when I add a photo, then a signed URL is requested and the file is uploaded.
- [ ] AC2 Given the upload succeeds, when I return to the item, then the photo is shown.
- [ ] AC3 Given I pick a PDF for a log entry, when the upload succeeds, then the log entry lists that PDF.
- [ ] AC4 Given the device is offline, when I pick a file, then the upload is queued and the screen does not error.
- [ ] AC5 Given the sign request fails, when the error returns, then the file is not shown as attached and I can retry.

## Design
Screen: the attachment control hosted by DESIGN D2, E4, and E9 — States: idle, uploading, queued, error. Interactions: pick photo, pick PDF, retry.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 queues maintenance attachment bytes until online. This control uses that queue for every attachment. The host record stays readable offline.

## Module-off behaviour
The control is not offered on a screen whose module is off (X7).

## Data and contract
Tables: attachment  Endpoints: POST /attachments/sign  Types: Attachment
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/core/Tests/AttachmentPickerTests.swift → AC4 offline pick writes an outbox item and does not call sign
Component: apps/ios/AbodeNestlyTests/AttachmentPickerViewTests.swift → AC1 uploading, AC2 idle with a photo, AC4 queued, AC5 error; pick photo, pick PDF, retry. Also asserts AC3
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: n/a (the queue assertion is AC4's unit test; host screens in shopping and pantry add the TESTING §6 test)

## Dependencies
Blocked by: CORE-11-db, CORE-11-api  Blocks: PANT-10-ios, MAINT-02-ios, MAINT-05-ios

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
none

# CORE-11-android — Attach a photo or PDF

## Summary
A person can attach a photo or a PDF to a pantry item, an asset, or a log entry, and see it on that record.

## Spec references
SPEC §4 CORE-11; DESIGN D2, E4, E9; TESTING §3 client rows

## Scope
In: The shared attachment control those screens host: pick a photo or PDF, upload with the signed URL, show the file.
Out: The rest of the pantry item (PANT-02, PANT-10), the asset (MAINT-02), and the mark-done sheet (MAINT-05).

## Acceptance criteria
- [ ] AC1 Given I am editing a pantry item, when I add a photo, then a signed URL is requested and the file is uploaded.
- [ ] AC2 Given the upload succeeds, when I return to the item, then the photo is shown.
- [ ] AC3 Given I pick a PDF for a log entry, when the upload succeeds, then the log entry lists that PDF.
- [ ] AC4 Given the device is offline, when I pick a file, then the upload is queued and the screen does not error.
- [ ] AC5 Given the sign request fails, when the error returns, then the file is not shown as attached and I can retry.

## Design
Screen: the attachment control hosted by DESIGN D2, E4, and E9 — States: idle, uploading, queued, error. Interactions: pick photo, pick PDF, retry.
Deviations from the plan: none

## Offline behaviour
SPEC §6.5 queues maintenance attachment bytes until online. This control uses that queue for every attachment. The host record stays readable offline.

## Module-off behaviour
The control is not offered on a screen whose module is off (X7).

## Data and contract
Tables: attachment  Endpoints: POST /attachments/sign  Types: Attachment
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/core/src/test/java/app/abodenestly/core/AttachmentPickerTest.kt → AC4 offline pick writes an outbox item and does not call sign
Component: apps/android/core/src/test/java/app/abodenestly/core/AttachmentPickerComposeTest.kt → AC1 uploading, AC2 idle with a photo, AC4 queued, AC5 error; pick photo, pick PDF, retry. Also asserts AC3
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: n/a (the queue assertion is AC4's unit test; host screens in shopping and pantry add the TESTING §6 test)

## Dependencies
Blocked by: CORE-11-db, CORE-11-api  Blocks: PANT-10-android, MAINT-02-android, MAINT-05-android

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
none

# CORE-12-db — Notification preferences

## Summary
Each person can store, per household, which notices they want.

## Spec references
SPEC §4 CORE-12; §8 NotificationPref; §10 respects notification_pref; TESTING §3 -db row

## Scope
In: notification_pref rows for eat_soon, low_stock, task_due, warranty, and activity, defaulting to enabled, writable only by that user.
Out: The switches (CORE-12 clients). The jobs that read the preference (PANT-16, MAINT-08, MAINT-11). Skipped layers: -api — §7 has no preference endpoint. DESIGN B9 writes preferences through the outbox, so they travel with sync.

## Acceptance criteria
- [ ] AC1 Given a member with no row, when a job reads the preference, then the notice is allowed (default enabled).
- [ ] AC2 Given the user sets eat_soon to disabled, when the row is read, then enabled is false.
- [ ] AC3 Given another user, when they update my preference, then the update is rejected.

## Design
Screen: n/a — this layer has no DESIGN screen.
Deviations from the plan: none

## Offline behaviour
DESIGN B9 writes the preference through the outbox. The row is applied when sync runs.

## Module-off behaviour
A preference cannot create a notice for a module that is off, because that job does not enqueue (see PANT-16 and MAINT-08).

## Data and contract
Tables: notification_pref  Endpoints: none  Types: NotificationPref
Schema change: yes — supabase/migrations/20261108100900_CORE-12_notification_pref.sql  Contract change: no

## Tests required
Unit: n/a (database behaviour is asserted in pgTAP, TESTING §2)
Component: n/a (no UI)
Integration: supabase/tests/CORE-12_prefs.sql → AC1 missing row defaults to enabled; AC2 disable persists; AC3 other user denied
E2E: n/a (covered by the client journeys for this ID)
Offline test: n/a (server-side)

## Dependencies
Blocked by: CORE-02-db  Blocks: CORE-12-web, CORE-12-ios, CORE-12-android, PANT-16-db, MAINT-11-db

## Skill
.claude/skills/sql-supabase/SKILL.md

## Open questions
notification_pref is not in the §8 SyncTable list, and DESIGN B9 says the edit goes through the outbox. Proposed default: add notification_pref to the sync allow-list in SHOP-08-api.

# CORE-12-web — Notification preferences

## Summary
A person can choose, for this household, which notices they want.

## Spec references
SPEC §4 CORE-12; §8 NotificationType; DESIGN B9; TESTING §3 client rows

## Scope
In: Notification preferences (B9): a switch per type, and a link to the system notification settings.
Out: Registering the device token and sending pushes (PANT-16, MAINT-08, MAINT-11). The profile link (CORE-06). Skipped layers: -api — §7 has no preference endpoint. DESIGN B9 writes preferences through the outbox, so they travel with sync.

## Acceptance criteria
- [ ] AC1 Given I open the screen, when it loads, then I see switches for Eat soon, Low stock, Task due, Warranty, and Activity.
- [ ] AC2 Given I turn Eat soon off, when the change is saved, then an outbox upsert stores enabled false for this household.
- [ ] AC3 Given the system notification permission is denied, when the screen loads, then the permission-denied banner is shown and the switches remain.
- [ ] AC4 Given pantry is off, when the screen loads, then Eat soon and Low stock are hidden.
- [ ] AC5 Given maintenance is off, when the screen loads, then Task due and Warranty are hidden, and Activity stays.

## Design
Screen: DESIGN B9 — States: idle, permission-denied. Interactions: toggle a type; open system notification settings.
Deviations from the plan: none

## Offline behaviour
DESIGN B9: the change goes through the outbox, so it is queued offline and applied on sync. SPEC §6.5 does not list this edit in the offline-write sentence; the proposed default is the design plan.

## Module-off behaviour
Rows for a disabled module are hidden (DESIGN B9). Activity stays because it is core.

## Data and contract
Tables: notification_pref  Endpoints: none  Types: NotificationPref
Schema change: no  Contract change: no

## Tests required
Unit: web/src/core/NotificationPrefs.test.ts → AC2 one upsert is queued with type eat_soon and enabled false; AC4 and AC5 hidden rows match the flags
Component: web/src/core/NotificationPrefs.test.tsx → AC1 idle with five switches, AC3 permission-denied; toggle and the system-settings link
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: n/a (core)

## Dependencies
Blocked by: CORE-12-db, CORE-05-web  Blocks: PANT-16-web, MAINT-08-web

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
DESIGN B9 says the screen works through the outbox, and notification_pref is absent from SyncTable. Proposed default: queue the upsert and let SHOP-08-api accept the table. The system-settings link opens the platform notification settings.

# CORE-12-ios — Notification preferences

## Summary
A person can choose, for this household, which notices they want.

## Spec references
SPEC §4 CORE-12; §8 NotificationType; DESIGN B9; TESTING §3 client rows

## Scope
In: Notification preferences (B9): a switch per type, and a link to the system notification settings.
Out: Registering the device token and sending pushes (PANT-16, MAINT-08, MAINT-11). The profile link (CORE-06). Skipped layers: -api — §7 has no preference endpoint. DESIGN B9 writes preferences through the outbox, so they travel with sync.

## Acceptance criteria
- [ ] AC1 Given I open the screen, when it loads, then I see switches for Eat soon, Low stock, Task due, Warranty, and Activity.
- [ ] AC2 Given I turn Eat soon off, when the change is saved, then an outbox upsert stores enabled false for this household.
- [ ] AC3 Given the system notification permission is denied, when the screen loads, then the permission-denied banner is shown and the switches remain.
- [ ] AC4 Given pantry is off, when the screen loads, then Eat soon and Low stock are hidden.
- [ ] AC5 Given maintenance is off, when the screen loads, then Task due and Warranty are hidden, and Activity stays.

## Design
Screen: DESIGN B9 — States: idle, permission-denied. Interactions: toggle a type; open system notification settings.
Deviations from the plan: none

## Offline behaviour
DESIGN B9: the change goes through the outbox, so it is queued offline and applied on sync. SPEC §6.5 does not list this edit in the offline-write sentence; the proposed default is the design plan.

## Module-off behaviour
Rows for a disabled module are hidden (DESIGN B9). Activity stays because it is core.

## Data and contract
Tables: notification_pref  Endpoints: none  Types: NotificationPref
Schema change: no  Contract change: no

## Tests required
Unit: apps/ios/Packages/core/Tests/NotificationPrefsTests.swift → AC2 one upsert is queued with type eat_soon and enabled false; AC4 and AC5 hidden rows match the flags
Component: apps/ios/AbodeNestlyTests/NotificationPrefsViewTests.swift → AC1 idle with five switches, AC3 permission-denied; toggle and the system-settings link
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: n/a (core)

## Dependencies
Blocked by: CORE-12-db, CORE-05-ios  Blocks: PANT-16-ios, MAINT-08-ios

## Skill
.claude/skills/swift-ios/SKILL.md

## Open questions
DESIGN B9 says the screen works through the outbox, and notification_pref is absent from SyncTable. Proposed default: queue the upsert and let SHOP-08-api accept the table. The system-settings link opens the platform notification settings.

# CORE-12-android — Notification preferences

## Summary
A person can choose, for this household, which notices they want.

## Spec references
SPEC §4 CORE-12; §8 NotificationType; DESIGN B9; TESTING §3 client rows

## Scope
In: Notification preferences (B9): a switch per type, and a link to the system notification settings.
Out: Registering the device token and sending pushes (PANT-16, MAINT-08, MAINT-11). The profile link (CORE-06). Skipped layers: -api — §7 has no preference endpoint. DESIGN B9 writes preferences through the outbox, so they travel with sync.

## Acceptance criteria
- [ ] AC1 Given I open the screen, when it loads, then I see switches for Eat soon, Low stock, Task due, Warranty, and Activity.
- [ ] AC2 Given I turn Eat soon off, when the change is saved, then an outbox upsert stores enabled false for this household.
- [ ] AC3 Given the system notification permission is denied, when the screen loads, then the permission-denied banner is shown and the switches remain.
- [ ] AC4 Given pantry is off, when the screen loads, then Eat soon and Low stock are hidden.
- [ ] AC5 Given maintenance is off, when the screen loads, then Task due and Warranty are hidden, and Activity stays.

## Design
Screen: DESIGN B9 — States: idle, permission-denied. Interactions: toggle a type; open system notification settings.
Deviations from the plan: none

## Offline behaviour
DESIGN B9: the change goes through the outbox, so it is queued offline and applied on sync. SPEC §6.5 does not list this edit in the offline-write sentence; the proposed default is the design plan.

## Module-off behaviour
Rows for a disabled module are hidden (DESIGN B9). Activity stays because it is core.

## Data and contract
Tables: notification_pref  Endpoints: none  Types: NotificationPref
Schema change: no  Contract change: no

## Tests required
Unit: apps/android/core/src/test/java/app/abodenestly/core/NotificationPrefsTest.kt → AC2 one upsert is queued with type eat_soon and enabled false; AC4 and AC5 hidden rows match the flags
Component: apps/android/core/src/test/java/app/abodenestly/core/NotificationPrefsComposeTest.kt → AC1 idle with five switches, AC3 permission-denied; toggle and the system-settings link
Integration: n/a (no API handler in this story)
E2E: n/a
Offline test: n/a (core)

## Dependencies
Blocked by: CORE-12-db, CORE-05-android  Blocks: PANT-16-android, MAINT-08-android

## Skill
.claude/skills/kotlin-android/SKILL.md

## Open questions
DESIGN B9 says the screen works through the outbox, and notification_pref is absent from SyncTable. Proposed default: queue the upsert and let SHOP-08-api accept the table. The system-settings link opens the platform notification settings.
