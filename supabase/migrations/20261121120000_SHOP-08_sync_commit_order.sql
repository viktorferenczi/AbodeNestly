-- SHOP-08 (#514): make sync_seq follow commit order within a household.
-- nextval() is taken when a row is written, not when its transaction commits, so a pull could
-- see seq 11 (committed) while seq 10 was still in flight and then never return it.
-- A transaction-scoped advisory lock per household, held until commit, means a later writer
-- cannot take a sequence number until the earlier one has committed.
-- pushMutations takes the same lock first, so row locks are always taken while it is held.
create or replace function set_sync_fields() returns trigger
language plpgsql as $$
declare
  hid uuid;
begin
  -- A TG_TABLE_NAME branch, not a CASE: plpgsql only plans the branch that runs, and
  -- household has no household_id field.
  if tg_table_name = 'household' then
    hid := new.id;
  else
    hid := new.household_id;
  end if;
  -- Serialise per household until commit so sync_seq order equals commit order (#514).
  perform pg_advisory_xact_lock(hashtextextended('sync_seq:' || hid::text, 0));
  new.updated_at := now();
  new.sync_seq := nextval('sync_seq_global');
  return new;
end $$;
