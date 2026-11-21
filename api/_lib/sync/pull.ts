import type { SyncPullResponse, SyncTable } from '@abodenestly/contract';
import type { Queryable } from '../queryable.ts';
import { SYNC_TABLES } from './tables.ts';

export const DEFAULT_PULL_LIMIT = 500;

// One query ordered by sync_seq across every table, so a page is a contiguous slice of the global
// sequence. Per-table queries can skip rows: if one table fills the limit at seq 1000 while another
// returns rows up to 2000, a cursor of 2000 would lose the first table's rows between 1001 and 2000.
export function buildPullSql(): string {
  const parts = Object.entries(SYNC_TABLES).map(
    ([table, def]) =>
      `select '${table}' as t, r.sync_seq as seq, to_jsonb(r) as row from ${table} r where r.${def.scope} = $1 and r.sync_seq > $2`,
  );
  return `${parts.join('\nunion all\n')}\norder by seq limit $3`;
}

export async function pullChanges(
  client: Queryable,
  householdId: string,
  since: number,
  limit: number,
): Promise<SyncPullResponse> {
  // One extra row tells us whether another page exists.
  const { rows } = await client.query(buildPullSql(), [householdId, since, limit + 1]);
  const page = rows.slice(0, limit);
  const changes: SyncPullResponse['changes'] = {};
  for (const r of page) {
    const table = r.t as SyncTable;
    (changes[table] ??= []).push(r.row);
  }
  const last = page[page.length - 1];
  return {
    // bigint comes back from pg as a string; sequence values stay far below 2^53.
    cursor: last ? Number(last.seq) : since,
    has_more: rows.length > limit,
    changes,
  };
}
