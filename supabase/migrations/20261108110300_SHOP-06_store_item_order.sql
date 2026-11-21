-- SHOP-06-db: learned aisle order, one row per (store, name_key). The running mean is written by
-- X4-db's list_item_learn trigger; this migration only creates the table.
--
-- Guard beyond docs/schema.sql before this story: the FK is composite on (store_id, household_id).
-- RLS only checks that the caller is a member of the row's household, so a plain store_id FK would let
-- a member insert a row that points at another household's store. store gets a unique (id, household_id)
-- so the composite FK can reference it. Only this table is guarded; other tables keep the plain pattern.

alter table store add constraint store_id_household_uk unique (id, household_id);

create table store_item_order (
  household_id  uuid not null references household(id) on delete cascade,
  store_id      uuid not null,
  name_key      text not null,
  mean_position numeric not null,
  samples       int not null default 1,
  updated_at    timestamptz not null default now(),
  sync_seq      bigint not null default nextval('sync_seq_global'),
  primary key (store_id, name_key),
  foreign key (store_id, household_id) references store(id, household_id) on delete cascade
);
call setup_scoped('store_item_order');

-- SHOP-09 asked for this table to be published; publish_realtime skips tables already published.
call publish_realtime('store_item_order');
