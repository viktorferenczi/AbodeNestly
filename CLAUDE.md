# CLAUDE.md

Read `AGENTS.md` and follow it. Then read `docs/SPEC.md` and the skill in `.claude/skills/` for the area you are changing (listed in AGENTS.md §1).

Claude-specific notes:
- Skills in `.claude/skills/*/SKILL.md` are loaded on demand; always load the one for the package you touch.
- Use `supabase start` for a local database before running integration tests.
- Prefer `pnpm -r lint typecheck test` from the repo root before finishing.
