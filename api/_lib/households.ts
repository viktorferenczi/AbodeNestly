import type { Household, UpdateModulesRequest } from '@abodenestly/contract';
import type { Queryable } from './queryable.ts';
import { ApiError } from './errors.ts';

export function validateHouseholdName(body: { name: string }): string {
  const name = body.name.trim();
  if (!name) throw new ApiError(400, 'validation', 'name is required');
  return name;
}

const iso = (d: Date): string => d.toISOString();

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function toHousehold(r: any): Household {
  return {
    id: r.id,
    name: r.name,
    created_by: r.created_by,
    module_shopping: r.module_shopping,
    module_pantry: r.module_pantry,
    module_maintenance: r.module_maintenance,
    currency: r.currency,
    created_at: iso(r.created_at),
    updated_at: iso(r.updated_at),
    deleted_at: r.deleted_at ? iso(r.deleted_at) : null,
    sync_seq: Number(r.sync_seq),
  };
}

// Two statements, one transaction: is_member is `stable`, so inside a single statement it
// cannot see the owner row create_household just inserted and RLS would hide the household.
export async function createHousehold(client: Queryable, name: string): Promise<Household> {
  const created = await client.query('select create_household($1) as id', [name]);
  const { rows } = await client.query('select * from household where id = $1', [
    created.rows[0]?.id,
  ]);
  const r = rows[0];
  if (!r) throw new Error('create_household returned no readable row');
  return toHousehold(r);
}

export function validateUpdateModules(body: UpdateModulesRequest): UpdateModulesRequest {
  if (body.shopping === undefined && body.pantry === undefined && body.maintenance === undefined) {
    throw new ApiError(400, 'validation', 'At least one of shopping, pantry, maintenance is required');
  }
  return body;
}

const hasCode = (e: unknown, code: string): boolean =>
  typeof e === 'object' && e !== null && (e as { code?: unknown }).code === code;

// One static statement: coalesce keeps flags the caller did not send. Owner-only is enforced by
// the household_guard trigger (42501), not here.
export async function updateModules(
  client: Queryable,
  householdId: string,
  input: UpdateModulesRequest,
): Promise<Household> {
  let rows;
  try {
    ({ rows } = await client.query(
      `update household set
         module_shopping = coalesce($2::boolean, module_shopping),
         module_pantry = coalesce($3::boolean, module_pantry),
         module_maintenance = coalesce($4::boolean, module_maintenance)
       where id = $1 and deleted_at is null
       returning *`,
      [householdId, input.shopping ?? null, input.pantry ?? null, input.maintenance ?? null],
    ));
  } catch (e) {
    if (hasCode(e, '42501')) throw new ApiError(403, 'forbidden', 'Only an owner can change modules');
    throw e;
  }
  // RLS hides non-member, unknown and deleted households alike.
  if (!rows[0]) throw new ApiError(404, 'not_found', 'Household not found');
  return toHousehold(rows[0]);
}
