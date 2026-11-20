-- SHOP-08: record_sync_mutation remembers applied mutation ids per user for 24 h.
begin;
create extension if not exists pgtap with schema extensions;
select plan(13);

insert into auth.users (id, email, aud, role) values
  ('00000000-0000-0000-0000-0000000000a1', 'a1@example.com', 'authenticated', 'authenticated'),
  ('00000000-0000-0000-0000-0000000000a2', 'a2@example.com', 'authenticated', 'authenticated');

select set_config('test.m1', gen_random_uuid()::text, true);
select set_config('test.m2', gen_random_uuid()::text, true);
select set_config('test.m3', gen_random_uuid()::text, true);
select set_config('test.m4', gen_random_uuid()::text, true);
select set_config('test.m5', gen_random_uuid()::text, true);

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a1","role":"authenticated"}', true);

-- AC1: first call records (true), a replay is skipped (false)
select is(public.record_sync_mutation(current_setting('test.m1')::uuid), true,
  'SHOP-08: AC1 first record of a mutation id returns true');
select is(public.record_sync_mutation(current_setting('test.m1')::uuid), false,
  'SHOP-08: AC1 replay of the same id returns false');

-- AC2: an id older than 24 h counts as new again, even though cleanup has not run
select is(public.record_sync_mutation(current_setting('test.m2')::uuid), true,
  'SHOP-08: AC2 setup: m2 recorded');
reset role;
update public.sync_applied set applied_at = now() - interval '25 hours'
  where mutation_id = current_setting('test.m2')::uuid;
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a1","role":"authenticated"}', true);
select is(public.record_sync_mutation(current_setting('test.m2')::uuid), true,
  'SHOP-08: AC2 id applied 25 h ago is applied again');
select ok((select applied_at > now() - interval '1 hour' from public.sync_applied
  where mutation_id = current_setting('test.m2')::uuid),
  'SHOP-08: AC2 applied_at refreshed');

-- AC3: ids are independent of each other
select is(public.record_sync_mutation(current_setting('test.m3')::uuid), true, 'SHOP-08: AC3 m3 recorded');
select is(public.record_sync_mutation(current_setting('test.m4')::uuid), true, 'SHOP-08: AC3 m4 recorded');
select is(public.record_sync_mutation(current_setting('test.m3')::uuid), false, 'SHOP-08: AC3 m3 replay skipped');
select is(public.record_sync_mutation(current_setting('test.m4')::uuid), false, 'SHOP-08: AC3 m4 replay skipped');
select is(public.record_sync_mutation(current_setting('test.m5')::uuid), true,
  'SHOP-08: AC3 recording m3/m4 does not mark a fresh m5 as applied');

-- RLS: another user neither sees nor collides with a1's ids
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a2","role":"authenticated"}', true);
select is_empty($$select 1 from public.sync_applied where user_id = '00000000-0000-0000-0000-0000000000a1'$$,
  'SHOP-08: RLS a2 sees no rows of a1');
select is(public.record_sync_mutation(current_setting('test.m1')::uuid), true,
  'SHOP-08: RLS a2 can record a1''s id (ids are per user)');
select throws_ok($$insert into public.sync_applied (user_id, mutation_id)
  values ('00000000-0000-0000-0000-0000000000a1', gen_random_uuid())$$,
  '42501', null, 'SHOP-08: RLS a2 cannot insert a row for a1');

select * from finish();
rollback;
