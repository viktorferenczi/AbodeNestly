-- Household app — Supabase/Postgres schema
-- Conventions:
--   * every household-scoped table has: id uuid (client-generated allowed),
--     household_id, created_at, updated_at, deleted_at (soft delete), sync_seq
--   * sync_seq is a global monotonic sequence bumped on every write; clients
--     pull with  WHERE household_id = $1 AND sync_seq > $cursor
--   * RLS: a user can touch a row only if they are a member of its household

create extension if not exists "pgcrypto";
create extension if not exists "pg_cron";

create sequence if not exists sync_seq_global;

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

-- ---------------------------------------------------------------------------
-- Core
-- ---------------------------------------------------------------------------

create table profile (
  id            uuid primary key references auth.users(id) on delete cascade,
  display_name  text not null,
  avatar_path   text,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now()
);
alter table profile enable row level security;
create policy profile_self on profile for all using (id = auth.uid());
-- members of the same household can see each other's name/avatar
create policy profile_household on profile for select using (
  exists (select 1 from member a join member b on a.household_id = b.household_id
          where a.user_id = auth.uid() and b.user_id = profile.id
            and a.left_at is null and b.left_at is null)
);

-- Create a profile row the first time a user signs in (any provider).
create or replace function handle_new_user() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  insert into profile(id, display_name, avatar_path)
  values (new.id,
          coalesce(new.raw_user_meta_data->>'full_name',
                   new.raw_user_meta_data->>'name',
                   split_part(new.email, '@', 1)),
          new.raw_user_meta_data->>'avatar_url')
  on conflict (id) do nothing;
  return new;
end $$;
create trigger on_auth_user_created after insert on auth.users
  for each row execute function handle_new_user();

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

-- household RLS (member is referenced by is_member, so policies are explicit)
alter table household enable row level security;
create policy household_select on household for select using (is_member(id));
create policy household_insert on household for insert with check (created_by = auth.uid());
create policy household_update on household for update using (is_owner(id));
create trigger household_sync before insert or update on household for each row execute function set_sync_fields();

alter table member enable row level security;
create policy member_select on member for select using (is_member(household_id));
create policy member_update on member for update using (is_owner(household_id) or user_id = auth.uid());
-- inserts happen via security-definer functions (create_household, accept_invite)
-- A client may only touch its own membership and may only *leave* (set left_at). RLS WITH CHECK cannot
-- see OLD, so this guard enforces what an UPDATE may change. Security-definer paths (accept_invite,
-- create_household) run as the function owner and are not restricted.
create or replace function guard_member_update() returns trigger
language plpgsql as $$
begin
  if current_user <> 'authenticated' then return new; end if;
  if new.household_id is distinct from old.household_id
     or new.user_id is distinct from old.user_id
     or new.joined_at is distinct from old.joined_at then
    raise exception 'member_identity_immutable' using errcode = '42501';
  end if;
  if is_owner(old.household_id) then return new; end if;
  if new.role is distinct from old.role then
    raise exception 'member_role_owner_only' using errcode = '42501';
  end if;
  if old.left_at is not null and new.left_at is distinct from old.left_at then
    raise exception 'member_rejoin_requires_invite' using errcode = '42501';
  end if;
  return new;
end $$;
create trigger member_guard before update on member for each row execute function guard_member_update();
create trigger member_sync before insert or update on member for each row execute function set_sync_fields();

-- Seeds the owner membership, the Groceries list and the default locations (CORE-02).
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

create table invite (
  id            uuid primary key default gen_random_uuid(),
  household_id  uuid not null references household(id) on delete cascade,
  code          text not null unique default encode(gen_random_bytes(6), 'hex'),
  role          text not null default 'member' check (role in ('owner','member')),
  created_by    uuid not null references auth.users(id),
  expires_at    timestamptz not null default now() + interval '7 days',
  max_uses      int not null default 10,
  uses          int not null default 0,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  deleted_at    timestamptz,
  sync_seq      bigint not null default nextval('sync_seq_global')
);
call setup_scoped('invite');
-- Only owners may mint owner invites; members may create member invites.
drop policy invite_insert on invite;
create policy invite_insert on invite for insert with check (
  is_member(household_id) and created_by = auth.uid()
  and (role = 'member' or is_owner(household_id)));

