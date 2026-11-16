-- CORE-08: per-household activity feed rows ("Anna checked off Milk"). X6-api writes them, members read them.

create table activity (
  id            uuid primary key default gen_random_uuid(),
  household_id  uuid not null references household(id) on delete cascade,
  actor_id      uuid references auth.users(id),
  module        text not null check (module in ('core','shopping','pantry','maintenance')),
  entity        text not null,          -- table name
  entity_id     uuid not null,
  action        text not null,          -- created | updated | checked | done ...
  summary       text not null,          -- "Anna checked off Milk"
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  deleted_at    timestamptz,
  sync_seq      bigint not null default nextval('sync_seq_global')
);
call setup_scoped('activity');
-- A member cannot attribute a sentence to someone else (X6-api forwards the user's JWT).
drop policy activity_insert on activity;
create policy activity_insert on activity for insert with check (
  is_member(household_id) and actor_id = auth.uid());

-- A WITH CHECK cannot see OLD, so a trigger keeps feed rows immutable for clients: only deleted_at may change
-- (soft delete), and a hard delete would never reach devices that already pulled the row (SPEC §6.3 tombstones).
-- Service-role / function-owner writes are not restricted.
create or replace function guard_activity_update() returns trigger
language plpgsql as $$
begin
  if current_user <> 'authenticated' then return new; end if;
  if new.household_id is distinct from old.household_id
     or new.actor_id is distinct from old.actor_id
     or new.module is distinct from old.module
     or new.entity is distinct from old.entity
     or new.entity_id is distinct from old.entity_id
     or new.action is distinct from old.action
     or new.summary is distinct from old.summary
     or new.created_at is distinct from old.created_at then
    raise exception 'activity_field_immutable' using errcode = '42501';
  end if;
  return new;
end $$;
create trigger activity_guard before update on activity for each row execute function guard_activity_update();

create or replace function guard_activity_delete() returns trigger
language plpgsql as $$
begin
  if current_user = 'authenticated' then
    raise exception 'activity_soft_delete_only' using errcode = '42501';
  end if;
  return old;
end $$;
create trigger activity_guard_delete before delete on activity for each row execute function guard_activity_delete();

-- Feed window: newest first per household. now() is not immutable, so the 30-day cut-off is a range scan
-- on created_at, not a partial-index predicate.
create index activity_feed_idx on activity (household_id, created_at desc) where deleted_at is null;

alter publication supabase_realtime add table activity;
