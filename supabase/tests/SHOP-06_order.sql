-- SHOP-06-db: learned aisle order rows per (store, name_key).
-- The running mean is written by X4-db's trigger; here a member writes rows directly, as a synced client does.
begin;
create extension if not exists pgtap with schema extensions;
select plan(10);

insert into auth.users (id, email, aud, role) values
  ('00000000-0000-0000-0000-0000000000a1', 'a1@example.com', 'authenticated', 'authenticated'),
  ('00000000-0000-0000-0000-0000000000a2', 'a2@example.com', 'authenticated', 'authenticated');

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a1","role":"authenticated"}', true);
select set_config('test.home', public.create_household('Home')::text, true);
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a2","role":"authenticated"}', true);
select set_config('test.cabin', public.create_household('Cabin')::text, true);
reset role;

select set_config('test.s1', gen_random_uuid()::text, true);
select set_config('test.s2', gen_random_uuid()::text, true);
insert into public.store (id, household_id, name) values
  (current_setting('test.s1')::uuid, current_setting('test.home')::uuid, 'Lidl'),
  (current_setting('test.s2')::uuid, current_setting('test.cabin')::uuid, 'Corner shop');

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a1","role":"authenticated"}', true);

-- AC1: a member upserts an order row; mean_position and samples are stored
select lives_ok($$insert into public.store_item_order (household_id, store_id, name_key, mean_position)
  values (current_setting('test.home')::uuid, current_setting('test.s1')::uuid, 'milk', 2)$$,
  'SHOP-06: AC1 member inserts an order row');
select lives_ok($$insert into public.store_item_order (household_id, store_id, name_key, mean_position, samples)
  values (current_setting('test.home')::uuid, current_setting('test.s1')::uuid, 'milk', 3, 2)
  on conflict (store_id, name_key) do update set mean_position = excluded.mean_position, samples = excluded.samples$$,
  'SHOP-06: AC1 member upserts the same (store, name_key)');
select results_eq($$select mean_position, samples from public.store_item_order
  where store_id = current_setting('test.s1')::uuid and name_key = 'milk'$$,
  $$values (3::numeric, 2)$$, 'SHOP-06: AC1 mean_position and samples are stored');

-- AC3: two name keys at one store coexist
select lives_ok($$insert into public.store_item_order (household_id, store_id, name_key, mean_position)
  values (current_setting('test.home')::uuid, current_setting('test.s1')::uuid, 'bread', 0)$$,
  'SHOP-06: AC3 second name key at the same store is accepted');
select is((select count(*)::int from public.store_item_order where store_id = current_setting('test.s1')::uuid), 2,
  'SHOP-06: AC3 both rows exist');

-- Guard: a row cannot point at a store of another household, nor be written into one
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a2","role":"authenticated"}', true);
select throws_ok($$insert into public.store_item_order (household_id, store_id, name_key, mean_position)
  values (current_setting('test.cabin')::uuid, current_setting('test.s1')::uuid, 'cheese', 1)$$,
  '23503', null, 'SHOP-06: a member cannot point a row at another household''s store');
select throws_ok($$insert into public.store_item_order (household_id, store_id, name_key, mean_position)
  values (current_setting('test.home')::uuid, current_setting('test.s1')::uuid, 'eggs', 1)$$,
  '42501', null, 'SHOP-06: a non-member cannot insert into another household');

-- AC2: a non-member sees nothing and changes nothing
select is_empty($$select 1 from public.store_item_order where store_id = current_setting('test.s1')::uuid$$,
  'SHOP-06: AC2 non-member sees no order rows of Home');
select is_empty($$with u as (update public.store_item_order set mean_position = 99
  where store_id = current_setting('test.s1')::uuid returning 1) select * from u$$,
  'SHOP-06: AC2 non-member update matches 0 rows');
reset role;

-- Cascade: purging a store removes its order rows
delete from public.store where id = current_setting('test.s1')::uuid;
select is((select count(*)::int from public.store_item_order where store_id = current_setting('test.s1')::uuid), 0,
  'SHOP-06: deleting a store deletes its order rows');

select * from finish();
rollback;
