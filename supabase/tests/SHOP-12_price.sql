-- SHOP-12-db: item_price rows per (store, name_key), written directly by a member as a synced client does.
begin;
create extension if not exists pgtap with schema extensions;
select plan(14);

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

-- AC1: a member inserts a price; the numeric price and seen_at are stored
select lives_ok($$insert into public.item_price (household_id, store_id, name_key, price, seen_at)
  values (current_setting('test.home')::uuid, current_setting('test.s1')::uuid, 'milk', 1.20, '2026-11-03T10:00:00Z')$$,
  'SHOP-12: AC1 member inserts a price');
select results_eq($$select price, seen_at from public.item_price
  where store_id = current_setting('test.s1')::uuid and name_key = 'milk'$$,
  $$values (1.20::numeric, '2026-11-03T10:00:00Z'::timestamptz)$$, 'SHOP-12: AC1 price and seen_at are stored');

-- AC2: a second price for the same store and name coexists; the newer seen_at is distinguishable
select lives_ok($$insert into public.item_price (household_id, store_id, name_key, price, seen_at)
  values (current_setting('test.home')::uuid, current_setting('test.s1')::uuid, 'milk', 1.35, '2026-11-20T10:00:00Z')$$,
  'SHOP-12: AC2 second price for the same store and name is accepted');
select is((select count(*)::int from public.item_price
  where store_id = current_setting('test.s1')::uuid and name_key = 'milk'), 2, 'SHOP-12: AC2 both prices exist');
select is((select price from public.item_price
  where store_id = current_setting('test.s1')::uuid and name_key = 'milk' order by seen_at desc limit 1), 1.35::numeric,
  'SHOP-12: AC2 the newest seen_at is the latest price');

-- name_key is normalised like list_item.name_key
select lives_ok($$insert into public.item_price (household_id, store_id, name_key, price)
  values (current_setting('test.home')::uuid, current_setting('test.s1')::uuid, '  Whole   Milk ', 1.5)$$,
  'SHOP-12: a messy name_key is accepted');
select is((select count(*)::int from public.item_price where name_key = 'whole milk'), 1,
  'SHOP-12: name_key is stored normalised');

-- Checks
select throws_ok($$insert into public.item_price (household_id, store_id, name_key, price)
  values (current_setting('test.home')::uuid, current_setting('test.s1')::uuid, 'bread', -1)$$,
  '23514', null, 'SHOP-12: a negative price is rejected');
select throws_ok($$insert into public.item_price (household_id, store_id, name_key, price)
  values (current_setting('test.home')::uuid, current_setting('test.s1')::uuid, '   ', 1)$$,
  '23514', null, 'SHOP-12: an empty name_key is rejected');

-- sync_seq is set and moves forward on update
select set_config('test.seq', (select sync_seq::text from public.item_price where name_key = 'whole milk'), true);
update public.item_price set price = 1.6 where name_key = 'whole milk';
select cmp_ok((select sync_seq from public.item_price where name_key = 'whole milk'), '>', current_setting('test.seq')::bigint,
  'SHOP-12: sync_seq increases on update');

-- Guard: a price cannot point at another household's store, nor be written into one
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a2","role":"authenticated"}', true);
select throws_ok($$insert into public.item_price (household_id, store_id, name_key, price)
  values (current_setting('test.cabin')::uuid, current_setting('test.s1')::uuid, 'cheese', 1)$$,
  '23503', null, 'SHOP-12: a member cannot point a price at another household''s store');
select throws_ok($$insert into public.item_price (household_id, store_id, name_key, price)
  values (current_setting('test.home')::uuid, current_setting('test.s1')::uuid, 'eggs', 1)$$,
  '42501', null, 'SHOP-12: a non-member cannot insert into another household');

-- AC3: a non-member sees nothing
select is_empty($$select 1 from public.item_price where household_id = current_setting('test.home')::uuid$$,
  'SHOP-12: AC3 non-member sees no prices of Home');
reset role;

select ok(exists (select 1 from pg_publication_tables
  where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = 'item_price'),
  'SHOP-12: item_price is in the realtime publication');

select * from finish();
rollback;
