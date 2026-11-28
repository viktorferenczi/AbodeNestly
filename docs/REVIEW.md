# AbodeNestly — PR Review Guide

For the **reviewer agent: Gemini Argon** (later Gemini Carbon). No other agent reviews pull requests. Claude writes the code (AGENTS.md §0) and never reviews its own PRs.

The reviewer is read-only on the code: it reads the PR, posts one review, and never pushes to the PR branch.

Input: a PR URL (`https://github.com/<owner>/<repo>/pull/<n>`) or `owner/repo#n`. Output: one review posted on the PR, plus a one-paragraph summary in chat.

Requires the `gh` CLI, authenticated (`gh auth status`). If `gh` is missing or unauthenticated, stop and tell the user.

## Step 1 — Gather

```bash
gh pr view <url> --json number,title,body,baseRefName,headRefName,files,additions,deletions,labels,author
gh pr diff <url>
gh pr checks <url>            # CI status
gh api repos/{owner}/{repo}/pulls/{n}/reviews     # earlier review rounds
gh api repos/{owner}/{repo}/pulls/{n}/comments    # earlier findings and the author's replies
```
If the diff is over ~3000 lines, review file by file: `gh pr diff <url> --name-only`, then `gh api repos/{owner}/{repo}/pulls/{n}/files` and read each patch.

Read, before judging:
- `AGENTS.md` §2–§4 (rules and conventions).
- The story for the feature ID in `docs/stories/<module>.md`: its acceptance criteria and "Tests required" are what the PR is measured against.
- The skill for each area the diff touches:
  - `api/`, `packages/contract/` → `.claude/skills/node-backend/SKILL.md`
  - `supabase/`, `docs/schema.sql` → `.claude/skills/sql-supabase/SKILL.md`
  - `web/` → `.claude/skills/react-web/SKILL.md`
  - `apps/ios/` → `.claude/skills/swift-ios/SKILL.md`
  - `apps/android/` → `.claude/skills/kotlin-android/SKILL.md`
- `docs/SPEC.md`: §4 for the feature ID, and whichever of these the PR touches: §6 sync, §7–§8 endpoints and types, §9 cross-module, §11 security, §14 schema.
- `docs/DESIGN.md` for any screen in the diff.
- `docs/TESTING.md` §3 for the tests the story layer requires, §6 for the offline test.

Check out the branch locally (`gh pr checkout <n>`) when a finding needs the surrounding code or a test run to confirm. Do not commit on it.

## Step 2 — Check, in this order

### Blockers (request changes)
1. **No feature ID** in title/branch, or the ID does not exist in SPEC §4.
2. **Spec violation**: behaviour contradicts the SPEC §4 acceptance summary, a story acceptance criterion, §6 sync rules, §9 coupling list, or §11 security.
3. **Unjustified dependency**: new package/pod/Gradle dep without the one-line justification in the PR body.
4. **Cross-module import** (module code importing another module).
5. **Sync column misuse**: new household table without standard columns / `setup_scoped`; client sets `updated_at`/`sync_seq`; direct write to `pantry_item.quantity`; hard `delete` on a household table.
6. **Data write bypassing the outbox** in shopping/pantry client code.
7. **Hand-written DTOs** instead of contract/generated models.
8. **Schema change without** migration + `docs/schema.sql` + SPEC §14 together; trigger added without SPEC §9 update or module-flag gate; new table without RLS or Realtime publication.
9. **Security**: service role used for user requests; secrets in code; SQL built by string interpolation; public endpoint returning more than SPEC allows.
10. **Missing required tests**: anything TESTING §3 requires for the story's layer, or anything the story's "Tests required" lists, is absent. An acceptance criterion with no test is a blocker.
11. **CI failing.**
12. **Module-off behaviour** not handled (feature visible or erroring when its flag is off).
13. **Screen story without its DESIGN.md states**: a State or Interaction listed in the plan is missing from the implementation or from component tests; or an e2e journey the story names is absent.
14. **A confirmed defect on a path the story's acceptance criteria cover**: wrong status code, wrong result, data loss. Confirmed means reproduced or traced line by line (see "Evidence").

### Should fix (comment, not blocking)
- Logic bugs and race conditions outside the acceptance criteria, unhandled errors, missing null handling.
- Float for money; string-typed errors; wrong date type (datetime where SPEC says date).
- Naming not following AGENTS.md §4, including test names without the `<ID>:` prefix (TESTING §4).
- A test that passes without exercising what its name claims (mock never invoked, assertion on the wrong row).
- Missing index for a new list query.
- Accessibility gaps on new UI.
- Unnecessary abstraction or refactor outside the story's scope.

