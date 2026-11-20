-- SHOP-10-db: global barcode product cache, following docs/schema.sql.
-- Global, not household-scoped (SPEC §11): no household_id / sync_seq, no setup_scoped,
-- not in the Realtime publication or lib/sync/tables.ts, and not gated by the shopping flag.
-- Readable by authenticated users; written only by the API with the service role (bypasses RLS).
-- Also adds list_item.barcode -> product(barcode), deferred here by SHOP-03-db.
-- The pantry_item barcode FK comes with pantry_item.

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

alter table list_item add constraint list_item_barcode_fk
  foreign key (barcode) references product(barcode);
