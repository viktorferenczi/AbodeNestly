-- CORE-04: the last active owner cannot leave or be demoted. Membership is core: no module flag applies.
begin;
create extension if not exists pgtap with schema extensions;
select plan(13);

insert into auth.users (id, email, aud, role) values
  ('00000000-0000-0000-0000-0000000000a1', 'a1@example.com', 'authenticated', 'authenticated'),
  ('00000000-0000-0000-0000-0000000000a2', 'a2@example.com', 'authenticated', 'authenticated'),
  ('00000000-0000-0000-0000-0000000000a3', 'a3@example.com', 'authenticated', 'authenticated'),
  ('00000000-0000-0000-0000-0000000000a4', 'a4@example.com', 'authenticated', 'authenticated');

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a1","role":"authenticated"}', true);
select set_config('test.hid', public.create_household('Home')::text, true);
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a4","role":"authenticated"}', true);
select set_config('test.hid2', public.create_household('Cabin')::text, true);
reset role;

insert into public.member (household_id, user_id, role) values
  (current_setting('test.hid')::uuid, '00000000-0000-0000-0000-0000000000a2', 'owner'),
  (current_setting('test.hid')::uuid, '00000000-0000-0000-0000-0000000000a3', 'member');

-- AC1: a second owner leaves
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a2","role":"authenticated"}', true);
select lives_ok($$update public.member set left_at = now()
  where household_id = current_setting('test.hid')::uuid and user_id = '00000000-0000-0000-0000-0000000000a2'$$,
  'CORE-04: AC1 second owner can leave');
reset role;
select isnt((select left_at from public.member where household_id = current_setting('test.hid')::uuid
  and user_id = '00000000-0000-0000-0000-0000000000a2'), null, 'CORE-04: AC1 a2 left_at set');
select is((select left_at from public.member where household_id = current_setting('test.hid')::uuid
  and user_id = '00000000-0000-0000-0000-0000000000a1'), null, 'CORE-04: AC1 a1 still active owner');

-- Replay of the same leave is a no-op, not an error
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a2","role":"authenticated"}', true);
select lives_ok($$update public.member set left_at = now()
  where household_id = current_setting('test.hid')::uuid and user_id = '00000000-0000-0000-0000-0000000000a2'$$,
  'CORE-04: replayed leave does not error');

-- AC2: a1 is now the sole owner
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a1","role":"authenticated"}', true);
select throws_ok($$update public.member set left_at = now()
  where household_id = current_setting('test.hid')::uuid and user_id = '00000000-0000-0000-0000-0000000000a1'$$,
  'P0001', 'last_owner', 'CORE-04: AC2 sole owner cannot leave');
select throws_ok($$update public.member set role = 'member'
  where household_id = current_setting('test.hid')::uuid and user_id = '00000000-0000-0000-0000-0000000000a1'$$,
  'P0001', 'last_owner', 'CORE-04: sole owner cannot demote themselves');
reset role;
select is((select left_at from public.member where household_id = current_setting('test.hid')::uuid
  and user_id = '00000000-0000-0000-0000-0000000000a1'), null, 'CORE-04: AC2 a1 left_at still null');

-- AC3: owner removes a plain member
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a1","role":"authenticated"}', true);
select lives_ok($$update public.member set left_at = now()
  where household_id = current_setting('test.hid')::uuid and user_id = '00000000-0000-0000-0000-0000000000a3'$$,
  'CORE-04: AC3 owner removes a member');
-- Cross-household: a1 cannot touch hid2's member (RLS hides the row)
update public.member set left_at = now()
  where household_id = current_setting('test.hid2')::uuid and user_id = '00000000-0000-0000-0000-0000000000a4';
reset role;
select isnt((select left_at from public.member where household_id = current_setting('test.hid')::uuid
  and user_id = '00000000-0000-0000-0000-0000000000a3'), null, 'CORE-04: AC3 a3 left_at set');
select is((select left_at from public.member where household_id = current_setting('test.hid2')::uuid
  and user_id = '00000000-0000-0000-0000-0000000000a4'), null, 'CORE-04: cross-household row unchanged');

-- AC4: a non-owner cannot change another member's row (0 rows affected)
update public.member set left_at = null
  where household_id = current_setting('test.hid')::uuid and user_id = '00000000-0000-0000-0000-0000000000a3';
insert into public.member (household_id, user_id, role) values
  (current_setting('test.hid')::uuid, '00000000-0000-0000-0000-0000000000a2', 'member')
  on conflict (household_id, user_id) do update set left_at = null, role = 'member';
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a2","role":"authenticated"}', true);
update public.member set left_at = now()
  where household_id = current_setting('test.hid')::uuid and user_id = '00000000-0000-0000-0000-0000000000a1';
reset role;
select is((select left_at from public.member where household_id = current_setting('test.hid')::uuid
  and user_id = '00000000-0000-0000-0000-0000000000a1'), null, 'CORE-04: AC4 non-owner cannot remove another member');

-- Owner removes owner: allowed while another owner remains, then rejected for the last
update public.member set role = 'owner'
  where household_id = current_setting('test.hid')::uuid and user_id = '00000000-0000-0000-0000-0000000000a2';
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a1","role":"authenticated"}', true);
select lives_ok($$update public.member set left_at = now()
  where household_id = current_setting('test.hid')::uuid and user_id = '00000000-0000-0000-0000-0000000000a2'$$,
  'CORE-04: owner removes another owner while one remains');

-- Service role (no authenticated role) is bound too
reset role;
select throws_ok($$update public.member set left_at = now()
  where household_id = current_setting('test.hid')::uuid and user_id = '00000000-0000-0000-0000-0000000000a1'$$,
  'P0001', 'last_owner', 'CORE-04: service role cannot remove the last owner either');

select * from finish();
rollback;
