-- SHOP-12-db: price memory. One row per price seen for a (store, name_key); history is kept (no unique
-- key), the newest seen_at is the "last price" clients show next to an item.
--
-- Guards beyond the baseline docs/schema.sql definition:
--  * price >= 0 and name_key <> ''.
--  * Composite FK on (store_id, household_id), as store_item_order does (SHOP-06): RLS only checks the
--    caller's membership of the row's household, so a plain store_id FK would let a member point a price
--    at another household's store.
--  * item_price has no name column, so clients write name_key themselves. A trigger normalises it with
--    name_key() so it always matches list_item.name_key, whatever a client sends.

create table item_price (
  id            uuid primary key default gen_random_uuid(),
  household_id  uuid not null references household(id) on delete cascade,
  store_id      uuid not null,
  name_key      text not null check (name_key <> ''),
  price         numeric not null check (price >= 0),
  seen_at       timestamptz not null default now(),
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  deleted_at    timestamptz,
  sync_seq      bigint not null default nextval('sync_seq_global'),
  foreign key (store_id, household_id) references store(id, household_id) on delete cascade
);

create or replace function normalize_item_price_key() returns trigger
language plpgsql as $$
begin
  new.name_key := name_key(new.name_key);
  return new;
end $$;
create trigger item_price_name_key before insert or update on item_price
  for each row execute function normalize_item_price_key();

call setup_scoped('item_price');

-- "Last price at this store" read: newest live row per (store, name_key).
create index item_price_last_idx on item_price (store_id, name_key, seen_at desc) where deleted_at is null;

call publish_realtime('item_price');

-- SPEC §5: soft-deleted household rows are purged after 30 days. Same function as CORE-10 with
-- 'item_price' added; it comes before 'store' because its composite FK points at store.
create or replace function purge_soft_deleted() returns void
language plpgsql security definer set search_path = public as $$
declare t text;
begin
  foreach t in array array['list_item','shopping_list','pantry_item','maint_log','maint_task','asset',
                           'item_price','store','location','category','recipe','contractor','house','attachment']
  loop
    continue when to_regclass(t) is null;
    execute format('delete from %I where deleted_at < now() - interval ''30 days''', t);
  end loop;
end $$;
revoke execute on function purge_soft_deleted() from public, anon, authenticated, service_role;
