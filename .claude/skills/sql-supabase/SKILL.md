---
name: sql-supabase
description: How to change AbodeNestly's Postgres/Supabase database — migrations, tables, RLS policies, triggers, pg_cron jobs, Realtime publication, Storage policies and seed data. Use this whenever a task mentions a table, column, migration, trigger, policy, RLS, index, schema, Supabase, pg_cron, or docs/schema.sql, even if phrased as "store X" or "add a field".
---

# SQL / Supabase skill

Read `docs/SPEC.md` §5, §9, §11, §14 before starting. `docs/schema.sql` is the canonical DDL; migrations are how it reaches a database.

## Workflow for any schema change
1. Write a new migration: `supabase/migrations/<YYYYMMDDHHMMSS>_<id>_<short>.sql` (e.g. `20261110120000_PANT-13_unit_conversion.sql`). Migrations are append-only; never edit a merged one.
2. Apply the same change to `docs/schema.sql` so it stays a from-scratch definition, and paste the updated file into SPEC §14.
3. Add the table to the Realtime publication if it is household-scoped.
4. Add/extend `lib/sync/tables.ts` in the API and the contract types.
5. Test on `supabase start` (`supabase db reset` runs all migrations + `seed.sql`).

## Every household-scoped table must have
```sql
id            uuid primary key default gen_random_uuid(),
household_id  uuid not null references household(id) on delete cascade,
created_at    timestamptz not null default now(),
updated_at    timestamptz not null default now(),
deleted_at    timestamptz,
sync_seq      bigint not null default nextval('sync_seq_global')
```
then `call setup_scoped('<table>');` which attaches the sync trigger, enables RLS, creates the four member policies and the `(household_id, sync_seq)` index. If the table has a composite key (like `store_item_order`), keep `sync_seq` and `updated_at` and still call `setup_scoped`.

## RLS rules
- Policies use `is_member(household_id)` or `is_owner(household_id)` only. Do not write new membership checks inline.
- Global rows (template data, product cache) use `household_id is null` for read, and are written by the service role.
- Security-definer functions must `set search_path = public` and be as small as possible. Only `create_household`, `accept_invite`, `enqueue_daily_notifications`, `purge_soft_deleted` exist today; adding one needs a justification in the PR.
- Never grant to `anon` except the share-link read path, which is served by the API with a dedicated function, not a policy.

## Triggers
- Cross-module triggers are listed in SPEC §9. Adding one means updating §9 in the same PR.
- Triggers must check the household module flag when they cross modules.
- Keep triggers idempotent: a replayed sync must not create duplicates. Use partial unique indexes (see `list_item_open_pantry_idx`) and `on conflict do nothing`.
- `pantry_item.quantity` is written only by `pantry_movement_apply`. Do not add another writer.

## Soft delete
- Never `delete from` a household-scoped table in application code. Set `deleted_at`.
- Add the table to `purge_soft_deleted()` if it should be purged after 30 days.
- Foreign keys from a live row to a soft-deleted row are allowed; the UI treats the target as missing.

## Indexes
- `(household_id, sync_seq)` comes from `setup_scoped`.
- Add an index for every list-screen query: `(household_id, <sort column>) where deleted_at is null`.
- Partial unique indexes for "at most one open X".

## Naming
- Tables singular `snake_case` (`list_item`, not `list_items`).
- Policies `<table>_<action>`, triggers `<table>_<purpose>`, functions verb-first (`apply_pantry_movement`).
- Enum-like columns use `text` + `check (col in (...))`, not Postgres enums (easier to migrate).

## pg_cron
- Jobs are scheduled in migrations with `cron.schedule('<name>', '<cron>', $$...$$)`. Use `cron.unschedule('<name>')` before rescheduling in a later migration.
- Jobs only write rows (`notification`) — delivery happens in the API cron.

## Storage
- Bucket `households`, private. Policies:
  ```sql
  create policy households_rw on storage.objects for all
    using (bucket_id = 'households' and is_member((storage.foldername(name))[2]::uuid));
  ```
- Attachment rows are created by the API in `/attachments/sign`; the client uploads to the signed URL.

## Seed data (`supabase/seed.sql`)
Global `category` defaults (with shelf days), global `checklist_template` rows for spring/autumn, no household rows.

## Testing
Follow `docs/TESTING.md` §3 (`-db` row).

Write SQL tests as `supabase/tests/*.sql` using `pgTAP` (available in Supabase). Required for every PR that adds a trigger or policy:
- RLS: a member of household A cannot read/write household B rows.
- Trigger: the behaviour fires exactly once and respects the module flag.

## Checklist before PR
- [ ] Migration file + `docs/schema.sql` + SPEC §14 updated together
- [ ] Standard columns + `setup_scoped`
- [ ] Realtime publication updated
- [ ] Cross-module trigger documented in SPEC §9 and gated by module flag
- [ ] pgTAP test for RLS/trigger
- [ ] Feature ID in migration file name
