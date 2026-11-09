-- CORE-01: profile row created on first sign-in
begin;
create extension if not exists pgtap with schema extensions;
select plan(21);

-- Fixtures (as postgres): the on_auth_user_created trigger creates the profiles.
insert into auth.users (id, email, raw_user_meta_data, aud, role) values
  ('00000000-0000-0000-0000-0000000000a1', 'anna@example.com',
   '{"full_name":"Anna Kovacs","avatar_url":"https://example.com/a.png"}', 'authenticated', 'authenticated'),
  ('00000000-0000-0000-0000-0000000000a2', 'bence@example.com',
   '{"name":"Bence"}', 'authenticated', 'authenticated'),
  ('00000000-0000-0000-0000-0000000000a3', 'csilla.t@example.com',
   '{}', 'authenticated', 'authenticated'),
  ('00000000-0000-0000-0000-0000000000a4', 'dora@example.com',
   '{"full_name":"Dora"}', 'authenticated', 'authenticated');

-- AC1: full_name + avatar copied, id matches
select is((select display_name from public.profile where id = '00000000-0000-0000-0000-0000000000a1'),
  'Anna Kovacs', 'AC1: display_name from full_name');
select is((select avatar_path from public.profile where id = '00000000-0000-0000-0000-0000000000a1'),
  'https://example.com/a.png', 'AC1: avatar_url copied to avatar_path');
select is((select count(*)::int from public.profile where id = '00000000-0000-0000-0000-0000000000a1'),
  1, 'AC1: profile id equals the user id');

-- Extra: middle step of the coalesce
select is((select display_name from public.profile where id = '00000000-0000-0000-0000-0000000000a2'),
  'Bence', 'display_name falls back to name');

-- AC2: email local part
select is((select display_name from public.profile where id = '00000000-0000-0000-0000-0000000000a3'),
  'csilla.t', 'AC2: display_name falls back to email local part');

-- AC3: replaying the trigger function for an existing id keeps the row untouched.
-- A second auth.users insert would violate its primary key, so go through a temp table.
update public.profile set display_name = 'Kept' where id = '00000000-0000-0000-0000-0000000000a1';
create temp table replay_users (id uuid, email text, raw_user_meta_data jsonb);
create trigger replay_new_user after insert on pg_temp.replay_users
  for each row execute function public.handle_new_user();
insert into pg_temp.replay_users values
  ('00000000-0000-0000-0000-0000000000a1', 'anna@example.com', '{"full_name":"Other"}');
select is((select display_name from public.profile where id = '00000000-0000-0000-0000-0000000000a1'),
  'Kept', 'AC3: replay does not overwrite display_name');
select is((select count(*)::int from public.profile where id = '00000000-0000-0000-0000-0000000000a1'),
  1, 'AC3: still exactly one profile row');

-- AC4: household peers see each other's profile, strangers do not
insert into public.household (id, name, created_by)
  values ('00000000-0000-0000-0000-0000000000b1', 'Home', '00000000-0000-0000-0000-0000000000a1');
insert into public.member (household_id, user_id, role) values
  ('00000000-0000-0000-0000-0000000000b1', '00000000-0000-0000-0000-0000000000a1', 'owner'),
  ('00000000-0000-0000-0000-0000000000b1', '00000000-0000-0000-0000-0000000000a2', 'member');
-- u4 used to be a member and has left
insert into public.member (household_id, user_id, role, left_at)
  values ('00000000-0000-0000-0000-0000000000b1', '00000000-0000-0000-0000-0000000000a4', 'member', now());
-- a second household u2 was never invited to
insert into public.household (id, name, created_by)
  values ('00000000-0000-0000-0000-0000000000b2', 'Elsewhere', '00000000-0000-0000-0000-0000000000a3');

-- household_sync overwrites client-supplied sync fields on insert
insert into public.household (id, name, created_by, updated_at, sync_seq)
  values ('00000000-0000-0000-0000-0000000000b3', 'Forged', '00000000-0000-0000-0000-0000000000a3', '2000-01-01', 0);
select ok((select sync_seq > 0 and updated_at > '2001-01-01' from public.household
           where id = '00000000-0000-0000-0000-0000000000b3'),
  'household insert: sync_seq and updated_at are server-assigned');

set local role authenticated;

select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a1","role":"authenticated"}', true);
select results_eq(
  $$select display_name from public.profile where id = '00000000-0000-0000-0000-0000000000a2'$$,
  $$values ('Bence'::text)$$,
  'AC4: household peer can read the other member''s profile');
select is((select count(*)::int from public.profile where id = '00000000-0000-0000-0000-0000000000a1'),
  1, 'AC4: user reads own profile');

select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a3","role":"authenticated"}', true);
select is_empty(
  $$select 1 from public.profile where id = '00000000-0000-0000-0000-0000000000a2'$$,
  'AC4: stranger without a household cannot read a member''s profile');
select is_empty(
  $$select 1 from public.profile where id = '00000000-0000-0000-0000-0000000000a1'$$,
  'AC4: stranger cannot read the owner''s profile either');
select is((select count(*)::int from public.profile where id = '00000000-0000-0000-0000-0000000000a3'),
  1, 'AC4: stranger can still read own profile (profile_self)');

-- profile_household ignores members who have left
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a4","role":"authenticated"}', true);
select is_empty(
  $$select 1 from public.profile where id = '00000000-0000-0000-0000-0000000000a1'$$,
  'a member who left cannot read a current member''s profile');
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a1","role":"authenticated"}', true);
select is_empty(
  $$select 1 from public.profile where id = '00000000-0000-0000-0000-0000000000a4'$$,
  'a current member cannot read the profile of someone who left');

-- member_update: a plain member cannot escalate, move household or rejoin
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a2","role":"authenticated"}', true);
select throws_ok(
  $$update public.member set role = 'owner' where user_id = '00000000-0000-0000-0000-0000000000a2'$$,
  '42501', 'member_role_owner_only', 'member cannot promote themselves');
select is((select role from public.member where user_id = '00000000-0000-0000-0000-0000000000a2'),
  'member', 'role is unchanged after the rejected promotion');
select throws_ok(
  $$update public.member set household_id = '00000000-0000-0000-0000-0000000000b2'
    where user_id = '00000000-0000-0000-0000-0000000000a2'$$,
  '42501', 'member_identity_immutable', 'member cannot move into another household');
select lives_ok(
  $$update public.member set left_at = now() where user_id = '00000000-0000-0000-0000-0000000000a2'$$,
  'member can leave');
-- once left, the row is invisible to its user (member_select), so the clear matches nothing
select lives_ok(
  $$update public.member set left_at = null where user_id = '00000000-0000-0000-0000-0000000000a2'$$,
  'member cannot clear left_at to rejoin: statement matches no row');

reset role;
select isnt((select left_at from public.member where user_id = '00000000-0000-0000-0000-0000000000a2'),
  null, 'left_at is still set after the rejoin attempt');
select * from finish();
rollback;
