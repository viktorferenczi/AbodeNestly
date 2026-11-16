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

-- Feed window: newest first per household. now() is not immutable, so the 30-day cut-off is a range scan
-- on created_at, not a partial-index predicate.
create index activity_feed_idx on activity (household_id, created_at desc) where deleted_at is null;

alter publication supabase_realtime add table activity;
