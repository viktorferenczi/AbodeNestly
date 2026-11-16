-- CORE-08: activity table (member insert, actor check, RLS isolation, 30-day feed window and index)
begin;
create extension if not exists pgtap with schema extensions;
select plan(20);

insert into auth.users (id, email, aud, role) values
  ('00000000-0000-0000-0000-0000000000a1', 'anna@example.com', 'authenticated', 'authenticated'),
  ('00000000-0000-0000-0000-0000000000a2', 'bela@example.com', 'authenticated', 'authenticated'),
  ('00000000-0000-0000-0000-0000000000a4', 'stranger@example.com', 'authenticated', 'authenticated');

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a1","role":"authenticated"}', true);
select set_config('test.hid', public.create_household('Home')::text, true);
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a2","role":"authenticated"}', true);
select set_config('test.hid2', public.create_household('Cabin')::text, true);

-- AC1: member a1 inserts a row for their household
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a1","role":"authenticated"}', true);
select lives_ok(
  $$insert into public.activity(household_id, actor_id, module, entity, entity_id, action, summary) values
    (current_setting('test.hid')::uuid, '00000000-0000-0000-0000-0000000000a1', 'shopping', 'list_item',
     gen_random_uuid(), 'checked', 'Anna checked off Milk')$$,
  'AC1: member can insert an activity row');
select results_eq(
  $$select actor_id, summary, module from public.activity where household_id = current_setting('test.hid')::uuid$$,
  $$values ('00000000-0000-0000-0000-0000000000a1'::uuid, 'Anna checked off Milk'::text, 'shopping'::text)$$,
  'AC1: actor, summary and module read back');
select ok((select sync_seq is not null and updated_at is not null from public.activity
  where household_id = current_setting('test.hid')::uuid), 'AC1: sync_seq and updated_at are set');
select throws_ok(
  $$insert into public.activity(household_id, actor_id, module, entity, entity_id, action, summary) values
    (current_setting('test.hid')::uuid, '00000000-0000-0000-0000-0000000000a2', 'shopping', 'list_item',
     gen_random_uuid(), 'checked', 'Bela checked off Milk')$$,
  '42501', null, 'AC1: cannot attribute a row to another user');
select throws_ok(
  $$insert into public.activity(household_id, actor_id, module, entity, entity_id, action, summary) values
    (current_setting('test.hid2')::uuid, '00000000-0000-0000-0000-0000000000a1', 'shopping', 'list_item',
     gen_random_uuid(), 'checked', 'x')$$,
  '42501', null, 'AC1: cannot insert into another household');
select throws_ok(
  $$insert into public.activity(household_id, actor_id, module, entity, entity_id, action, summary) values
    (current_setting('test.hid')::uuid, '00000000-0000-0000-0000-0000000000a1', 'garden', 'list_item',
     gen_random_uuid(), 'checked', 'x')$$,
  '23514', null, 'AC1: unknown module violates the check');

-- AC2: non-members see nothing
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a4","role":"authenticated"}', true);
select is_empty($$select 1 from public.activity where household_id = current_setting('test.hid')::uuid$$,
  'AC2: stranger sees no activity');
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a2","role":"authenticated"}', true);
select is_empty($$select 1 from public.activity where household_id = current_setting('test.hid')::uuid$$,
  'AC2: member of household B cannot read household A activity');
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a1","role":"authenticated"}', true);
select isnt_empty($$select 1 from public.activity where household_id = current_setting('test.hid')::uuid$$,
  'AC2: member reads own household activity');

-- Immutability: members may only soft-delete
select throws_ok($$update public.activity set actor_id = '00000000-0000-0000-0000-0000000000a2'
  where household_id = current_setting('test.hid')::uuid$$, '42501', 'activity_field_immutable',
  'guard: cannot reassign actor_id');
select throws_ok($$update public.activity set summary = 'rewritten'
  where household_id = current_setting('test.hid')::uuid$$, '42501', 'activity_field_immutable',
  'guard: cannot rewrite summary');
select throws_ok($$update public.activity set created_at = now() - interval '40 days'
  where household_id = current_setting('test.hid')::uuid$$, '42501', 'activity_field_immutable',
  'guard: cannot change created_at');
select throws_ok($$delete from public.activity where household_id = current_setting('test.hid')::uuid$$,
  '42501', 'activity_soft_delete_only', 'guard: cannot hard delete');
select lives_ok($$update public.activity set deleted_at = now()
  where household_id = current_setting('test.hid')::uuid$$, 'guard: member can soft-delete');
select is((select count(*)::int from public.activity
  where household_id = current_setting('test.hid')::uuid and deleted_at is null), 0,
  'guard: soft-deleted rows leave the live feed');

-- AC3: 30-day feed window (service role / table owner inserts)
reset role;
insert into public.activity(household_id, actor_id, module, entity, entity_id, action, summary, created_at) values
  (current_setting('test.hid')::uuid, '00000000-0000-0000-0000-0000000000a1', 'pantry', 'pantry_item',
   gen_random_uuid(), 'created', 'old', now() - interval '31 days'),
  (current_setting('test.hid')::uuid, '00000000-0000-0000-0000-0000000000a1', 'pantry', 'pantry_item',
   gen_random_uuid(), 'created', 'recent', now() - interval '29 days');
select is(
  (select array_agg(summary order by summary) from public.activity
    where household_id = current_setting('test.hid')::uuid and deleted_at is null
      and created_at >= now() - interval '30 days' and module = 'pantry'),
  array['recent'], 'AC3: feed window excludes the 31-day-old row and keeps the 29-day-old one');
select is((select count(*)::int from public.activity
    where household_id = current_setting('test.hid')::uuid and module = 'pantry'), 2,
  'AC3: the old row is still in the table');
select has_index('public', 'activity', 'activity_feed_idx', 'AC3: feed index exists');
select has_index('public', 'activity', 'activity_sync_idx', 'sync index exists');
select ok(exists (select 1 from pg_publication_tables
  where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = 'activity'),
  'activity is in the Realtime publication');

select * from finish();
rollback;
