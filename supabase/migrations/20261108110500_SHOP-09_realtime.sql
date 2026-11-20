-- SHOP-09-db: list changes reach the other members via Supabase Realtime (SPEC §6.4); clients pull on any event.
-- list_item (SHOP-03) and shopping_list (CORE-02) are already published; store_item_order is created by
-- SHOP-06-db. A plain `alter publication ... add table` on a member fails with 42710, so publish_realtime
-- skips tables already in the publication and tables that do not exist yet. SHOP-06-db must
-- `call publish_realtime('store_item_order')` in its own migration so an already-migrated database gets it.
-- Realtime applies each table's RLS (is_member), so an event only reaches members of that household.

create or replace procedure publish_realtime(variadic p_tables text[])
language plpgsql set search_path = public as $$
declare t text;
begin
  foreach t in array p_tables loop
    if to_regclass('public.' || quote_ident(t)) is not null and not exists (
      select 1 from pg_publication_tables
      where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = t) then
      execute format('alter publication supabase_realtime add table public.%I', t);
    end if;
  end loop;
end $$;
revoke execute on procedure publish_realtime(text[]) from public, anon, authenticated;

call publish_realtime('list_item', 'shopping_list', 'store_item_order');
