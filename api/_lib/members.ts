import type { Queryable } from './queryable.ts';
import { ApiError } from './errors.ts';

const hasCode = (e: unknown, code: string): boolean =>
  typeof e === 'object' && e !== null && (e as { code?: unknown }).code === code;

// "Remove" and "leave" are the same soft exit (SPEC §7): set left_at. The member_last_owner
// trigger (CORE-04-db) refuses to leave a household without an active owner.
export async function removeMember(
  client: Queryable,
  householdId: string,
  userId: string,
): Promise<void> {
  // Checked first so an unknown id is a 404 and never reaches the last-owner guard.
  // RLS hides other households' rows, so "not yours" and "does not exist" look the same.
  // An already-left user counts as not a member.
  const { rows: found } = await client.query(
    'select role from member where household_id = $1 and user_id = $2 and left_at is null',
    [householdId, userId],
  );
  if (found.length === 0) throw new ApiError(404, 'not_found', 'Member not found');

  let rows;
  try {
    ({ rows } = await client.query(
      'update member set left_at = now() where household_id = $1 and user_id = $2 and left_at is null returning user_id',
      [householdId, userId],
    ));
  } catch (e) {
    if (hasCode(e, 'P0001') && e instanceof Error && e.message === 'last_owner') {
      throw new ApiError(409, 'last_owner', 'A household needs at least one owner');
    }
    if (hasCode(e, '42501')) throw new ApiError(403, 'forbidden', 'Not allowed to remove this member');
    throw e;
  }
  if (rows.length === 0) {
    // Zero rows is either RLS hiding the row from a plain member (still active: 403) or a
    // concurrent exit that won the race between our SELECT and UPDATE (gone: 404).
    const { rows: again } = await client.query(
      'select 1 from member where household_id = $1 and user_id = $2 and left_at is null',
      [householdId, userId],
    );
    if (again.length === 0) throw new ApiError(404, 'not_found', 'Member not found');
    throw new ApiError(403, 'forbidden', 'Not allowed to remove this member');
  }
}
