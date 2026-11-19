import type { HouseholdDb, OutboxRow } from '../sync/db.ts';
import type { Modules } from './household/HouseholdProvider.tsx';
import { MODULE_ORDER, MODULE_TABLES, type SearchModule, type SearchTable } from './Search.ts';

/** SPEC §5: the server purges rows soft-deleted for more than this many days. */
export const RETENTION_DAYS = 30;
const DAY_MS = 86_400_000;

/** A soft-deleted local row; `maint_task` supplies its `title` as `name`. */
export type DeletedRow = { table: SearchTable; id: string; name: string; deleted_at: string };
export type DeletedItem = { key: string; table: SearchTable; id: string; name: string; deletedAt: string; daysLeft: number };
export type DeletedSection = { module: SearchModule; items: DeletedItem[] };

export type RestoreDeps = { uuid: () => string; now: () => string };
/** SPEC §6.2 envelope. `data` is partial on purpose: the server merges it, so a rename made elsewhere survives. */
export type RestoreEnvelope = {
  id: string;
  table: SearchTable;
  op: 'upsert';
  row_id: string;
  data: { deleted_at: null };
  client_ts: string;
};

/** Whole days elapsed are subtracted, so a row deleted a moment ago has 30 days left. */
export function daysLeft(deletedAt: string, now: Date): number {
  const elapsed = Math.max(0, now.getTime() - new Date(deletedAt).getTime());
  return RETENTION_DAYS - Math.floor(elapsed / DAY_MS);
}

const MODULE_OF = Object.fromEntries(
  MODULE_ORDER.flatMap((m) => MODULE_TABLES[m].map((t) => [t, m] as const)),
) as Record<SearchTable, SearchModule>;

/**
 * One section per enabled module, in module order. Rows with no days left are dropped: the server has purged
 * (or is about to purge) them and sends no tombstone, so this age filter is what keeps them off the screen.
 * Newest deleted first.
 */
export function deletedSections(rows: readonly DeletedRow[], modules: Modules, now: Date): DeletedSection[] {
  return MODULE_ORDER.filter((m) => modules[m]).map((module) => {
    const items = rows
      .filter((r) => MODULE_OF[r.table] === module)
      .map((r) => ({ key: `${r.table}:${r.id}`, table: r.table, id: r.id, name: r.name, deletedAt: r.deleted_at, daysLeft: daysLeft(r.deleted_at, now) }))
      .filter((i) => i.daysLeft > 0)
      .sort((a, b) => (a.deletedAt < b.deletedAt ? 1 : a.deletedAt > b.deletedAt ? -1 : a.key < b.key ? -1 : 1));
    return { module, items };
  });
}

export function restoreEnvelope(table: SearchTable, rowId: string, deps: RestoreDeps): RestoreEnvelope {
  return { id: deps.uuid(), table, op: 'upsert', row_id: rowId, data: { deleted_at: null }, client_ts: deps.now() };
}

/** Clears `deleted_at` locally and queues the upsert in one transaction, so the two cannot diverge. */
export async function restore(db: HouseholdDb, table: SearchTable, rowId: string, deps: RestoreDeps): Promise<void> {
  const envelope = restoreEnvelope(table, rowId, deps);
  await db.transaction('rw', db.table(table), db.outbox, async () => {
    await db.table(table).update(rowId, { deleted_at: null });
    const entry: OutboxRow = { table, op: 'upsert', payload: envelope };
    await db.outbox.add(entry);
  });
}
