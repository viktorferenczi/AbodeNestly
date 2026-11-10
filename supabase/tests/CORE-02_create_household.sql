-- CORE-02: create_household seeds owner, Groceries list and default locations
begin;
create extension if not exists pgtap with schema extensions;
select plan(18);

insert into auth.users (id, email, aud, role) values
  ('00000000-0000-0000-0000-0000000000a1', 'anna@example.com', 'authenticated', 'authenticated'),
  ('00000000-0000-0000-0000-0000000000a2', 'bence@example.com', 'authenticated', 'authenticated');

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a1","role":"authenticated"}', true);
select set_config('test.hid', public.create_household('Home')::text, true);

-- AC1: household + single owner member
select is((select created_by from public.household where id = current_setting('test.hid')::uuid),
  '00000000-0000-0000-0000-0000000000a1'::uuid, 'AC1: household created_by is the caller');
select is((select count(*)::int from public.member where household_id = current_setting('test.hid')::uuid),
  1, 'AC1: exactly one member');
select results_eq(
  $$select user_id, role from public.member where household_id = current_setting('test.hid')::uuid$$,
  $$values ('00000000-0000-0000-0000-0000000000a1'::uuid, 'owner'::text)$$,
  'AC1: the member is the caller, as owner');

-- AC2: one Groceries list
select is((select count(*)::int from public.shopping_list where household_id = current_setting('test.hid')::uuid),
  1, 'AC2: exactly one list');
select results_eq(
  $$select name, is_template, archived_at is null, deleted_at is null
    from public.shopping_list where household_id = current_setting('test.hid')::uuid$$,
  $$values ('Groceries'::text, false, true, true)$$,
  'AC2: Groceries, not template, not archived, not deleted');

-- AC3: default locations
select results_eq(
  $$select name, kind from public.location where household_id = current_setting('test.hid')::uuid order by name$$,
  $$values ('Freezer'::text, 'freezer'::text), ('Fridge', 'fridge'), ('Pantry', 'pantry')$$,
  'AC3: Fridge, Freezer and Pantry exist with matching kinds');

-- Extra: a second call is independent, no cross-seeding
select set_config('test.hid2', public.create_household('Cabin')::text, true);
select isnt(current_setting('test.hid2'), current_setting('test.hid'), 'second household is a new row');
select is((select count(*)::int from public.shopping_list where household_id = current_setting('test.hid2')::uuid),
  1, 'second household has its own single Groceries list');
select is((select count(*)::int from public.location where household_id = current_setting('test.hid2')::uuid),
  3, 'second household has its own three locations');
select is((select count(*)::int from public.shopping_list where household_id = current_setting('test.hid')::uuid),
  1, 'first household is unchanged by the second call');
select is((select count(*)::int from public.location where household_id = current_setting('test.hid')::uuid),
  3, 'first household locations unchanged by the second call');

-- AC4: stranger sees nothing
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a2","role":"authenticated"}', true);
select is_empty($$select 1 from public.household where id = current_setting('test.hid')::uuid$$,
  'AC4: stranger cannot read the household');
select is_empty($$select 1 from public.shopping_list where household_id = current_setting('test.hid')::uuid$$,
  'AC4: stranger cannot read the lists');
select is_empty($$select 1 from public.location where household_id = current_setting('test.hid')::uuid$$,
  'AC4: stranger cannot read the locations');

-- Extra: unauthenticated call (no sub claim) is rejected and creates nothing
select set_config('request.jwt.claims', '{"role":"authenticated"}', true);
select throws_ok($$select public.create_household('Ghost')$$, '42501', 'not_authenticated',
  'unauthenticated call raises not_authenticated');

reset role;
select is((select count(*)::int from public.household where name = 'Ghost'), 0,
  'unauthenticated call created no household');
select is((select count(*)::int from public.household where name in ('Home','Cabin')), 2,
  'only the two authenticated calls created households');
select is((select count(*)::int from public.shopping_list), 2, 'two Groceries lists in total');
select * from finish();
rollback;