-- Clients may only retire an invite (deleted_at). uses is bumped only by accept_invite (function owner,
-- which returns early); role / max_uses / expires_at are owner-only and 'owner' invites need an owner.
create or replace function guard_invite_update() returns trigger
language plpgsql as $$
begin
  if current_user <> 'authenticated' then return new; end if;
  if new.household_id is distinct from old.household_id
     or new.code is distinct from old.code
     or new.created_by is distinct from old.created_by
     or new.uses is distinct from old.uses then
    raise exception 'invite_field_immutable' using errcode = '42501';
  end if;
  if not is_owner(old.household_id)
     and (new.role is distinct from old.role
          or new.max_uses is distinct from old.max_uses
          or new.expires_at is distinct from old.expires_at) then
    raise exception 'invite_owner_only' using errcode = '42501';
  end if;
  return new;
end $$;
create trigger invite_guard before update on invite for each row execute function guard_invite_update();

create or replace function accept_invite(p_code text) returns uuid
language plpgsql security definer set search_path = public as $$
declare inv invite;
begin
  if auth.uid() is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;
  select * into inv from invite
   where code = p_code and deleted_at is null and expires_at > now() and uses < max_uses
   for update;
  if not found then raise exception 'invalid_invite'; end if;
  -- Already an active member: no-op (no demotion, no use burned).
  if exists (select 1 from member
              where household_id = inv.household_id and user_id = auth.uid() and left_at is null) then
    return inv.household_id;
  end if;
  insert into member(household_id, user_id, role) values (inv.household_id, auth.uid(), inv.role)
    on conflict (household_id, user_id) do update set left_at = null, role = excluded.role;
  update invite set uses = uses + 1 where id = inv.id;
  return inv.household_id;
end $$;

