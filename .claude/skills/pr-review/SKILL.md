---
name: pr-review
description: Review a GitHub pull request for AbodeNestly from its URL — fetch the diff with the gh CLI, check it against docs/SPEC.md, AGENTS.md and the relevant platform skill, and post findings as review comments on the PR (request changes), or approve it when nothing is wrong. Use this whenever the user gives a PR link or number, says "review this PR", "check my pull request", "is this mergeable", or asks for code review of a branch, even if they don't say "review".
---

# PR review skill

Input: a PR URL (`https://github.com/<owner>/<repo>/pull/<n>`) or `owner/repo#n`. Output: a review posted on the PR, plus a one-paragraph summary in chat.

Requires the `gh` CLI, authenticated (`gh auth status`). If `gh` is missing or unauthenticated, stop and tell the user.

## Step 1 — Gather
```bash
gh pr view <url> --json number,title,body,baseRefName,headRefName,files,additions,deletions,labels,author
gh pr diff <url>
gh pr checks <url>            # CI status
```
If the diff is over ~3000 lines, review file by file with `gh pr diff <url> --name-only` then `gh api repos/{owner}/{repo}/pulls/{n}/files` and read each patch.

Identify the area(s) from the file paths and **read the matching skill(s)** before judging:
- `api/`, `packages/contract/` → `.claude/skills/node-backend/SKILL.md`
- `supabase/`, `docs/schema.sql` → `.claude/skills/sql-supabase/SKILL.md`
- `web/` → `.claude/skills/react-web/SKILL.md`
- `apps/ios/` → `.claude/skills/swift-ios/SKILL.md`
- `apps/android/` → `.claude/skills/kotlin-android/SKILL.md`

Read `docs/SPEC.md` sections the PR touches, plus `docs/DESIGN.md` for any screen and `docs/TESTING.md` §3 for required tests (§4 for the feature ID, §6 for sync, §7–8 for contract, §9 for cross-module, §14 for schema).

## Step 2 — Check, in this order

### Blockers (request changes)
1. **No feature ID** in title/branch, or ID does not exist in SPEC §4.
2. **Spec violation**: behaviour contradicts SPEC acceptance summary, §6 sync rules, §9 coupling list, or §11 security.
3. **Unjustified dependency**: new package/pod/Gradle dep without the one-line justification in the PR body.
4. **Cross-module import** (module code importing another module).
5. **Sync column misuse**: new household table without standard columns / `setup_scoped`; client sets `updated_at`/`sync_seq`; direct write to `pantry_item.quantity`; hard `delete` on a household table.
6. **Data write bypassing the outbox** in shopping/pantry client code.
7. **Hand-written DTOs** instead of contract/generated models.
8. **Schema change without** migration + `docs/schema.sql` + SPEC §14 together; trigger added without SPEC §9 update or module-flag gate; new table without RLS or Realtime publication.
9. **Security**: service role used for user requests; secrets in code; SQL built by string interpolation; public endpoint returning more than SPEC allows.
10. **Missing required tests**: integration test for trigger/RLS changes; offline test for shopping/pantry client features.
11. **CI failing.**
12. **Module-off behaviour** not handled (feature visible or erroring when flag is off).
13. **Screen story without its DESIGN.md states**: a State or Interaction listed in the plan is missing from the implementation or from component tests; or an e2e journey the story names is absent.

### Should fix (comment, not blocking)
- Logic bugs, race conditions, unhandled errors, missing null handling.
- Float for money; string-typed errors; wrong date type (datetime where SPEC says date).
- Naming not following AGENTS.md §4.
- Missing index for a new list query.
- Accessibility gaps on new UI.
- Unnecessary abstraction or refactor outside the story's scope.

### Nits (comment, prefix `nit:`)
- Style, wording, small simplifications. Never block on nits.

## Step 3 — Post the review

Post **line comments** on the exact diff lines, each one self-contained: what is wrong, why (cite SPEC section or skill rule), and the fix. Max ~12 inline comments; aggregate repeated patterns into one comment with a count.

Use the GitHub API to create a single review with all comments:
```bash
gh api repos/{owner}/{repo}/pulls/{n}/reviews -X POST \
  -f event=REQUEST_CHANGES|COMMENT|APPROVE \
  -f body="$SUMMARY" \
  --input comments.json      # [{"path":"api/sync.ts","line":42,"side":"RIGHT","body":"..."}]
```
Decide the event:
- Any blocker → `REQUEST_CHANGES`.
- Only should-fix/nits → `COMMENT`.
- Nothing to say → `APPROVE` with body `LGTM — checked against SPEC §<sections> and <skill>; no findings.` Do not invent nits to have something to say.

Summary body format:
```
## Review: <ID> <title>
Verdict: Request changes | Comment | Approve
Checked: SPEC §4 <ID>, §6, §9; skill: <name>; CI: passing/failing

### Blockers (n)
- path:line — one line each
### Should fix (n)
- …
### Nits (n)
- …
```
Omit empty sections.

## Step 4 — Chat reply
One paragraph: verdict, number of findings per level, the single most important one. Include the review URL from the API response.

## Tone
Precise, neutral, specific. Praise is fine when the code is good but keep it to one line. Never speculate about the author's intent; ask a question in the comment if unclear. Never approve with unresolved blockers "to be fixed later".

## Don't
- Don't re-review files outside the diff.
- Don't post duplicate comments on a PR you already reviewed; fetch existing review comments first (`gh api .../pulls/{n}/comments`) and only add new findings.
- Don't modify the PR branch yourself unless explicitly asked.
