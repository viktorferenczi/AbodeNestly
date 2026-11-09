-- INIT baseline: shared helpers plus household/member, so later migrations (CORE-01 profile policies) can reference them.
-- Copied from docs/schema.sql; pg_cron is left to the first cron story.
-- Order differs from schema.sql only in that the tables come before the helpers: is_member/is_owner are
-- LANGUAGE sql, and Postgres validates their bodies (the member table) at creation time.

create extension if not exists "pgcrypto";
create sequence if not exists sync_seq_global;

create table household (
  id                uuid primary key default gen_random_uuid(),
  name              text not null,
  created_by        uuid not null references auth.users(id),
  module_shopping   boolean not null default true,
  module_pantry     boolean not null default true,
  module_maintenance boolean not null default true,
  currency          text not null default 'EUR',
  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now(),
  deleted_at        timestamptz,
  sync_seq          bigint not null default nextval('sync_seq_global')
);

create table member (
  household_id  uuid not null references household(id) on delete cascade,
  user_id       uuid not null references auth.users(id) on delete cascade,
  role          text not null check (role in ('owner','member')),
  joined_at     timestamptz not null default now(),
  left_at       timestamptz,
  updated_at    timestamptz not null default now(),
  sync_seq      bigint not null default nextval('sync_seq_global'),
  primary key (household_id, user_id)
);
create index member_user_idx on member(user_id);

-- ---------------------------------------------------------------------------
-- Helpers
-- ---------------------------------------------------------------------------

create or replace function set_sync_fields() returns trigger
language plpgsql as $$
begin
  new.updated_at := now();
  new.sync_seq := nextval('sync_seq_global');
  return new;
end $$;

create or replace function is_member(hid uuid) returns boolean
language sql stable security definer set search_path = public as $$
  select exists (
    select 1 from member
    where household_id = hid and user_id = auth.uid() and left_at is null
  );
$$;

create or replace function is_owner(hid uuid) returns boolean
language sql stable security definer set search_path = public as $$
  select exists (
    select 1 from member
    where household_id = hid and user_id = auth.uid()
      and role = 'owner' and left_at is null
  );
$$;

create or replace function name_key(p text) returns text
language sql immutable as $$
  select lower(regexp_replace(trim(p), '\s+', ' ', 'g'));
$$;

-- Attach standard sync trigger + RLS to a table in one call
create or replace procedure setup_scoped(tbl regclass)
language plpgsql as $$
declare t text := tbl::text;
begin
  execute format('create trigger %I_sync before insert or update on %s
                  for each row execute function set_sync_fields()', t, t);
  execute format('alter table %s enable row level security', t);
  execute format('create policy %I_select on %s for select using (is_member(household_id))', t, t);
  execute format('create policy %I_insert on %s for insert with check (is_member(household_id))', t, t);
  execute format('create policy %I_update on %s for update using (is_member(household_id))', t, t);
  execute format('create policy %I_delete on %s for delete using (is_member(household_id))', t, t);
  execute format('create index %I_sync_idx on %s (household_id, sync_seq)', t, t);
end $$;

-- household RLS (member is referenced by is_member, so policies are explicit)
alter table household enable row level security;
create policy household_select on household for select using (is_member(id));
create policy household_insert on household for insert with check (created_by = auth.uid());
create policy household_update on household for update using (is_owner(id));
create trigger household_sync before update on household for each row execute function set_sync_fields();

alter table member enable row level security;
create policy member_select on member for select using (is_member(household_id));
create policy member_update on member for update using (is_owner(household_id) or user_id = auth.uid());
-- inserts happen via security-definer functions (create_household, accept_invite)
create trigger member_sync before insert or update on member for each row execute function set_sync_fields();

create or replace function create_household(p_name text) returns uuid
language plpgsql security definer set search_path = public as $$
declare hid uuid;
begin
  insert into household(name, created_by) values (p_name, auth.uid()) returning id into hid;
  insert into member(household_id, user_id, role) values (hid, auth.uid(), 'owner');
  return hid;
end $$;
