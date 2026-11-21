-- SHOP-08 (#514): set_sync_fields() takes a per-household advisory lock until commit so sync_seq
-- follows commit order. The concurrency itself is proven in api/test/SHOP-08-sync-order.int.test.ts
-- (it needs two connections); here we check the trigger still works on every kind of table.
begin;
create extension if not exists pgtap with schema extensions;
select plan(8);

insert into auth.users (id, email, aud, role) values
  ('00000000-0000-0000-0000-0000000000a1', 'a1@example.com', 'authenticated', 'authenticated');

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a1","role":"authenticated"}', true);
select set_config('test.home', public.create_household('Home')::text, true);
reset role;

select set_config('test.list', (select id::text from public.shopping_list
  where household_id = current_setting('test.home')::uuid and name = 'Groceries'), true);
select set_config('test.i1', gen_random_uuid()::text, true);

-- AC1: a write still sets sync_seq and updated_at (household_id branch)
select lives_ok($$insert into public.list_item (id, household_id, list_id, name)
  values (current_setting('test.i1')::uuid, current_setting('test.home')::uuid, current_setting('test.list')::uuid, 'Milk')$$,
  'SHOP-08: AC1 list_item insert accepted');
select ok((select sync_seq is not null and updated_at is not null from public.list_item
  where id = current_setting('test.i1')::uuid), 'SHOP-08: AC1 sync_seq and updated_at set');
select set_config('test.seq', (select sync_seq::text from public.list_item where id = current_setting('test.i1')::uuid), true);
update public.list_item set note = 'x' where id = current_setting('test.i1')::uuid;
select cmp_ok((select sync_seq from public.list_item where id = current_setting('test.i1')::uuid),
  '>', current_setting('test.seq')::bigint, 'SHOP-08: AC1 update raised sync_seq');

-- AC2: the household table (keyed by id, no household_id column) works
select set_config('test.seq', (select sync_seq::text from public.household where id = current_setting('test.home')::uuid), true);
select lives_ok($$update public.household set name = 'Home 2' where id = current_setting('test.home')::uuid$$,
  'SHOP-08: AC2 household update accepted');
select cmp_ok((select sync_seq from public.household where id = current_setting('test.home')::uuid),
  '>', current_setting('test.seq')::bigint, 'SHOP-08: AC2 household sync_seq raised');

-- AC3: member and notification_pref (hand-wired triggers) work
select set_config('test.seq', (select sync_seq::text from public.member
  where household_id = current_setting('test.home')::uuid), true);
update public.member set role = role where household_id = current_setting('test.home')::uuid;
select cmp_ok((select sync_seq from public.member where household_id = current_setting('test.home')::uuid),
  '>', current_setting('test.seq')::bigint, 'SHOP-08: AC3 member write raised sync_seq');
select lives_ok($$insert into public.notification_pref (user_id, household_id, type, enabled)
  values ('00000000-0000-0000-0000-0000000000a1', current_setting('test.home')::uuid, 'eat_soon', false)$$,
  'SHOP-08: AC3 notification_pref insert accepted');

-- AC4: the write holds the household's advisory lock (released at commit)
select is((select count(*)::int from pg_locks
  where locktype = 'advisory' and pid = pg_backend_pid() and objsubid = 1
    and objid = ((hashtextextended('sync_seq:' || current_setting('test.home'), 0)) & 4294967295)::oid),
  1, 'SHOP-08: AC4 household advisory lock held by the writing transaction');

select * from finish();
rollback;
