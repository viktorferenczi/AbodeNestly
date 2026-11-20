-- CORE-10: purge_soft_deleted() removes rows deleted more than 30 days ago.
-- shopping_list and location stand in for the §14 tables; the function skips tables that do not exist yet.
begin;
create extension if not exists pgtap with schema extensions;
select plan(10);

insert into auth.users (id, email, aud, role) values
  ('00000000-0000-0000-0000-0000000000a1', 'anna@example.com', 'authenticated', 'authenticated');

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a1","role":"authenticated"}', true);
select set_config('test.hid', public.create_household('Home')::text, true);
reset role;

insert into public.shopping_list(id, household_id, name, deleted_at) values
  ('00000000-0000-0000-0000-00000000d031', current_setting('test.hid')::uuid, 'old',  now() - interval '31 days'),
  ('00000000-0000-0000-0000-00000000d029', current_setting('test.hid')::uuid, 'recent', now() - interval '29 days'),
  ('00000000-0000-0000-0000-00000000d000', current_setting('test.hid')::uuid, 'live', null);
insert into public.location(id, household_id, name, deleted_at) values
  ('00000000-0000-0000-0000-00000000e031', current_setting('test.hid')::uuid, 'old shelf', now() - interval '31 days');

select lives_ok($$select public.purge_soft_deleted()$$, 'missing tables from the §14 list are skipped');

-- AC1
select is_empty($$select 1 from public.shopping_list where id = '00000000-0000-0000-0000-00000000d031'$$,
  'CORE-10: row deleted 31 days ago is purged (shopping_list)');
select is_empty($$select 1 from public.location where id = '00000000-0000-0000-0000-00000000e031'$$,
  'CORE-10: row deleted 31 days ago is purged (location)');

-- AC2
select is(
  (select count(*)::int from public.shopping_list where id in
    ('00000000-0000-0000-0000-00000000d029', '00000000-0000-0000-0000-00000000d000')),
  2, 'CORE-10: row deleted 29 days ago remains, and so does the live row');

-- AC3
select set_config('test.before',
  ((select count(*) from public.shopping_list) + (select count(*) from public.location))::text, true);
select lives_ok($$select public.purge_soft_deleted()$$, 'CORE-10: second run is a no-op (no error)');
select is(
  ((select count(*) from public.shopping_list) + (select count(*) from public.location))::text,
  current_setting('test.before'), 'CORE-10: second run leaves the row count unchanged');

-- Access
select is((select schedule from cron.job where jobname = 'purge-soft-deleted'), '0 3 * * *',
  'cron job purge-soft-deleted is scheduled at 03:00 UTC');
select ok(not has_function_privilege('authenticated', 'public.purge_soft_deleted()', 'execute'),
  'authenticated cannot execute purge_soft_deleted()');
select ok(not has_function_privilege('anon', 'public.purge_soft_deleted()', 'execute'),
  'anon cannot execute purge_soft_deleted()');

select ok(not has_function_privilege('service_role', 'public.purge_soft_deleted()', 'execute'),
  'service_role cannot execute purge_soft_deleted()');

select * from finish();
rollback;
