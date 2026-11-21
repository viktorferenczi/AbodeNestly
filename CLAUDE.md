# CLAUDE.md

Read `AGENTS.md` and follow it. Then read `docs/SPEC.md` and the skill in `.claude/skills/` for the area you are changing (listed in AGENTS.md §1).

Claude is the only agent that plans and writes code in this repo (AGENTS.md §0):
- **Opus plans**: stories (AGENTS.md §6), SPEC/DESIGN/TESTING proposals, and the implementation plan for a story. Planning sessions do not write feature code.
- **Sonnet implements**: one story at a time by AGENTS.md §3, including the fixes for review findings.
- `/model opusplan` gives this split in one session: Opus in plan mode, Sonnet for execution.
- Claude does not review pull requests. Review is done by the Gemini reviewer (`docs/REVIEW.md`); when asked to "review" a PR, say so instead of posting a review.

Claude-specific notes:
- Skills in `.claude/skills/*/SKILL.md` are loaded on demand; always load the one for the package you touch.
- Use `supabase start` for a local database before running integration tests.
- Prefer `pnpm -r lint typecheck test` from the repo root before finishing.
