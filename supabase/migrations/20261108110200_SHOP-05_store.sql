-- SHOP-05-db: store (a shop a household buys from) and the FK from shopping_list.store_id.
-- Scope: this migration only creates store and the FK. SHOP-06 adds store_item_order and learn_store_order.
-- purge_soft_deleted() (CORE-10) already lists 'store'; it starts purging as soon as the table exists.
--
-- Deviation from docs/schema.sql before this story: the FK is `on delete set null`. A live list may
-- reference a soft-deleted store; with a plain FK the 30-day purge's `delete from store` would fail
-- and abort the whole purge job. set null turns a purged store into "no store" on the list.

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
create index store_household_name_idx on store(household_id, name) where deleted_at is null;  -- stores list screen

alter table shopping_list
  add constraint shopping_list_store_fk foreign key (store_id) references store(id) on delete set null;

alter publication supabase_realtime add table store;
