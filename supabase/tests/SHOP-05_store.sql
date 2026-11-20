-- SHOP-05: a household creates stores and assigns a shopping list to one.
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
select set_config('test.s1', gen_random_uuid()::text, true);

-- AC1: a member creates a store with a client-generated id; it lands in their household only
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a1","role":"authenticated"}', true);
select lives_ok($$insert into public.store (id, household_id, name)
  values (current_setting('test.s1')::uuid, current_setting('test.home')::uuid, 'Lidl')$$,
  'SHOP-05: AC1 member inserts a store with a client id');
select results_eq($$select household_id, name from public.store where id = current_setting('test.s1')::uuid$$,
  $$select current_setting('test.home')::uuid, 'Lidl'::text$$,
  'SHOP-05: AC1 store reads back with Home household_id');
select throws_ok($$insert into public.store (household_id, name)
  values (current_setting('test.cabin')::uuid, 'Smuggled')$$,
  '42501', null, 'SHOP-05: AC1 insert into a household the user is not in is rejected');

-- AC2: a list can point at the store; unknown stores are rejected; purging the store clears the link
select lives_ok($$update public.shopping_list set store_id = current_setting('test.s1')::uuid
  where id = current_setting('test.list')::uuid$$,
  'SHOP-05: AC2 list assigned to the store');
select results_eq($$select s.name from public.shopping_list l join public.store s on s.id = l.store_id
  where l.id = current_setting('test.list')::uuid$$,
  $$values ('Lidl'::text)$$, 'SHOP-05: AC2 join returns the store name');
select throws_ok($$update public.shopping_list set store_id = gen_random_uuid()
  where id = current_setting('test.list')::uuid$$,
  '23503', null, 'SHOP-05: AC2 unknown store_id violates the FK');

-- AC3: a non-member cannot see or change the store
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a2","role":"authenticated"}', true);
select is_empty($$select 1 from public.store where id = current_setting('test.s1')::uuid$$,
  'SHOP-05: AC3 non-member sees no stores of Home');
select is_empty($$with u as (update public.store set name = 'Hijacked'
  where id = current_setting('test.s1')::uuid returning 1) select * from u$$,
  'SHOP-05: AC3 non-member update matches 0 rows');
reset role;
select is((select name from public.store where id = current_setting('test.s1')::uuid), 'Lidl',
  'SHOP-05: AC3 name unchanged after denied update');

-- AC2 (purge): hard delete as superuser, as the purge job does, nulls the list's store_id
delete from public.store where id = current_setting('test.s1')::uuid;
select is((select store_id from public.shopping_list where id = current_setting('test.list')::uuid), null,
  'SHOP-05: AC2 purging the store sets the list store_id to null');

select * from finish();
rollback;
