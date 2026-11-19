-- CORE-11: attachment rows and household Storage policies.
begin;
create extension if not exists pgtap with schema extensions;
select plan(21);

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

select set_config('test.eid', gen_random_uuid()::text, true);

-- AC1: a member inserts an attachment for their household; foreign prefix and forged created_by are rejected
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a1","role":"authenticated"}', true);
select lives_ok($$insert into public.attachment (household_id, entity, entity_id, storage_path, mime, size_bytes, created_by)
  values (current_setting('test.home')::uuid, 'asset', current_setting('test.eid')::uuid,
    'households/' || current_setting('test.home') || '/asset/' || current_setting('test.eid') || '/a.pdf',
    'application/pdf', 1234, '00000000-0000-0000-0000-0000000000a1')$$, 'CORE-11: AC1 member insert accepted');
select results_eq($$select entity, entity_id, mime, size_bytes, created_by from public.attachment$$,
  $$values ('asset'::text, current_setting('test.eid')::uuid, 'application/pdf'::text, 1234::bigint,
    '00000000-0000-0000-0000-0000000000a1'::uuid)$$, 'CORE-11: AC1 row stored as inserted');
select throws_ok($$insert into public.attachment (household_id, entity, entity_id, storage_path, mime, size_bytes, created_by)
  values (current_setting('test.home')::uuid, 'asset', current_setting('test.eid')::uuid,
    'households/' || current_setting('test.cabin') || '/asset/' || current_setting('test.eid') || '/b.pdf',
    'application/pdf', 1, '00000000-0000-0000-0000-0000000000a1')$$,
  '23514', null, 'CORE-11: AC1 path under another household rejected');
select throws_ok($$insert into public.attachment (household_id, entity, entity_id, storage_path, mime, size_bytes, created_by)
  values (current_setting('test.home')::uuid, 'asset', current_setting('test.eid')::uuid,
    'households/' || current_setting('test.home') || '/asset/' || current_setting('test.eid') || '/c.pdf',
    'application/pdf', 1, '00000000-0000-0000-0000-0000000000a2')$$,
  '42501', null, 'CORE-11: AC1 forged created_by rejected');
select throws_ok($$insert into public.attachment (household_id, entity, entity_id, storage_path, mime, size_bytes, created_by)
  values (current_setting('test.home')::uuid, 'pantry_item', current_setting('test.eid')::uuid,
    'households/' || current_setting('test.home') || '/pantry/item/' || current_setting('test.eid') || '/e.pdf',
    'application/pdf', 1, '00000000-0000-0000-0000-0000000000a1')$$,
  '23514', null, 'CORE-11: AC1 underscore in entity is not a wildcard');
select throws_ok($$insert into public.attachment (household_id, entity, entity_id, storage_path, mime, size_bytes, created_by)
  values (current_setting('test.home')::uuid, 'asset', current_setting('test.eid')::uuid,
    'households/' || current_setting('test.home') || '/asset/' || current_setting('test.eid') || '/sub/f.pdf',
    'application/pdf', 1, '00000000-0000-0000-0000-0000000000a1')$$,
  '23514', null, 'CORE-11: AC1 nested file path rejected');
select throws_ok($$update public.attachment set created_by = '00000000-0000-0000-0000-0000000000a2'$$,
  '42501', null, 'CORE-11: AC1 created_by is immutable for clients');

-- AC2: non-members and former members see nothing and cannot write
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a2","role":"authenticated"}', true);
select is_empty($$select 1 from public.attachment$$, 'CORE-11: AC2 stranger sees no attachments');
select throws_ok($$insert into public.attachment (household_id, entity, entity_id, storage_path, mime, size_bytes, created_by)
  values (current_setting('test.home')::uuid, 'asset', current_setting('test.eid')::uuid,
    'households/' || current_setting('test.home') || '/asset/' || current_setting('test.eid') || '/d.pdf',
    'application/pdf', 1, '00000000-0000-0000-0000-0000000000a2')$$,
  '42501', null, 'CORE-11: AC2 stranger insert rejected');
update public.attachment set mime = 'text/plain';
delete from public.attachment;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a3","role":"authenticated"}', true);
select is_empty($$select 1 from public.attachment$$, 'CORE-11: AC2 former member sees no attachments');
reset role;
select is((select mime from public.attachment), 'application/pdf', 'CORE-11: AC2 stranger update changed nothing');
select is((select count(*)::int from public.attachment), 1, 'CORE-11: AC2 stranger delete removed nothing');

-- AC3: Storage policies
insert into storage.objects (bucket_id, name) values
  ('households', 'households/' || current_setting('test.home') || '/asset/' || current_setting('test.eid') || '/a.pdf'),
  ('households', 'households/' || current_setting('test.cabin') || '/asset/' || current_setting('test.eid') || '/b.pdf'),
  ('households', 'misc/c.pdf'),
  ('households', 'households/not-a-uuid/x');

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a1","role":"authenticated"}', true);
select is((select count(*)::int from storage.objects where bucket_id = 'households' and name like 'households/' || current_setting('test.home') || '/%'),
  1, 'CORE-11: AC3 member reads own household object');
select is((select count(*)::int from storage.objects where name like 'households/' || current_setting('test.cabin') || '/%'),
  0, 'CORE-11: AC3 other household object hidden');
select is((select count(*)::int from storage.objects where name = 'misc/c.pdf'), 0, 'CORE-11: AC3 misc/ object hidden');
select is((select count(*)::int from storage.objects where name = 'households/not-a-uuid/x'), 0,
  'CORE-11: AC3 malformed key hidden');
select lives_ok($$select count(*) from storage.objects$$, 'CORE-11: AC3 select never raises a cast error');
select throws_ok($$insert into storage.objects (bucket_id, name)
  values ('households', 'households/' || current_setting('test.cabin') || '/asset/' || current_setting('test.eid') || '/z.pdf')$$,
  '42501', null, 'CORE-11: AC3 insert into another household rejected');
select throws_ok($$insert into storage.objects (bucket_id, name)
  values ('households', 'households/' || current_setting('test.home') || '/x.pdf')$$,
  '42501', null, 'CORE-11: AC3 insert at wrong depth rejected');
select lives_ok($$insert into storage.objects (bucket_id, name)
  values ('households', 'households/' || current_setting('test.home') || '/asset/' || current_setting('test.eid') || '/new.pdf')$$,
  'CORE-11: AC3 insert at correct path accepted');
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a3","role":"authenticated"}', true);
select is((select count(*)::int from storage.objects where name like 'households/' || current_setting('test.home') || '/%'),
  0, 'CORE-11: AC3 former member cannot read household object');
reset role;

select * from finish();
rollback;
