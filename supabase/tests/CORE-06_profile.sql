-- CORE-06: profile self-update, peer read, avatar Storage policies.
begin;
create extension if not exists pgtap with schema extensions;
select plan(18);

insert into auth.users (id, email, aud, role) values
  ('00000000-0000-0000-0000-0000000000a1', 'a1@example.com', 'authenticated', 'authenticated'),
  ('00000000-0000-0000-0000-0000000000a2', 'a2@example.com', 'authenticated', 'authenticated'),
  ('00000000-0000-0000-0000-0000000000a3', 'a3@example.com', 'authenticated', 'authenticated'),
  ('00000000-0000-0000-0000-0000000000a4', 'a4@example.com', 'authenticated', 'authenticated');

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a1","role":"authenticated"}', true);
select set_config('test.hid', public.create_household('Home')::text, true);
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a3","role":"authenticated"}', true);
select public.create_household('Cabin');
reset role;

insert into public.member (household_id, user_id, role) values
  (current_setting('test.hid')::uuid, '00000000-0000-0000-0000-0000000000a2', 'member'),
  (current_setting('test.hid')::uuid, '00000000-0000-0000-0000-0000000000a4', 'member');
update public.member set left_at = now()
  where household_id = current_setting('test.hid')::uuid and user_id = '00000000-0000-0000-0000-0000000000a4';

-- profile_touch would stamp now(); switch it off so the fixture can start in the past
alter table public.profile disable trigger profile_touch;
update public.profile set display_name = 'One', updated_at = '2000-01-01', avatar_path = 'profiles/00000000-0000-0000-0000-0000000000a1/me.png'
  where id = '00000000-0000-0000-0000-0000000000a1';
update public.profile set display_name = 'Two' where id = '00000000-0000-0000-0000-0000000000a2';
alter table public.profile enable trigger profile_touch;
select set_config('test.created', (select created_at::text from public.profile
  where id = '00000000-0000-0000-0000-0000000000a1'), true);
select set_config('test.updated', (select updated_at::text from public.profile
  where id = '00000000-0000-0000-0000-0000000000a1'), true);

-- AC1: own update stored; timestamps are server-owned
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a1","role":"authenticated"}', true);
select lives_ok($$update public.profile set display_name = 'New', created_at = '2000-01-01'
  where id = '00000000-0000-0000-0000-0000000000a1'$$, 'CORE-06: AC1 self update accepted');
select is((select display_name from public.profile where id = '00000000-0000-0000-0000-0000000000a1'), 'New',
  'CORE-06: AC1 new name stored');
select ok((select updated_at > current_setting('test.updated')::timestamptz from public.profile
  where id = '00000000-0000-0000-0000-0000000000a1'), 'CORE-06: AC1 updated_at advanced');
select is((select created_at::text from public.profile where id = '00000000-0000-0000-0000-0000000000a1'),
  current_setting('test.created'), 'CORE-06: AC1 forged created_at ignored');

-- AC2: peer update raises, stranger matches nothing, id change blocked, no insert/delete
select throws_ok($$update public.profile set display_name = 'Hax' where id = '00000000-0000-0000-0000-0000000000a2'$$,
  '42501', null, 'CORE-06: AC2 peer update rejected');
select throws_ok($$update public.profile set id = '00000000-0000-0000-0000-0000000000a2'
  where id = '00000000-0000-0000-0000-0000000000a1'$$, '42501', null, 'CORE-06: AC2 changing id rejected');
select throws_ok($$insert into public.profile (id, display_name) values (gen_random_uuid(), 'X')$$,
  '42501', null, 'CORE-06: AC2 insert rejected');
delete from public.profile where id = '00000000-0000-0000-0000-0000000000a1';
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a3","role":"authenticated"}', true);
update public.profile set display_name = 'Hax' where id = '00000000-0000-0000-0000-0000000000a1';
reset role;
select is((select display_name from public.profile where id = '00000000-0000-0000-0000-0000000000a2'), 'Two',
  'CORE-06: AC2 peer name unchanged');
select is((select display_name from public.profile where id = '00000000-0000-0000-0000-0000000000a1'), 'New',
  'CORE-06: AC2 stranger update and own delete changed nothing');

-- AC3: peers read, strangers and former members do not
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a2","role":"authenticated"}', true);
select results_eq($$select display_name, avatar_path from public.profile where id = '00000000-0000-0000-0000-0000000000a1'$$,
  $$values ('New'::text, 'profiles/00000000-0000-0000-0000-0000000000a1/me.png'::text)$$, 'CORE-06: AC3 peer reads profile');
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a3","role":"authenticated"}', true);
select is_empty($$select 1 from public.profile where id = '00000000-0000-0000-0000-0000000000a1'$$, 'CORE-06: AC3 stranger sees nothing');
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a4","role":"authenticated"}', true);
select is_empty($$select 1 from public.profile where id = '00000000-0000-0000-0000-0000000000a1'$$, 'CORE-06: AC3 former member sees nothing');

-- Storage
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a1","role":"authenticated"}', true);
select lives_ok($$insert into storage.objects (bucket_id, name, owner) values
  ('households', 'profiles/00000000-0000-0000-0000-0000000000a1/me.png', auth.uid())$$, 'CORE-06: own avatar upload');
select throws_ok($$insert into storage.objects (bucket_id, name, owner) values
  ('households', 'profiles/00000000-0000-0000-0000-0000000000a2/x.png', auth.uid())$$, '42501', null,
  'CORE-06: cannot upload into a peer folder');
select throws_ok($$insert into storage.objects (bucket_id, name, owner) values
  ('households', 'profiles/00000000-0000-0000-0000-0000000000a1/sub/x.png', auth.uid())$$, '42501', null,
  'CORE-06: nested path rejected');
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a2","role":"authenticated"}', true);
select is((select count(*)::int from storage.objects where name = 'profiles/00000000-0000-0000-0000-0000000000a1/me.png'), 1,
  'CORE-06: peer reads avatar');
-- Storage refuses direct deletes unless this flag is set; RLS must still hide the row
select set_config('storage.allow_delete_query', 'true', true);
delete from storage.objects where name = 'profiles/00000000-0000-0000-0000-0000000000a1/me.png';
select lives_ok($$select count(*) from storage.objects where name = 'profiles/not-a-uuid/x'$$,
  'CORE-06: malformed folder does not raise');
reset role;
select is((select count(*)::int from storage.objects where name = 'profiles/00000000-0000-0000-0000-0000000000a1/me.png'), 1,
  'CORE-06: peer delete removed nothing');

select * from finish();
rollback;
