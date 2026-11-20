-- SHOP-09: list_item, shopping_list and store_item_order are in the supabase_realtime publication.
-- pgTAP cannot observe a Realtime event; it asserts publication membership plus the RLS that Realtime
-- applies to decide which household's members receive it.
begin;
create extension if not exists pgtap with schema extensions;
select plan(10);

insert into auth.users (id, email, aud, role) values
  ('00000000-0000-0000-0000-0000000000a1', 'a1@example.com', 'authenticated', 'authenticated'),
  ('00000000-0000-0000-0000-0000000000a2', 'a2@example.com', 'authenticated', 'authenticated');

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a1","role":"authenticated"}', true);
select set_config('test.home', public.create_household('Home')::text, true);
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a2","role":"authenticated"}', true);
select set_config('test.cabin', public.create_household('Cabin')::text, true);
reset role;

select set_config('test.list', (select id::text from public.shopping_list
  where household_id = current_setting('test.home')::uuid and name = 'Groceries'), true);
select set_config('test.i1', gen_random_uuid()::text, true);
insert into public.list_item (id, household_id, list_id, name)
  values (current_setting('test.i1')::uuid, current_setting('test.home')::uuid, current_setting('test.list')::uuid, 'Milk');

-- AC1: the list tables are published, and an update by a member is scoped to that household
select ok(exists (select 1 from pg_publication_tables
  where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = 'list_item'),
  'SHOP-09: AC1 list_item is in the Realtime publication');
select ok(exists (select 1 from pg_publication_tables
  where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = 'shopping_list'),
  'SHOP-09: AC1 shopping_list is in the Realtime publication');
select case when to_regclass('public.store_item_order') is null
  then skip('store_item_order not created yet (SHOP-06-db)', 1)
  else ok(exists (select 1 from pg_publication_tables
    where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = 'store_item_order'),
    'SHOP-09: AC1 store_item_order is in the Realtime publication')
  end;

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a1","role":"authenticated"}', true);
select lives_ok($$update public.list_item set name = 'Oat milk' where id = current_setting('test.i1')::uuid$$,
  'SHOP-09: AC1 a member updates a published list_item row');
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-0000000000a2","role":"authenticated"}', true);
select is_empty($$select 1 from public.list_item where id = current_setting('test.i1')::uuid$$,
  'SHOP-09: AC1 a member of another household cannot read the row, so gets no event for it');
select throws_ok($$call public.publish_realtime('list_item')$$, '42501', null,
  'SHOP-09: authenticated cannot change the publication');
reset role;

-- AC2: an unrelated table is not required to be published
select ok(not exists (select 1 from pg_publication_tables
  where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = 'sync_applied'),
  'SHOP-09: AC2 sync_applied is not in the Realtime publication');

-- AC3: applying the publication again does not error and does not duplicate
select lives_ok($$call public.publish_realtime('list_item', 'shopping_list', 'store_item_order')$$,
  'SHOP-09: AC3 re-applying the publication does not error');
select lives_ok($$call public.publish_realtime('list_item', 'shopping_list', 'store_item_order')$$,
  'SHOP-09: AC3 applying it a third time does not error');
select is((select count(*)::int from pg_publication_tables
  where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = 'list_item'), 1,
  'SHOP-09: AC3 list_item is a member exactly once');

select * from finish();
rollback;
