-- CORE-03: invite table and accept_invite (expiry, max uses, role, rejoin, RLS)
begin;
create extension if not exists pgtap with schema extensions;
select plan(28);

insert into auth.users (id, email, aud, role) values
  ('00000000-0000-0000-0000-0000000000a1', 'owner@example.com', 'authenticated', 'authenticated'),
  ('00000000-0000-0000-0000-0000000000a2', 'joiner@example.com', 'authenticated', 'authenticated'),
  ('00000000-0000-0000-0000-0000000000a3', 'member@example.com', 'authenticated', 'authenticated'),
  ('00000000-0000-0000-0000-0000000000a4', 'stranger@example.com', 'authenticated', 'authenticated');

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a1","role":"authenticated"}', true);
select set_config('test.hid', public.create_household('Home')::text, true);
select set_config('test.hid2', public.create_household('Cabin')::text, true);

reset role;
insert into public.invite (household_id, code, created_by, role, expires_at, max_uses, uses) values
  (current_setting('test.hid')::uuid, 'live',     '00000000-0000-0000-0000-0000000000a1', 'member', now() + interval '1 day', 10, 0),
  (current_setting('test.hid')::uuid, 'expired',  '00000000-0000-0000-0000-0000000000a1', 'member', now() - interval '1 day', 10, 0),
  (current_setting('test.hid')::uuid, 'full',     '00000000-0000-0000-0000-0000000000a1', 'member', now() + interval '1 day', 2, 2),
  (current_setting('test.hid')::uuid, 'ownerinv', '00000000-0000-0000-0000-0000000000a1', 'owner',  now() + interval '1 day', 10, 0),
  (current_setting('test.hid')::uuid, 'gone',     '00000000-0000-0000-0000-0000000000a1', 'member', now() + interval '1 day', 10, 0);
update public.invite set deleted_at = now() where code = 'gone';

-- a3 joins via live (member)
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a3","role":"authenticated"}', true);
select public.accept_invite('live');

-- AC1: a2 accepts live
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a2","role":"authenticated"}', true);
select is(public.accept_invite('live'), current_setting('test.hid')::uuid, 'AC1: returns the household id');
select results_eq(
  $$select role, left_at is null from public.member
    where household_id = current_setting('test.hid')::uuid and user_id = '00000000-0000-0000-0000-0000000000a2'$$,
  $$values ('member'::text, true)$$, 'AC1: active member row with role member');
reset role;
select is((select uses from public.invite where code = 'live'), 2, 'AC1: uses incremented (a3 and a2)');

-- AC2: expired
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a4","role":"authenticated"}', true);
select throws_ok($$select public.accept_invite('expired')$$, 'P0001', 'invalid_invite', 'AC2: expired invite rejected');
reset role;
select is((select count(*)::int from public.member where user_id = '00000000-0000-0000-0000-0000000000a4'), 0,
  'AC2: no member row for caller');
select is((select uses from public.invite where code = 'expired'), 0, 'AC2: uses unchanged');

-- AC3: exhausted
set local role authenticated;
select throws_ok($$select public.accept_invite('full')$$, 'P0001', 'invalid_invite', 'AC3: exhausted invite rejected');
reset role;
select is((select uses from public.invite where code = 'full'), 2, 'AC3: uses unchanged');

-- unknown and soft-deleted
set local role authenticated;
select throws_ok($$select public.accept_invite('nope')$$, 'P0001', 'invalid_invite', 'unknown code rejected');
select throws_ok($$select public.accept_invite('gone')$$, 'P0001', 'invalid_invite', 'soft-deleted invite rejected');

-- AC4: a2 leaves, then rejoins
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a2","role":"authenticated"}', true);
update public.member set left_at = now()
  where household_id = current_setting('test.hid')::uuid and user_id = '00000000-0000-0000-0000-0000000000a2';
