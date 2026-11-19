-- CORE-10: purge soft-deleted rows 30 days after deletion (restore window), nightly at 03:00 UTC.
--
-- The table list is the complete SPEC §14 list. Several of those tables (list_item, pantry_item,
-- maint_*, ...) arrive in later SHOP/PANT/MAINT stories, so the function skips any table that does
-- not exist yet (to_regclass is null). Those tables are then purged automatically once their own
-- migrations land, with no further change here.
-- Children come before parents (list_item before shopping_list, maint_log before maint_task).

create extension if not exists pg_cron;

create or replace function purge_soft_deleted() returns void
language plpgsql security definer set search_path = public as $$
declare t text;
begin
  foreach t in array array['list_item','shopping_list','pantry_item','maint_log','maint_task','asset',
                           'store','location','category','recipe','contractor','house','attachment']
  loop
    continue when to_regclass(t) is null;
    execute format('delete from %I where deleted_at < now() - interval ''30 days''', t);
  end loop;
end $$;

-- Security definer: keep it off PostgREST RPC. Only cron / postgres runs it.
revoke execute on function purge_soft_deleted() from public, anon, authenticated, service_role;

select cron.schedule('purge-soft-deleted', '0 3 * * *', $$select purge_soft_deleted()$$);
