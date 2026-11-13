-- CORE-04: an update may not leave a household with zero active owners.
-- Separate trigger (not part of guard_member_update): that guard returns early for non-`authenticated`
-- roles, but this rule must hold for every role (service role, security-definer functions).
-- Name sorts between member_guard and member_sync, so permission errors still win.
-- Security invoker: the caller is an active member in a BEFORE trigger, so RLS shows every member row;
-- RLS can only hide rows, so any blind spot fails closed (rejects), never open.
-- Covers leaving (left_at set) and demotion (role owner -> other). Replaying a leave on an already-left
-- row is a no-op here (old.left_at is not null), so it stays idempotent.
create or replace function reject_last_owner_exit() returns trigger
language plpgsql as $$
begin
  -- Only an active owner losing ownership (leaving or being demoted) matters.
  if old.role <> 'owner' or old.left_at is not null then return new; end if;
  if new.role = 'owner' and new.left_at is null then return new; end if;
  -- Serialise owner exits per household so two owners leaving at once cannot both pass.
  perform pg_advisory_xact_lock(hashtext('member_owner:' || old.household_id::text));
  if not exists (select 1 from member
                  where household_id = old.household_id and user_id <> old.user_id
                    and role = 'owner' and left_at is null) then
    raise exception 'last_owner' using errcode = 'P0001';
  end if;
  return new;
end $$;
create trigger member_last_owner before update on member
  for each row execute function reject_last_owner_exit();
