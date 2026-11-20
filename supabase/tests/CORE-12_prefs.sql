-- CORE-12: notification_pref is self-only. A missing row means enabled (the §14 job coalesces to true).
-- a1 and a2 are members of hid; a3 owns hid2.
begin;
create extension if not exists pgtap with schema extensions;
select plan(19);

insert into auth.users (id, email, aud, role) values
  ('00000000-0000-0000-0000-0000000000a1', 'a1@example.com', 'authenticated', 'authenticated'),
  ('00000000-0000-0000-0000-0000000000a2', 'a2@example.com', 'authenticated', 'authenticated'),
  ('00000000-0000-0000-0000-0000000000a3', 'a3@example.com', 'authenticated', 'authenticated');

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a1","role":"authenticated"}', true);
select set_config('test.hid', public.create_household('Home')::text, true);
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a3","role":"authenticated"}', true);
select set_config('test.hid2', public.create_household('Cabin')::text, true);
reset role;

insert into public.member (household_id, user_id, role) values
  (current_setting('test.hid')::uuid, '00000000-0000-0000-0000-0000000000a2', 'member');

-- The §14 job's join, for one user and type (runs as the job does: not subject to RLS).
create function pg_temp.job_allows(uid uuid, t text) returns boolean language sql as $$
  select coalesce(np.enabled, true)
  from public.member m
  left join public.notification_pref np
    on np.user_id = m.user_id and np.household_id = m.household_id and np.type = t
  where m.household_id = current_setting('test.hid')::uuid and m.user_id = uid and m.left_at is null
$$;

-- AC1: no row means allowed
select is(pg_temp.job_allows('00000000-0000-0000-0000-0000000000a2', 'eat_soon'), true,
  'CORE-12: AC1 member without a row is allowed');

-- AC2: a1 turns eat_soon off
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a1","role":"authenticated"}', true);
select lives_ok($$insert into public.notification_pref (user_id, household_id, type, enabled)
  values ('00000000-0000-0000-0000-0000000000a1', current_setting('test.hid')::uuid, 'eat_soon', false)
  on conflict (user_id, household_id, type) do update set enabled = excluded.enabled$$,
  'CORE-12: AC2 user upserts own preference');
select is((select enabled from public.notification_pref where type = 'eat_soon'
  and household_id = current_setting('test.hid')::uuid), false, 'CORE-12: AC2 row reads enabled = false');
select set_config('test.seq', (select sync_seq::text from public.notification_pref
  where type = 'eat_soon' and household_id = current_setting('test.hid')::uuid), true);
select lives_ok($$insert into public.notification_pref (user_id, household_id, type, enabled)
  values ('00000000-0000-0000-0000-0000000000a1', current_setting('test.hid')::uuid, 'eat_soon', false)
  on conflict (user_id, household_id, type) do update set enabled = excluded.enabled$$,
  'CORE-12: AC2 replay is accepted');
select is((select count(*)::int from public.notification_pref where type = 'eat_soon'
  and household_id = current_setting('test.hid')::uuid), 1, 'CORE-12: AC2 replay leaves one row');
select ok((select sync_seq from public.notification_pref where type = 'eat_soon'
  and household_id = current_setting('test.hid')::uuid) > current_setting('test.seq')::bigint,
  'CORE-12: AC2 replay bumps sync_seq');
reset role;
select is(pg_temp.job_allows('00000000-0000-0000-0000-0000000000a1', 'eat_soon'), false,
  'CORE-12: AC2 the job join now skips a1');

-- a2 stores a preference of their own (used below)
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a2","role":"authenticated"}', true);
insert into public.notification_pref (user_id, household_id, type, enabled)
  values ('00000000-0000-0000-0000-0000000000a2', current_setting('test.hid')::uuid, 'low_stock', false);

-- AC3: a2 cannot touch a1's row
select is_empty($$update public.notification_pref set enabled = true
  where user_id = '00000000-0000-0000-0000-0000000000a1' returning 1$$,
  'CORE-12: AC3 another user updates 0 rows');
select throws_ok($$insert into public.notification_pref (user_id, household_id, type, enabled)
  values ('00000000-0000-0000-0000-0000000000a1', current_setting('test.hid')::uuid, 'warranty', false)$$,
  '42501', null, 'CORE-12: AC3 another user cannot insert a row for me');
select is((select count(*)::int from public.notification_pref where user_id = '00000000-0000-0000-0000-0000000000a1'), 0,
  'CORE-12: AC3 another user cannot see my row');
reset role;
select is((select enabled from public.notification_pref where user_id = '00000000-0000-0000-0000-0000000000a1'
  and type = 'eat_soon'), false, 'CORE-12: AC3 a1 row is unchanged');

-- Cross-household: a3 is not in hid; a1 is not in hid2
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a3","role":"authenticated"}', true);
select throws_ok($$insert into public.notification_pref (user_id, household_id, type, enabled)
  values ('00000000-0000-0000-0000-0000000000a3', current_setting('test.hid')::uuid, 'eat_soon', false)$$,
  '42501', null, 'CORE-12: a non-member cannot insert for a household');
insert into public.notification_pref (user_id, household_id, type, enabled)
  values ('00000000-0000-0000-0000-0000000000a3', current_setting('test.hid2')::uuid, 'task_due', false);
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a1","role":"authenticated"}', true);
select is((select count(*)::int from public.notification_pref where household_id = current_setting('test.hid2')::uuid), 0,
  'CORE-12: a1 cannot see hid2 preferences');
select throws_ok($$insert into public.notification_pref (user_id, household_id, type, enabled)
  values ('00000000-0000-0000-0000-0000000000a1', current_setting('test.hid2')::uuid, 'eat_soon', false)$$,
  '42501', null, 'CORE-12: a1 cannot write preferences for hid2');

-- Other checks
select throws_ok($$insert into public.notification_pref (user_id, household_id, type, enabled)
  values ('00000000-0000-0000-0000-0000000000a1', current_setting('test.hid')::uuid, 'bogus', false)$$,
  '23514', null, 'CORE-12: an invalid type is rejected');
select is_empty($$delete from public.notification_pref returning 1$$, 'CORE-12: delete affects 0 rows');
reset role;
select is((select count(*)::int from public.notification_pref), 3, 'CORE-12: all rows survive the delete');

-- A member who has left can no longer update their own row
update public.member set left_at = now()
  where household_id = current_setting('test.hid')::uuid and user_id = '00000000-0000-0000-0000-0000000000a2';
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a2","role":"authenticated"}', true);
select is_empty($$update public.notification_pref set enabled = true where user_id = auth.uid() returning 1$$,
  'CORE-12: a member who left updates 0 rows');
reset role;
select is((select enabled from public.notification_pref where user_id = '00000000-0000-0000-0000-0000000000a2'),
  false, 'CORE-12: the left member row is unchanged');

select * from finish();
rollback;
