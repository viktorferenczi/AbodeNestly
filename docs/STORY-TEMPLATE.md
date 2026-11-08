# Story template

Every story is a GitHub issue with the title `<ID>-<layer> — <short title>` where layer is `api`, `db`, `web`, `ios` or `android`. The body follows the template below **exactly**; sections may not be omitted (write "n/a" with a reason instead). Acceptance criteria use Given/When/Then and must be testable one by one.

```markdown
## Summary
<One sentence of user value: who, what, why.>

## Spec references
SPEC §4 <ID>; §<n> …; DESIGN <screen id(s)>; TESTING §3 <layer row>

## Scope
In: <bullet list of exactly what this story delivers>
Out: <what is deliberately left to other IDs>

## Acceptance criteria
- [ ] AC1 Given <context>, when <action>, then <observable result>.
- [ ] AC2 …
(Minimum 4 for screen stories, 3 for api/db stories. Each AC maps to at least one test below.)

## Design
Screen: DESIGN <id> — list every State and Interaction from the plan that this story implements.
Deviations from the plan: none | <explain and update DESIGN.md in the PR>

## Offline behaviour
<From SPEC §6.5; say exactly what works offline and how (outbox, queued, read-only, needs network).>

## Module-off behaviour
<From SPEC §9 matrix; what is hidden or disabled and what must not error.>

## Data and contract
Tables: <names>  Endpoints: <method path>  Types: <names from §8>
Schema change: no | yes — <migration name>  Contract change: no | yes

## Tests required
Unit: <file → cases>
Component: <file → one case per State and Interaction>
Integration: <file → cases> | n/a
E2E: <journey id and file> | n/a
Offline test: <yes, per TESTING §6> | n/a

## Dependencies
Blocked by: <IDs>  Blocks: <IDs>

## Skill
<path to SKILL.md>

## Open questions
none | <question — proposed default>
```

---

## Full example: `CORE-01-web — Sign in with email magic link, Apple and Google`

```markdown
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
Unit: web/src/core/auth/landing.test.ts → no household → /welcome; one household → start tab; all modules off → /home
Component: web/src/core/auth/SignIn.test.tsx → idle, sending, link-sent (resend timer), error, offline; submit, Apple, Google clicks call the auth API with the right provider
Integration: n/a (no API code)
E2E: E2E-CORE-1 (first half) web/e2e/CORE-01-signin.spec.ts → magic link via admin generateLink helper, lands on /welcome, reload keeps session
Offline test: n/a (core)

## Dependencies
Blocked by: CORE-01-db (profile trigger), CORE-01-api (none required; env config only)  Blocks: CORE-02-web, CORE-07-web

## Skill
.claude/skills/react-web/SKILL.md

## Open questions
none
```
