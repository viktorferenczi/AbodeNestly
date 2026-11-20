-- CORE-12: per-user, per-household notification preferences.
-- A missing row means "enabled" (the §14 job uses coalesce(np.enabled, true)), so a row is only
-- written once someone changes a default. Turning a notice off sets enabled = false; rows are never deleted.

create table notification_pref (
  user_id       uuid not null references auth.users(id) on delete cascade,
  household_id  uuid not null references household(id) on delete cascade,
  type          text not null check (type in ('eat_soon','low_stock','task_due','warranty','activity')),
  enabled       boolean not null default true,
  updated_at    timestamptz not null default now(),
  sync_seq      bigint not null default nextval('sync_seq_global'),
  primary key (user_id, household_id, type)
);

-- setup_scoped is deliberately not used: its policies are member-wide, so any member could write my row.
-- These are its steps done by hand, with self-only policies that also require current membership
-- (a user cannot add a row for a household they are not in, nor keep editing after leaving).
create trigger notification_pref_sync before insert or update on notification_pref
  for each row execute function set_sync_fields();
alter table notification_pref enable row level security;
create policy notification_pref_select on notification_pref for select
  using (user_id = auth.uid() and is_member(household_id));
create policy notification_pref_insert on notification_pref for insert
  with check (user_id = auth.uid() and is_member(household_id));
create policy notification_pref_update on notification_pref for update
  using (user_id = auth.uid() and is_member(household_id))
  with check (user_id = auth.uid() and is_member(household_id));
-- No delete policy: there is no soft delete, so deletes are denied.
create index notification_pref_sync_idx on notification_pref (household_id, sync_seq);

alter publication supabase_realtime add table notification_pref;
