-- SHOP-03-db: list_item with optional quantity, unit and note (all nullable, no defaults).
-- There is no SHOP-02-db story, so this migration creates the table, following docs/schema.sql.
-- Foreign keys left out because their targets do not exist yet:
--   barcode        -> product(barcode)   : added by the product cache story
--   pantry_item_id -> pantry_item(id)    : added with pantry_item (schema.sql: list_item_pantry_fk)
--   maint_task_id  -> maint_task(id)     : added with maint_task (schema.sql: list_item_task_fk)
-- Not here: learn_store_order trigger / store_item_order (SHOP-06, needs store), check-off logic (SHOP-04).

create table list_item (
  id              uuid primary key default gen_random_uuid(),
  household_id    uuid not null references household(id) on delete cascade,
  list_id         uuid not null references shopping_list(id) on delete cascade,
  name            text not null,
  name_key        text generated always as (name_key(name)) stored,
  quantity        numeric,
  unit            text,
  note            text,
  barcode         text,
  checked         boolean not null default false,
  checked_at      timestamptz,
  checked_by      uuid references auth.users(id),
  manual_position int,
  source          text not null default 'manual'
                  check (source in ('manual','pantry','maintenance','template','recipe')),
  pantry_item_id  uuid,
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

alter publication supabase_realtime add table list_item;
