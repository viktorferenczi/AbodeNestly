-- SHOP-10: global barcode product cache. Authenticated users read it; only the service role writes it.
begin;
create extension if not exists pgtap with schema extensions;
select plan(10);

insert into auth.users (id, email, aud, role) values
  ('00000000-0000-0000-0000-0000000000a1', 'a1@example.com', 'authenticated', 'authenticated');

-- seeded as superuser, the way the API's service role would fill the cache
insert into public.product (barcode, name, brand, source)
  values ('5000000000001', 'Oat milk', 'Oatly', 'openfoodfacts');

-- AC1: an authenticated user reads a cached product
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a1","role":"authenticated"}', true);
select results_eq($$select name from public.product where barcode = '5000000000001'$$,
  $$values ('Oat milk'::text)$$,
  'SHOP-10: AC1 authenticated user reads a cached product');

-- AC2: an authenticated user cannot write the cache
select throws_ok($$insert into public.product (barcode, name) values ('5000000000002', 'Fake')$$,
  '42501', 'new row violates row-level security policy for table "product"',
  'SHOP-10: AC2 authenticated insert is rejected by RLS');
select lives_ok($$update public.product set name = 'Hacked' where barcode = '5000000000001'$$,
  'SHOP-10: AC2 authenticated update runs');
reset role;
select is((select name from public.product where barcode = '5000000000001'), 'Oat milk',
  'SHOP-10: AC2 authenticated update changed no rows');

-- AC3: the service role upserts new and existing barcodes
set local role service_role;
select lives_ok($$insert into public.product (barcode, name, source) values ('5000000000003', 'Rye bread', 'openfoodfacts')
  on conflict (barcode) do update set name = excluded.name, fetched_at = now()$$,
  'SHOP-10: AC3 service role inserts a new barcode');
select lives_ok($$insert into public.product (barcode, name, source) values ('5000000000001', 'Oat drink', 'openfoodfacts')
  on conflict (barcode) do update set name = excluded.name, fetched_at = now()$$,
  'SHOP-10: AC3 service role refreshes an existing barcode');
reset role;
select results_eq($$select barcode, name from public.product order by barcode$$,
  $$values ('5000000000001'::text, 'Oat drink'::text), ('5000000000003'::text, 'Rye bread'::text)$$,
  'SHOP-10: AC3 both rows stored and the existing name updated');

-- anon sees nothing
set local role anon;
select set_config('request.jwt.claims', '{"role":"anon"}', true);
select is_empty($$select 1 from public.product$$, 'SHOP-10: anon reads no products');
reset role;

-- list_item.barcode must reference a cached product
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a1","role":"authenticated"}', true);
select set_config('test.home', public.create_household('Home')::text, true);
reset role;
select set_config('test.list', (select id::text from public.shopping_list
  where household_id = current_setting('test.home')::uuid and name = 'Groceries'), true);
select lives_ok($$insert into public.list_item (household_id, list_id, name, barcode)
  values (current_setting('test.home')::uuid, current_setting('test.list')::uuid, 'Oat drink', '5000000000001')$$,
  'SHOP-10: list_item with a cached barcode is accepted');
select throws_ok($$insert into public.list_item (household_id, list_id, name, barcode)
  values (current_setting('test.home')::uuid, current_setting('test.list')::uuid, 'Mystery', '9999999999999')$$,
  '23503', null, 'SHOP-10: list_item with an unknown barcode is rejected by the FK');

select * from finish();
rollback;
