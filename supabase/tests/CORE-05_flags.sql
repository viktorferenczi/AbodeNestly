-- CORE-05: module flags (and every other household column) are owner-writable only.
-- A member's write raises 42501; a non-member (incl. a former owner) sees no row, so 0 rows match.
begin;
create extension if not exists pgtap with schema extensions;
select plan(14);

insert into auth.users (id, email, aud, role) values
  ('00000000-0000-0000-0000-0000000000a1', 'a1@example.com', 'authenticated', 'authenticated'),
  ('00000000-0000-0000-0000-0000000000a2', 'a2@example.com', 'authenticated', 'authenticated'),
  ('00000000-0000-0000-0000-0000000000a3', 'a3@example.com', 'authenticated', 'authenticated'),
  ('00000000-0000-0000-0000-0000000000a4', 'a4@example.com', 'authenticated', 'authenticated');

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a1","role":"authenticated"}', true);
select set_config('test.hid', public.create_household('Home')::text, true);
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a3","role":"authenticated"}', true);
select set_config('test.hid2', public.create_household('Cabin')::text, true);
reset role;

insert into public.member (household_id, user_id, role) values
  (current_setting('test.hid')::uuid, '00000000-0000-0000-0000-0000000000a2', 'member'),
  (current_setting('test.hid')::uuid, '00000000-0000-0000-0000-0000000000a4', 'owner');

-- AC1: owner flips a flag
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a1","role":"authenticated"}', true);
select lives_ok($$update public.household set module_pantry = false where id = current_setting('test.hid')::uuid$$,
  'CORE-05: AC1 owner can disable a module');
select results_eq($$select module_pantry, module_shopping, module_maintenance from public.household
  where id = current_setting('test.hid')::uuid$$, $$values (false, true, true)$$,
  'CORE-05: AC1 only the pantry flag changed');
select lives_ok($$update public.household set module_pantry = false where id = current_setting('test.hid')::uuid$$,
  'CORE-05: AC1 replay is accepted');
select is((select module_pantry from public.household where id = current_setting('test.hid')::uuid), false,
  'CORE-05: AC1 replay is idempotent');

-- AC2: a plain member is rejected loudly
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a2","role":"authenticated"}', true);
select throws_ok($$update public.household set module_shopping = false where id = current_setting('test.hid')::uuid$$,
  '42501', 'household_owner_only', 'CORE-05: AC2 member cannot change a flag');
select throws_ok($$update public.household set name = 'Mine' where id = current_setting('test.hid')::uuid$$,
  '42501', 'household_owner_only', 'CORE-05: AC2 member cannot rename the household');
reset role;
select results_eq($$select module_shopping, name from public.household where id = current_setting('test.hid')::uuid$$,
  $$values (true, 'Home')$$, 'CORE-05: AC2 member attempts changed nothing');

-- A former owner is no longer a member: the row is invisible, so nothing matches
update public.member set left_at = now()
  where household_id = current_setting('test.hid')::uuid and user_id = '00000000-0000-0000-0000-0000000000a4';
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a4","role":"authenticated"}', true);
update public.household set module_maintenance = false where id = current_setting('test.hid')::uuid;
reset role;
select is((select module_maintenance from public.household where id = current_setting('test.hid')::uuid), true,
  'CORE-05: a former owner cannot change flags');

-- AC3: an outsider (a4 is now outside hid) sees nothing and changes nothing; a3 owns hid2 only
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a3","role":"authenticated"}', true);
select is((select count(*)::int from public.household where id = current_setting('test.hid')::uuid), 0,
  'CORE-05: AC3 outsider cannot see the household');
update public.household set module_shopping = false where id = current_setting('test.hid')::uuid;
-- Cross-household: a1 cannot see or update hid2
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a1","role":"authenticated"}', true);
select is((select count(*)::int from public.household where id = current_setting('test.hid2')::uuid), 0,
  'CORE-05: a1 cannot see another household');
update public.household set module_shopping = false where id = current_setting('test.hid2')::uuid;
reset role;
select is((select module_shopping from public.household where id = current_setting('test.hid')::uuid), true,
  'CORE-05: AC3 outsider update left flags unchanged');
select is((select module_shopping from public.household where id = current_setting('test.hid2')::uuid), true,
  'CORE-05: cross-household update left hid2 unchanged');

-- Service role (no authenticated role): the guard returns early
select lives_ok($$update public.household set module_shopping = false where id = current_setting('test.hid')::uuid$$,
  'CORE-05: service role can update flags');
select is((select module_shopping from public.household where id = current_setting('test.hid')::uuid), false,
  'CORE-05: service role update applied');

select * from finish();
rollback;
