import type { Mutation, SyncPushResponse, SyncRejection } from '@abodenestly/contract';
import type { Queryable } from '../queryable.ts';
import { SERVER_OWNED, SYNC_TABLES } from './tables.ts';

type Reason = SyncRejection['reason'];

// Thrown while applying one mutation; becomes a rejection, never a failed push.
export class Reject extends Error {
  constructor(
    public reason: Reason,
    message: string,
  ) {
    super(message);
  }
}

// RLS and privilege failures are 42501; constraint and data errors are classes 22 and 23. Anything
// else is a bug or an outage, so it propagates and rolls the whole push back as a 500.
export function classifyError(e: unknown): { reason: Reason; detail: string } | null {
  const err = e as { code?: unknown; message?: unknown } | null;
  if (typeof err?.code !== 'string') return null;
  const detail = typeof err.message === 'string' ? err.message : 'Rejected by the database';
  if (err.code === '42501') return { reason: 'rls_denied', detail };
  if (err.code.startsWith('22') || err.code.startsWith('23')) return { reason: 'validation', detail };
  return null;
}

// Upsert merge: only the columns present in `data` are written on conflict. Server-owned columns
// are dropped; any other key outside the table's column list is a validation error.
export function buildUpsert(
  table: string,
  columns: readonly string[],
  householdId: string,
  rowId: string,
  data: Record<string, unknown>,
): { sql: string; values: unknown[] } {
  const keys: string[] = [];
  for (const key of Object.keys(data)) {
    if (SERVER_OWNED.has(key)) continue;
    if (!columns.includes(key)) throw new Reject('validation', `Unknown column ${key} for ${table}`);
    keys.push(key);
  }
  const cols = ['id', 'household_id', ...keys];
  const values = [rowId, householdId, ...keys.map((k) => data[k] ?? null)];
  const placeholders = cols.map((_, i) => `$${i + 1}`);
  // The household_id guard keeps an upsert from moving a row that belongs to another household.
  const updates = ['household_id', ...keys].map((c) => `${c} = excluded.${c}`);
  return {
    sql:
      `insert into ${table} as t (${cols.join(', ')}) values (${placeholders.join(', ')}) ` +
      `on conflict (id) do update set ${updates.join(', ')} where t.household_id = excluded.household_id ` +
      'returning t.id',
    values,
  };
}

async function apply(client: Queryable, householdId: string, m: Mutation): Promise<void> {
  const def = SYNC_TABLES[m.table];
  if (!def) throw new Reject('unknown_table', `Unknown table ${m.table}`);
  if (!def.push) throw new Reject('validation', `${m.table} is read-only through sync`);
  if (m.op === 'delta') throw new Reject('validation', 'delta is not supported for this table');
  if (m.op === 'delete') {
    const res = await client.query(
      `update ${m.table} set deleted_at = now() where id = $1 and household_id = $2 returning id`,
      [m.row_id, householdId],
    );
    // Missing or another household's row look the same to the caller.
    if (res.rows.length === 0) throw new Reject('rls_denied', 'Row not found in this household');
    return;
  }
  const { sql, values } = buildUpsert(m.table, def.columns, householdId, m.row_id, m.data);
  const res = await client.query(sql, values);
  if (res.rows.length === 0) throw new Reject('rls_denied', 'Row belongs to another household');
}

// Applies mutations in order inside the caller's transaction. Each runs in its own savepoint so a
// rejection undoes only that mutation (and its idempotency record); the rest still apply.
export async function pushMutations(
  client: Queryable,
  householdId: string,
  mutations: Mutation[],
): Promise<SyncPushResponse> {
  const applied: string[] = [];
  const rejected: SyncRejection[] = [];
  for (const m of mutations) {
    // Unknown tables need no database round trip and are never recorded as applied.
    if (!SYNC_TABLES[m.table]) {
      rejected.push({ id: m.id, reason: 'unknown_table', detail: `Unknown table ${m.table}` });
      continue;
    }
    await client.query('savepoint m');
    try {
      const fresh = (await client.query('select record_sync_mutation($1) as fresh', [m.id])).rows[0]?.fresh;
      if (fresh) await apply(client, householdId, m);
      await client.query('release savepoint m');
      applied.push(m.id);
    } catch (e) {
      const rejection = e instanceof Reject ? { reason: e.reason, detail: e.message } : classifyError(e);
      if (!rejection) throw e;
      await client.query('rollback to savepoint m');
      await client.query('release savepoint m');
      rejected.push({ id: m.id, ...rejection });
    }
  }
  return { applied, rejected };
}
