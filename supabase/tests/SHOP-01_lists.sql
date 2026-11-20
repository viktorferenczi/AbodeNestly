-- SHOP-01: a household keeps several shopping lists; archiving a list keeps the row.
-- No module-flag test here: module_disabled is enforced by /sync (X7-api), not by a trigger on this table.
begin;
create extension if not exists pgtap with schema extensions;
select plan(18);

insert into auth.users (id, email, aud, role) values
  ('00000000-0000-0000-0000-0000000000a1', 'a1@example.com', 'authenticated', 'authenticated'),
  ('00000000-0000-0000-0000-0000000000a2', 'a2@example.com', 'authenticated', 'authenticated'),
  ('00000000-0000-0000-0000-0000000000a3', 'a3@example.com', 'authenticated', 'authenticated');

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a1","role":"authenticated"}', true);
select set_config('test.home', public.create_household('Home')::text, true);
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a2","role":"authenticated"}', true);
select set_config('test.cabin', public.create_household('Cabin')::text, true);
reset role;

-- a3 was a member of Home and has left
insert into public.member (household_id, user_id, role, left_at)
  values (current_setting('test.home')::uuid, '00000000-0000-0000-0000-0000000000a3', 'member', now());

select set_config('test.l1', gen_random_uuid()::text, true);
select set_config('test.l2', gen_random_uuid()::text, true);

-- AC1: a member inserts lists into their household with client-generated ids
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a1","role":"authenticated"}', true);
select lives_ok($$insert into public.shopping_list (id, household_id, name)
  values (current_setting('test.l1')::uuid, current_setting('test.home')::uuid, 'Weekend trip')$$,
  'SHOP-01: AC1 member insert accepted');
select results_eq($$select household_id, archived_at is null, is_template from public.shopping_list
  where id = current_setting('test.l1')::uuid$$,
  $$values (current_setting('test.home')::uuid, true, false)$$,
  'SHOP-01: AC1 stored with Home household_id, not archived, not a template');
select throws_ok($$insert into public.shopping_list (household_id, name)
  values (current_setting('test.cabin')::uuid, 'Smuggled')$$,
  '42501', null, 'SHOP-01: AC1 insert into another household rejected');
select throws_ok($$update public.shopping_list set household_id = current_setting('test.cabin')::uuid
  where id = current_setting('test.l1')::uuid$$,
  '42501', null, 'SHOP-01: AC1 moving a list to another household rejected');
select lives_ok($$insert into public.shopping_list (id, household_id, name)
  values (current_setting('test.l1')::uuid, current_setting('test.home')::uuid, 'Weekend trip')
  on conflict (id) do update set name = excluded.name$$,
  'SHOP-01: AC1 replayed upsert accepted');
select is((select count(*)::int from public.shopping_list where id = current_setting('test.l1')::uuid), 1,
  'SHOP-01: AC1 replayed upsert leaves exactly one row');
select is((select count(*)::int from public.shopping_list where household_id = current_setting('test.home')::uuid), 2,
  'SHOP-01: AC1 household has more than one list (Groceries + Weekend trip)');

-- AC2: non-members and former members see nothing and change nothing
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a2","role":"authenticated"}', true);
select is_empty($$select 1 from public.shopping_list where household_id = current_setting('test.home')::uuid$$,
  'SHOP-01: AC2 non-member sees no lists of Home');
select is_empty($$with u as (update public.shopping_list set name = 'Hijacked'
  where household_id = current_setting('test.home')::uuid returning 1) select * from u$$,
  'SHOP-01: AC2 non-member update matches 0 rows');
select is_empty($$with d as (delete from public.shopping_list
  where household_id = current_setting('test.home')::uuid returning 1) select * from d$$,
  'SHOP-01: AC2 non-member delete matches 0 rows');
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a3","role":"authenticated"}', true);
select is_empty($$select 1 from public.shopping_list where household_id = current_setting('test.home')::uuid$$,
  'SHOP-01: AC2 former member sees no lists of Home');
reset role;
select results_eq($$select name from public.shopping_list where household_id = current_setting('test.home')::uuid order by name$$,
  $$values ('Groceries'::text), ('Weekend trip'::text)$$,
  'SHOP-01: AC2 names unchanged after denied writes');
select is((select count(*)::int from public.shopping_list where household_id = current_setting('test.home')::uuid), 2,
  'SHOP-01: AC2 count unchanged after denied writes');

-- AC3: archiving keeps the row and is distinguishable from soft delete
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a1","role":"authenticated"}', true);
select lives_ok($$insert into public.shopping_list (id, household_id, name)
  values (current_setting('test.l2')::uuid, current_setting('test.home')::uuid, 'Hardware store')$$,
  'SHOP-01: AC3 second list inserted');
select set_config('test.seq', (select sync_seq::text from public.shopping_list where id = current_setting('test.l1')::uuid), true);
update public.shopping_list set archived_at = now() where id = current_setting('test.l1')::uuid;
select is((select archived_at is not null and deleted_at is null from public.shopping_list
  where id = current_setting('test.l1')::uuid), true,
  'SHOP-01: AC3 archived list still visible with archived_at set and deleted_at null');
select cmp_ok((select sync_seq from public.shopping_list where id = current_setting('test.l1')::uuid),
  '>', current_setting('test.seq')::bigint, 'SHOP-01: AC3 archive bumped sync_seq');
select lives_ok($$update public.shopping_list set deleted_at = now() where id = current_setting('test.l2')::uuid$$,
  'SHOP-01: AC3 soft delete accepted');
select results_eq($$select archived_at is not null, deleted_at is not null from public.shopping_list
  where id in (current_setting('test.l1')::uuid, current_setting('test.l2')::uuid)
  order by archived_at is not null$$,
  $$values (false, true), (true, false)$$,
  'SHOP-01: AC3 deleted is (false,true), archived is (true,false)');
reset role;

select * from finish();
rollback;