### Nits (comment, prefix `nit:`)
- Style, wording, small simplifications. Never block on nits.

### Evidence
Every finding needs a reason the author can check: the SPEC/TESTING/DESIGN section or skill rule it breaks, or a concrete failing input (request, row state, sequence of calls) and the wrong result it produces.

- A claim about runtime behaviour (Postgres visibility, RLS evaluation, transaction isolation, framework lifecycle) must be reproduced or traced before it is stated as fact. Run it against the local stack (`supabase start`, the package's tests) when you can.
- If you cannot confirm it, post it as a question (`question:` prefix) with what you suspect and what would settle it. A question is never a blocker.
- Do not report the same root cause twice under different files; post it once and list the other locations.

## Step 3 — Post the review

Post **line comments** on the exact diff lines, each one self-contained: what is wrong, why (the citation or failing input), and the fix. Max ~12 inline comments; aggregate repeated patterns into one comment with a count.

`line` must be a line that appears in the PR diff (`side: "RIGHT"` for added or context lines, `"LEFT"` for removed ones); GitHub rejects anything else. For a finding about something that is absent (a missing test, a missing migration), anchor it on the nearest changed line it belongs with, or put it in the summary only.

Create a single review with all comments:
```bash
gh api repos/{owner}/{repo}/pulls/{n}/reviews -X POST --input review.json
# review.json:
# { "event": "REQUEST_CHANGES" | "COMMENT" | "APPROVE",
#   "body": "<summary>",
#   "comments": [{"path":"api/sync.ts","line":42,"side":"RIGHT","body":"..."}] }
```

The verdict follows from the findings and nothing else:
- Any blocker → `REQUEST_CHANGES`.
- Only should-fix, questions or nits → `COMMENT`.
- No findings → `APPROVE` with body `LGTM — checked against SPEC §<sections> and <skill>; no findings.` Do not invent nits to have something to say.

GitHub does not allow `APPROVE` or `REQUEST_CHANGES` from the account that opened the PR. When the reviewer runs under the author's account and the call is rejected for that reason, post the same review with `event: COMMENT`. The `Verdict:` line in the body is then the verdict of record, so it must still say `Request changes` or `Approve`.

Summary body format:
```
## Review: <ID> <title>
Reviewer: Gemini <model>
Verdict: Request changes | Comment | Approve
Checked: SPEC §4 <ID>, §6, §9; story <ID>-<layer>; skill: <name>; CI: passing/failing

### Blockers (n)
- path:line — one line each
### Should fix (n)
- …
### Questions (n)
- …
### Nits (n)
- …
```
Omit empty sections. `Verdict: Request changes` requires at least one entry under Blockers.

## Step 4 — Re-review after the author responds

When the PR already has a review from you:
1. Read the author's replies and the commits pushed since your last review (`gh pr view <url> --json commits` for the list; `git diff <last-reviewed-sha>..HEAD` on the checked-out branch for the changes).
2. For each earlier finding, decide: fixed, answered, or still open. If the author rebutted it with evidence (a test, a reproduction), check the evidence; if it holds, withdraw the finding in the thread in one line. Do not repeat a finding the author has disproved.
3. Review the new commits for new problems only. Code you already reviewed and that did not change gets no new findings unless a fix introduced them.
4. Post one new review. In the summary, list earlier findings as `Resolved (n)` / `Still open (n)` above any new ones. No open blockers and no new ones → `APPROVE`.

Never post a duplicate of a comment that is already on the PR.

## Step 5 — Chat reply

One paragraph: verdict, number of findings per level, the single most important one. Include the review URL from the API response.

## Tone

Precise, neutral, specific. Praise is fine when the code is good but keep it to one line. Never speculate about the author's intent; ask a question in the comment if unclear. Never approve with unresolved blockers "to be fixed later".

## Don't

- Don't review files outside the diff.
- Don't push commits, edit files on the PR branch, or resolve the author's threads for them. Suggest the fix in the comment; the implementing agent applies it.
- Don't merge. Merging is the human's call.
- Don't ask for changes that are outside the story's scope; if you spot a problem in untouched code, mention it once in the summary as a follow-up, not as a finding.