reset role;
select isnt((select left_at from public.member
  where household_id = current_setting('test.hid')::uuid and user_id = '00000000-0000-0000-0000-0000000000a2'),
  null, 'AC4: left_at set by the client');
set local role authenticated;
select public.accept_invite('live');
select is((select left_at from public.member
  where household_id = current_setting('test.hid')::uuid and user_id = '00000000-0000-0000-0000-0000000000a2'),
  null, 'AC4: rejoin clears left_at');
reset role;
select is((select uses from public.invite where code = 'live'), 3, 'AC4: rejoin consumes a use');

-- Role: a4 accepts ownerinv
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a4","role":"authenticated"}', true);
select public.accept_invite('ownerinv');
select is((select role from public.member
  where household_id = current_setting('test.hid')::uuid and user_id = '00000000-0000-0000-0000-0000000000a4'),
  'owner', 'accepting an owner invite yields role owner');

-- Active member no-op: owner a1 accepts live
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a1","role":"authenticated"}', true);
select is(public.accept_invite('live'), current_setting('test.hid')::uuid, 'active owner: returns household id');
select is((select role from public.member
  where household_id = current_setting('test.hid')::uuid and user_id = '00000000-0000-0000-0000-0000000000a1'),
  'owner', 'active owner is not demoted');
reset role;
select is((select uses from public.invite where code = 'live'), 3, 'active owner: uses unchanged');

-- Unauthenticated
set local role authenticated;
select set_config('request.jwt.claims', '{"role":"authenticated"}', true);
select throws_ok($$select public.accept_invite('live')$$, '42501', 'not_authenticated', 'unauthenticated call rejected');

-- RLS: stranger-to-hid2 (a3 is only in hid; use a3 against hid2) ; a2 is member of hid only
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a3","role":"authenticated"}', true);
select is_empty($$select 1 from public.invite where household_id = current_setting('test.hid2')::uuid$$,
  'RLS: non-member cannot select the other household invites');
select isnt_empty($$select 1 from public.invite where household_id = current_setting('test.hid')::uuid$$,
  'RLS: member can select own household invites');
select throws_ok(
  $$insert into public.invite(household_id, created_by, role) values
    (current_setting('test.hid')::uuid, '00000000-0000-0000-0000-0000000000a3', 'owner')$$,
  '42501', null, 'RLS: non-owner member cannot create an owner invite');
select lives_ok(
  $$insert into public.invite(household_id, created_by, role) values
    (current_setting('test.hid')::uuid, '00000000-0000-0000-0000-0000000000a3', 'member')$$,
  'RLS: member can create a member invite');
select throws_ok(
  $$insert into public.invite(household_id, created_by) values
    (current_setting('test.hid2')::uuid, '00000000-0000-0000-0000-0000000000a3')$$,
  '42501', null, 'RLS: cannot create an invite for another household');
select throws_ok(
  $$insert into public.invite(household_id, created_by) values
    (current_setting('test.hid')::uuid, '00000000-0000-0000-0000-0000000000a1')$$,
  '42501', null, 'RLS: created_by must be the caller');

-- Update guard: a member cannot escalate role or reset uses; may still retire an invite
select throws_ok($$update public.invite set role = 'owner' where code = 'live'$$, '42501', 'invite_owner_only',
  'guard: member cannot set role to owner');
select throws_ok($$update public.invite set uses = 0 where code = 'full'$$, '42501', 'invite_field_immutable',
  'guard: member cannot reset uses');
select lives_ok($$update public.invite set deleted_at = now() where code = 'ownerinv'$$,
  'guard: member can retire an invite');
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a1","role":"authenticated"}', true);
select lives_ok(
  $$insert into public.invite(household_id, created_by, role) values
    (current_setting('test.hid')::uuid, '00000000-0000-0000-0000-0000000000a1', 'owner')$$,
  'RLS: owner can create an owner invite');

reset role;
select * from finish();
rollback;
