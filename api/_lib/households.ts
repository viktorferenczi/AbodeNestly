import type { Household } from '@abodenestly/contract';
import type { Queryable } from './queryable.ts';
import { ApiError } from './errors.ts';

export function validateHouseholdName(body: { name: string }): string {
  const name = body.name.trim();
  if (!name) throw new ApiError(400, 'validation', 'name is required');
  return name;
}

const iso = (d: Date): string => d.toISOString();

// Two statements, one transaction: is_member is `stable`, so inside a single statement it
// cannot see the owner row create_household just inserted and RLS would hide the household.
export async function createHousehold(client: Queryable, name: string): Promise<Household> {
  const created = await client.query('select create_household($1) as id', [name]);
  const { rows } = await client.query('select * from household where id = $1', [
    created.rows[0]?.id,
  ]);
  const r = rows[0];
  if (!r) throw new Error('create_household returned no readable row');
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
