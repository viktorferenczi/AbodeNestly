-- SHOP-08-db: remember applied sync mutation ids for 24 h (SPEC §6.3) so a retried push is idempotent.
-- Not household-scoped: keyed per user (the API forwards the user's JWT), so no household_id/sync_seq,
-- no setup_scoped, not in Realtime and not in lib/sync/tables.ts.

create table sync_applied (
  user_id      uuid not null default auth.uid() references auth.users(id) on delete cascade,
  mutation_id  uuid not null,
  applied_at   timestamptz not null default now(),
  primary key (user_id, mutation_id)
);
-- Self-only policies; no delete policy (the cron purge runs as superuser).
alter table sync_applied enable row level security;
create policy sync_applied_select on sync_applied for select using (user_id = auth.uid());
create policy sync_applied_insert on sync_applied for insert with check (user_id = auth.uid());
create policy sync_applied_update on sync_applied for update
  using (user_id = auth.uid()) with check (user_id = auth.uid());
create index sync_applied_applied_at_idx on sync_applied (applied_at);  -- hourly cleanup

-- true = newly recorded, apply the mutation; false = already applied in the last 24 h, skip it.
-- Expiry is decided here, so it holds even before the cleanup job runs. Called inside the push
-- transaction, so a rolled-back push also forgets its ids.
create function record_sync_mutation(p_mutation_id uuid) returns boolean
language sql set search_path = public as $$
  with ins as (
    insert into sync_applied as s (user_id, mutation_id) values (auth.uid(), p_mutation_id)
    on conflict (user_id, mutation_id) do update set applied_at = now()
      where s.applied_at < now() - interval '24 hours'
    returning 1)
  select exists (select 1 from ins)
$$;

select cron.schedule('purge-sync-applied', '15 * * * *',
  $$delete from sync_applied where applied_at < now() - interval '24 hours'$$);
