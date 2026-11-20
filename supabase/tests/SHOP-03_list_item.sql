-- SHOP-03: a list line carries an optional quantity, unit and note.
-- No module-flag test here: module_disabled is enforced by /sync (X7-api), not by a trigger on this table.
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

select set_config('test.list', (select id::text from public.shopping_list
  where household_id = current_setting('test.home')::uuid and name = 'Groceries'), true);
select set_config('test.i1', gen_random_uuid()::text, true);

-- AC1: quantity, unit and note are optional
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a1","role":"authenticated"}', true);
select lives_ok($$insert into public.list_item (id, household_id, list_id, name)
  values (current_setting('test.i1')::uuid, current_setting('test.home')::uuid, current_setting('test.list')::uuid, 'Milk')$$,
  'SHOP-03: AC1 item without quantity, unit, note accepted');
select results_eq($$select quantity is null, unit is null, note is null from public.list_item
  where id = current_setting('test.i1')::uuid$$,
  $$values (true, true, true)$$,
  'SHOP-03: AC1 quantity, unit and note are all null');

-- AC2: they can be set and round-trip
select set_config('test.seq', (select sync_seq::text from public.list_item where id = current_setting('test.i1')::uuid), true);
select lives_ok($$update public.list_item set quantity = 2, unit = 'l', note = 'semi-skimmed'
  where id = current_setting('test.i1')::uuid$$,
  'SHOP-03: AC2 update with quantity, unit, note accepted');
select results_eq($$select quantity, unit, note from public.list_item where id = current_setting('test.i1')::uuid$$,
  $$values (2::numeric, 'l'::text, 'semi-skimmed'::text)$$,
  'SHOP-03: AC2 values read back');
select cmp_ok((select sync_seq from public.list_item where id = current_setting('test.i1')::uuid),
  '>', current_setting('test.seq')::bigint, 'SHOP-03: AC2 update bumped sync_seq');
select lives_ok($$insert into public.list_item (id, household_id, list_id, name, quantity, unit, note)
  values (current_setting('test.i1')::uuid, current_setting('test.home')::uuid, current_setting('test.list')::uuid,
    'Milk', 2, 'l', 'semi-skimmed')
  on conflict (id) do update set quantity = excluded.quantity, unit = excluded.unit, note = excluded.note$$,
  'SHOP-03: AC2 replayed upsert accepted');
select is((select count(*)::int from public.list_item where id = current_setting('test.i1')::uuid), 1,
  'SHOP-03: AC2 replayed upsert leaves exactly one row');

-- AC3: a non-member cannot change or add items
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a2","role":"authenticated"}', true);
select is_empty($$with u as (update public.list_item set quantity = 99
  where id = current_setting('test.i1')::uuid returning 1) select * from u$$,
  'SHOP-03: AC3 non-member update matches 0 rows');
select throws_ok($$insert into public.list_item (household_id, list_id, name)
  values (current_setting('test.home')::uuid, current_setting('test.list')::uuid, 'Smuggled')$$,
  '42501', null, 'SHOP-03: AC3 non-member insert into Home rejected');
reset role;
select results_eq($$select quantity, unit, note from public.list_item where id = current_setting('test.i1')::uuid$$,
  $$values (2::numeric, 'l'::text, 'semi-skimmed'::text)$$,
  'SHOP-03: AC3 values unchanged after denied writes');

select * from finish();
rollback;