create table activity (
  id            uuid primary key default gen_random_uuid(),
  household_id  uuid not null references household(id) on delete cascade,
  actor_id      uuid references auth.users(id),
  module        text not null,          -- core | shopping | pantry | maintenance
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

create table attachment (
  id            uuid primary key default gen_random_uuid(),
  household_id  uuid not null references household(id) on delete cascade,
  entity        text not null,
  entity_id     uuid not null,
  storage_path  text not null,          -- households/{hid}/{entity}/{id}/{file}
  mime          text not null,
  size_bytes    bigint not null,
  created_by    uuid references auth.users(id),
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  deleted_at    timestamptz,
  sync_seq      bigint not null default nextval('sync_seq_global')
);
call setup_scoped('attachment');
create index attachment_entity_idx on attachment(entity, entity_id);

create table device_token (
  id          uuid primary key default gen_random_uuid(),
  user_id     uuid not null references auth.users(id) on delete cascade,
  platform    text not null check (platform in ('ios','android','web')),
  token       text not null unique,
  created_at  timestamptz not null default now(),
  last_seen   timestamptz not null default now()
);
alter table device_token enable row level security;
create policy device_token_self on device_token for all using (user_id = auth.uid());

create table notification_pref (
  user_id       uuid not null references auth.users(id) on delete cascade,
  household_id  uuid not null references household(id) on delete cascade,
  type          text not null,  -- eat_soon | low_stock | task_due | warranty | activity
  enabled       boolean not null default true,
  updated_at    timestamptz not null default now(),
  sync_seq      bigint not null default nextval('sync_seq_global'),
  primary key (user_id, household_id, type)
);
alter table notification_pref enable row level security;
create policy notification_pref_self on notification_pref for all using (user_id = auth.uid());

create table notification (
  id            uuid primary key default gen_random_uuid(),
  household_id  uuid not null references household(id) on delete cascade,
  user_id       uuid not null references auth.users(id) on delete cascade,
  type          text not null,
  title         text not null,
  body          text not null,
  data          jsonb not null default '{}',
  created_at    timestamptz not null default now(),
  sent_at       timestamptz,
  read_at       timestamptz
);
alter table notification enable row level security;
create policy notification_self on notification for all using (user_id = auth.uid());
create index notification_unsent_idx on notification(sent_at) where sent_at is null;

-- ---------------------------------------------------------------------------
-- Global product cache (barcode lookups) — readable by everyone, written by API
-- ---------------------------------------------------------------------------

create table product (
  barcode     text primary key,
  name        text not null,
  brand       text,
  category    text,
  image_url   text,
  default_unit text,
  source      text,                      -- openfoodfacts | manual
  fetched_at  timestamptz not null default now()
);
alter table product enable row level security;
create policy product_read on product for select using (auth.role() = 'authenticated');

-- ---------------------------------------------------------------------------
-- Shopping
-- ---------------------------------------------------------------------------

create table store (
  id            uuid primary key default gen_random_uuid(),
  household_id  uuid not null references household(id) on delete cascade,
  name          text not null,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  deleted_at    timestamptz,
  sync_seq      bigint not null default nextval('sync_seq_global')
);
call setup_scoped('store');

create table shopping_list (
  id            uuid primary key default gen_random_uuid(),
  household_id  uuid not null references household(id) on delete cascade,
  name          text not null,
  store_id      uuid references store(id),
  is_template   boolean not null default false,   -- "weekly basics"
  share_token   text unique,                       -- read-only public link
  archived_at   timestamptz,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  deleted_at    timestamptz,
  sync_seq      bigint not null default nextval('sync_seq_global')
);
call setup_scoped('shopping_list');

create table list_item (
  id              uuid primary key default gen_random_uuid(),
  household_id    uuid not null references household(id) on delete cascade,
  list_id         uuid not null references shopping_list(id) on delete cascade,
  name            text not null,
  name_key        text generated always as (name_key(name)) stored,
  quantity        numeric,
  unit            text,
  note            text,
  barcode         text references product(barcode),
  checked         boolean not null default false,
  checked_at      timestamptz,
  checked_by      uuid references auth.users(id),
  manual_position int,                              -- user drag order, null = use learned
  source          text not null default 'manual'
                  check (source in ('manual','pantry','maintenance','template','recipe')),
  pantry_item_id  uuid,                             -- fk added after pantry_item exists
  maint_task_id   uuid,
  created_by      uuid references auth.users(id),
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  deleted_at      timestamptz,
  sync_seq        bigint not null default nextval('sync_seq_global')
);
call setup_scoped('list_item');
create index list_item_list_idx on list_item(list_id) where deleted_at is null;
-- at most one open pantry-sourced line per pantry item per list
create unique index list_item_open_pantry_idx
  on list_item(list_id, pantry_item_id)
  where source = 'pantry' and checked = false and deleted_at is null;

-- learned aisle order: running mean of check-off index per store
create table store_item_order (
  household_id  uuid not null references household(id) on delete cascade,
  store_id      uuid not null references store(id) on delete cascade,
  name_key      text not null,
  mean_position numeric not null,
  samples       int not null default 1,
  updated_at    timestamptz not null default now(),
  sync_seq      bigint not null default nextval('sync_seq_global'),
  primary key (store_id, name_key)
);
call setup_scoped('store_item_order');

-- Learn when an item is checked: position = rank of check among list's checks
create or replace function learn_store_order() returns trigger
language plpgsql as $$
declare sid uuid; pos int;
begin
  if new.checked and not coalesce(old.checked,false) then
    select store_id into sid from shopping_list where id = new.list_id;
    if sid is null then return new; end if;
    select count(*) into pos from list_item
      where list_id = new.list_id and checked and deleted_at is null and id <> new.id;
    insert into store_item_order(household_id, store_id, name_key, mean_position)
      values (new.household_id, sid, new.name_key, pos)
    on conflict (store_id, name_key) do update
      set mean_position = (store_item_order.mean_position * store_item_order.samples + pos)
                          / (store_item_order.samples + 1),
          samples = store_item_order.samples + 1;
  end if;
  return new;
end $$;
create trigger list_item_learn after update on list_item
  for each row execute function learn_store_order();

create table item_price (
  id            uuid primary key default gen_random_uuid(),
  household_id  uuid not null references household(id) on delete cascade,
  store_id      uuid not null references store(id) on delete cascade,
  name_key      text not null,
  price         numeric not null,
  seen_at       timestamptz not null default now(),
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  deleted_at    timestamptz,
  sync_seq      bigint not null default nextval('sync_seq_global')
);
call setup_scoped('item_price');

-- ---------------------------------------------------------------------------
-- Pantry
-- ---------------------------------------------------------------------------

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

create table category (
  id                 uuid primary key default gen_random_uuid(),
  household_id       uuid not null references household(id) on delete cascade,
  name               text not null,
  default_shelf_days int,                            -- expiry suggestion
  created_at         timestamptz not null default now(),
  updated_at         timestamptz not null default now(),
  deleted_at         timestamptz,
  sync_seq           bigint not null default nextval('sync_seq_global')
);
call setup_scoped('category');

create table pantry_item (
  id              uuid primary key default gen_random_uuid(),
  household_id    uuid not null references household(id) on delete cascade,
  location_id     uuid references location(id),
  category_id     uuid references category(id),
  name            text not null,
  name_key        text generated always as (name_key(name)) stored,
  quantity        numeric not null default 1,
  unit            text,
  low_threshold   numeric,                           -- null = never auto-add
  auto_add        boolean not null default true,
  barcode         text references product(barcode),
  added_at        date not null default current_date,
  expires_at      date,
  note            text,
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  deleted_at      timestamptz,
  sync_seq        bigint not null default nextval('sync_seq_global')
);
call setup_scoped('pantry_item');
create index pantry_item_expires_idx on pantry_item(household_id, expires_at) where deleted_at is null;

alter table list_item add constraint list_item_pantry_fk
  foreign key (pantry_item_id) references pantry_item(id) on delete set null;

-- Quantity history: usage stats + the delta-safe sync path for quantity
create table pantry_movement (
  id              uuid primary key default gen_random_uuid(),
  household_id    uuid not null references household(id) on delete cascade,
  pantry_item_id  uuid not null references pantry_item(id) on delete cascade,
  delta           numeric not null,
  reason          text not null check (reason in ('added','used','restocked','discarded','adjusted')),
  actor_id        uuid references auth.users(id),
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  deleted_at      timestamptz,
  sync_seq        bigint not null default nextval('sync_seq_global')
);
call setup_scoped('pantry_movement');
create index pantry_movement_item_idx on pantry_movement(pantry_item_id, created_at);

-- Movements are the source of truth for quantity; apply delta on insert
create or replace function apply_pantry_movement() returns trigger
language plpgsql as $$
begin
  update pantry_item set quantity = quantity + new.delta where id = new.pantry_item_id;
  return new;
end $$;
create trigger pantry_movement_apply after insert on pantry_movement
  for each row execute function apply_pantry_movement();

create table unit_conversion (
  household_id  uuid not null references household(id) on delete cascade,
  from_unit     text not null,
  to_unit       text not null,
  factor        numeric not null,
  updated_at    timestamptz not null default now(),
  sync_seq      bigint not null default nextval('sync_seq_global'),
  primary key (household_id, from_unit, to_unit)
);
call setup_scoped('unit_conversion');

create table recipe (
  id            uuid primary key default gen_random_uuid(),
  household_id  uuid not null references household(id) on delete cascade,
  name          text not null,
  instructions  text,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  deleted_at    timestamptz,
  sync_seq      bigint not null default nextval('sync_seq_global')
);
call setup_scoped('recipe');

create table recipe_ingredient (
  id            uuid primary key default gen_random_uuid(),
  household_id  uuid not null references household(id) on delete cascade,
  recipe_id     uuid not null references recipe(id) on delete cascade,
  name          text not null,
  name_key      text generated always as (name_key(name)) stored,
  quantity      numeric,
  unit          text,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  deleted_at    timestamptz,
  sync_seq      bigint not null default nextval('sync_seq_global')
);
call setup_scoped('recipe_ingredient');

-- Cross-module: pantry low stock -> shopping list (only if shopping module on)
create or replace function pantry_low_stock_to_list() returns trigger
language plpgsql as $$
declare target_list uuid;
begin
  if new.deleted_at is not null or not new.auto_add or new.low_threshold is null then return new; end if;
  if new.quantity > new.low_threshold then return new; end if;
  if old is not null and old.quantity <= old.low_threshold then return new; end if; -- already low
  if not (select module_shopping from household where id = new.household_id) then return new; end if;

  select id into target_list from shopping_list
    where household_id = new.household_id and deleted_at is null and archived_at is null
      and not is_template
    order by created_at limit 1;
  if target_list is null then return new; end if;

  insert into list_item(household_id, list_id, name, quantity, unit, source, pantry_item_id)
    values (new.household_id, target_list, new.name, greatest(new.low_threshold * 2 - new.quantity, 1),
            new.unit, 'pantry', new.id)
  on conflict do nothing;
  return new;
end $$;
create trigger pantry_item_low_stock after insert or update of quantity, low_threshold on pantry_item
  for each row execute function pantry_low_stock_to_list();

-- ---------------------------------------------------------------------------
-- Maintenance
-- ---------------------------------------------------------------------------

create table house (
  id            uuid primary key default gen_random_uuid(),
  household_id  uuid not null references household(id) on delete cascade,
  name          text not null,
  address       text,
  build_year    int,
  notes         text,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  deleted_at    timestamptz,
  sync_seq      bigint not null default nextval('sync_seq_global')
);
call setup_scoped('house');

create table contractor (
  id            uuid primary key default gen_random_uuid(),
  household_id  uuid not null references household(id) on delete cascade,
  name          text not null,
  trade         text,
  phone         text,
  email         text,
  notes         text,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  deleted_at    timestamptz,
  sync_seq      bigint not null default nextval('sync_seq_global')
);
call setup_scoped('contractor');

create table asset (
  id              uuid primary key default gen_random_uuid(),
  household_id    uuid not null references household(id) on delete cascade,
  house_id        uuid references house(id),
  name            text not null,
  kind            text,                             -- boiler | hvac | roof | appliance ...
  brand           text,
  model           text,
  serial          text,
  room            text,
  purchase_date   date,
  purchase_price  numeric,
  warranty_until  date,
  qr_code         text unique default encode(gen_random_bytes(8), 'hex'),
  notes           text,
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  deleted_at      timestamptz,
  sync_seq        bigint not null default nextval('sync_seq_global')
);
call setup_scoped('asset');

create table maint_task (
  id            uuid primary key default gen_random_uuid(),
  household_id  uuid not null references household(id) on delete cascade,
  house_id      uuid references house(id),
  asset_id      uuid references asset(id),
  title         text not null,
  description   text,
  rrule         text,                               -- RFC 5545, null = one-off
  next_due      date,
  assigned_to   uuid references auth.users(id),
  season        text check (season in ('spring','summer','autumn','winter')),
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  deleted_at    timestamptz,
  sync_seq      bigint not null default nextval('sync_seq_global')
);
call setup_scoped('maint_task');
create index maint_task_due_idx on maint_task(household_id, next_due) where deleted_at is null;

alter table list_item add constraint list_item_task_fk
  foreign key (maint_task_id) references maint_task(id) on delete set null;

create table task_consumable (
  id            uuid primary key default gen_random_uuid(),
  household_id  uuid not null references household(id) on delete cascade,
  task_id       uuid not null references maint_task(id) on delete cascade,
  name          text not null,
  quantity      numeric,
  unit          text,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  deleted_at    timestamptz,
  sync_seq      bigint not null default nextval('sync_seq_global')
);
call setup_scoped('task_consumable');

create table maint_log (
  id            uuid primary key default gen_random_uuid(),
  household_id  uuid not null references household(id) on delete cascade,
  task_id       uuid references maint_task(id),
  asset_id      uuid references asset(id),
  done_at       date not null default current_date,
  done_by       uuid references auth.users(id),
  contractor_id uuid references contractor(id),
  cost          numeric,
  notes         text,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  deleted_at    timestamptz,
  sync_seq      bigint not null default nextval('sync_seq_global')
);
call setup_scoped('maint_log');
create index maint_log_asset_idx on maint_log(asset_id, done_at);

-- Seasonal/checklist templates: global rows have household_id null
create table checklist_template (
  id            uuid primary key default gen_random_uuid(),
  household_id  uuid references household(id) on delete cascade,
  name          text not null,
  season        text,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  deleted_at    timestamptz,
  sync_seq      bigint not null default nextval('sync_seq_global')
);
create table checklist_template_item (
  id            uuid primary key default gen_random_uuid(),
  household_id  uuid references household(id) on delete cascade,
  template_id   uuid not null references checklist_template(id) on delete cascade,
  title         text not null,
  rrule         text,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  deleted_at    timestamptz,
  sync_seq      bigint not null default nextval('sync_seq_global')
);
alter table checklist_template enable row level security;
alter table checklist_template_item enable row level security;
create policy tpl_select on checklist_template for select using (household_id is null or is_member(household_id));
create policy tpl_write on checklist_template for all using (household_id is not null and is_member(household_id));
create policy tpli_select on checklist_template_item for select using (household_id is null or is_member(household_id));
create policy tpli_write on checklist_template_item for all using (household_id is not null and is_member(household_id));

-- Rolling next_due is done in the API (needs an rrule library); keep the
-- "done" write path explicit: insert maint_log, then API updates next_due.

-- ---------------------------------------------------------------------------
-- Scheduled jobs (pg_cron) -> notification rows; Vercel cron delivers them
-- ---------------------------------------------------------------------------

create or replace function enqueue_daily_notifications() returns void
language plpgsql security definer set search_path = public as $$
begin
  -- eat soon (3 days)
  insert into notification(household_id, user_id, type, title, body, data)
  select p.household_id, m.user_id, 'eat_soon',
         'Eat soon', p.name || ' expires ' || to_char(p.expires_at, 'DD Mon'),
         jsonb_build_object('pantry_item_id', p.id)
  from pantry_item p
  join household h on h.id = p.household_id and h.module_pantry
  join member m on m.household_id = p.household_id and m.left_at is null
  left join notification_pref np on np.user_id = m.user_id and np.household_id = p.household_id and np.type = 'eat_soon'
  where p.deleted_at is null and p.expires_at between current_date and current_date + 3
    and coalesce(np.enabled, true)
    and not exists (select 1 from notification n where n.type='eat_soon' and n.user_id=m.user_id
                    and n.data->>'pantry_item_id' = p.id::text and n.created_at > now() - interval '3 days');

  -- tasks due today / overdue
  insert into notification(household_id, user_id, type, title, body, data)
  select t.household_id, m.user_id, 'task_due', 'Maintenance due', t.title,
         jsonb_build_object('task_id', t.id)
  from maint_task t
  join household h on h.id = t.household_id and h.module_maintenance
  join member m on m.household_id = t.household_id and m.left_at is null
       and (t.assigned_to is null or t.assigned_to = m.user_id)
  left join notification_pref np on np.user_id = m.user_id and np.household_id = t.household_id and np.type = 'task_due'
  where t.deleted_at is null and t.next_due <= current_date and coalesce(np.enabled, true)
    and not exists (select 1 from notification n where n.type='task_due' and n.user_id=m.user_id
                    and n.data->>'task_id' = t.id::text and n.created_at > now() - interval '1 day');

  -- warranty expiring in 30 days
  insert into notification(household_id, user_id, type, title, body, data)
  select a.household_id, m.user_id, 'warranty', 'Warranty ending', a.name || ' warranty ends ' || to_char(a.warranty_until, 'DD Mon'),
         jsonb_build_object('asset_id', a.id)
  from asset a
  join member m on m.household_id = a.household_id and m.left_at is null
  where a.deleted_at is null and a.warranty_until = current_date + 30;
end $$;

select cron.schedule('daily-notifications', '0 7 * * *', $$select enqueue_daily_notifications()$$);

-- Purge soft-deleted rows after 30 days (restore window)
create or replace function purge_soft_deleted() returns void
language plpgsql security definer set search_path = public as $$
declare t text;
begin
  foreach t in array array['list_item','shopping_list','pantry_item','maint_task','maint_log','asset',
                           'store','location','category','recipe','contractor','house','attachment']
  loop
    execute format('delete from %I where deleted_at < now() - interval ''30 days''', t);
  end loop;
end $$;
select cron.schedule('purge-soft-deleted', '0 3 * * *', $$select purge_soft_deleted()$$);

-- ---------------------------------------------------------------------------
-- Realtime: publish household-scoped tables so clients can subscribe by household_id
-- ---------------------------------------------------------------------------
alter publication supabase_realtime add table
  household, member, invite, activity, attachment,
  store, shopping_list, list_item, store_item_order, item_price,
  location, category, pantry_item, pantry_movement, recipe, recipe_ingredient,
  house, contractor, asset, maint_task, task_consumable, maint_log;
