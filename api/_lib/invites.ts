import type { AcceptInviteResponse, CreateInviteRequest, Invite } from '@abodenestly/contract';
import type { Queryable } from './queryable.ts';
import { ApiError } from './errors.ts';

// SPEC gives no bounds; these are sanity caps.
export function validateCreateInvite(body: CreateInviteRequest): CreateInviteRequest {
  const { expires_in_days: days, max_uses: uses } = body;
  if (days !== undefined && (days < 1 || days > 365)) {
    throw new ApiError(400, 'validation', 'expires_in_days must be 1-365');
  }
  if (uses !== undefined && (uses < 1 || uses > 1000)) {
    throw new ApiError(400, 'validation', 'max_uses must be 1-1000');
  }
  return body;
}

// Matches the DB default: encode(gen_random_bytes(6), 'hex').
export const isInviteCode = (s: string): boolean => /^[0-9a-f]{12}$/.test(s);

export const isUuid = (s: string): boolean =>
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(s);

const iso = (d: Date): string => d.toISOString();

const hasCode = (e: unknown, code: string): boolean =>
  typeof e === 'object' && e !== null && (e as { code?: unknown }).code === code;

// Optional columns are appended from this fixed list only when provided, so the DB defaults
// (member / 7 days / 10 uses) stay the single source of truth.
export async function createInvite(
  client: Queryable,
  householdId: string,
  input: CreateInviteRequest,
): Promise<Invite> {
  const columns = ['household_id', 'created_by'];
  const values: unknown[] = [householdId];
  const exprs = ['$1', 'auth.uid()'];
  if (input.role !== undefined) {
    values.push(input.role);
    columns.push('role');
    exprs.push(`$${values.length}`);
  }
  if (input.expires_in_days !== undefined) {
    values.push(input.expires_in_days);
    columns.push('expires_at');
    exprs.push(`now() + make_interval(days => $${values.length})`);
  }
  if (input.max_uses !== undefined) {
    values.push(input.max_uses);
    columns.push('max_uses');
    exprs.push(`$${values.length}`);
  }
  let rows;
  try {
    ({ rows } = await client.query(
      `insert into invite (${columns.join(', ')}) values (${exprs.join(', ')}) returning *`,
      values,
    ));
  } catch (e) {
    // RLS insert policy: non-member, or a member minting an owner invite.
    if (hasCode(e, '42501')) throw new ApiError(403, 'forbidden', 'Not allowed to create this invite');
    throw e;
  }
  const r = rows[0];
  if (!r) throw new Error('insert returned no row');
  return {
    id: r.id,
    household_id: r.household_id,
    code: r.code,
    role: r.role,
    created_by: r.created_by,
    expires_at: iso(r.expires_at),
    max_uses: r.max_uses,
    uses: r.uses,
    created_at: iso(r.created_at),
    updated_at: iso(r.updated_at),
    deleted_at: r.deleted_at ? iso(r.deleted_at) : null,
    sync_seq: Number(r.sync_seq),
  };
}

export async function acceptInvite(client: Queryable, code: string): Promise<AcceptInviteResponse> {
  try {
    const { rows } = await client.query('select accept_invite($1) as household_id', [code]);
    return { household_id: rows[0]?.household_id };
  } catch (e) {
    if (e instanceof Error && e.message === 'invalid_invite') {
      throw new ApiError(400, 'invalid_invite', 'Invite is invalid, expired or used up');
    }
    throw e;
  }
}
