-- CORE-05: household writes are owner-only, and a non-owner's write raises instead of matching 0 rows.
-- USING widens to members so the row reaches the guard; non-members still see nothing (0 rows).
drop policy household_update on household;
create policy household_update on household for update
  using (is_member(id)) with check (is_member(id));

create or replace function guard_household_update() returns trigger
language plpgsql as $$
begin
  if current_user <> 'authenticated' then return new; end if;   -- service role / security-definer paths
  if is_owner(old.id) then return new; end if;
  raise exception 'household_owner_only' using errcode = '42501';
end $$;
-- Name sorts before household_sync, so the permission error wins.
create trigger household_guard before update on household
  for each row execute function guard_household_update();
