-- CORE-02: creating a household seeds the owner, a Groceries list and default locations.
-- shopping_list and location are created here (SHOP-01-db / PANT-01-db are blocked by this story);
-- shopping_list.store_id gets its FK to store in SHOP-05-db.

create table shopping_list (
  id            uuid primary key default gen_random_uuid(),
  household_id  uuid not null references household(id) on delete cascade,
  name          text not null,
  store_id      uuid,
  is_template   boolean not null default false,   -- "weekly basics"
  share_token   text unique,                       -- read-only public link
  archived_at   timestamptz,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  deleted_at    timestamptz,
  sync_seq      bigint not null default nextval('sync_seq_global')
);
call setup_scoped('shopping_list');

create table location (
  id            uuid primary key default gen_random_uuid(),
  household_id  uuid not null references household(id) on delete cascade,
  name          text not null,
  kind          text not null default 'pantry' check (kind in ('fridge','freezer','pantry','other')),
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  deleted_at    timestamptz,
  sync_seq      bigint not null default nextval('sync_seq_global')
);
call setup_scoped('location');

alter publication supabase_realtime add table shopping_list, location;

create or replace function create_household(p_name text) returns uuid
language plpgsql security definer set search_path = public as $$
declare hid uuid;
begin
  if auth.uid() is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;
  insert into household(name, created_by) values (p_name, auth.uid()) returning id into hid;
  insert into member(household_id, user_id, role) values (hid, auth.uid(), 'owner');
  insert into shopping_list(household_id, name) values (hid, 'Groceries');
  insert into location(household_id, name, kind) values
    (hid, 'Fridge', 'fridge'), (hid, 'Freezer', 'freezer'), (hid, 'Pantry', 'pantry');
  return hid;
end $$;
