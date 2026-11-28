import type { Mutation, MovementReason, SyncTable } from '@abodenestly/contract';
import type { HouseholdDb, OutboxRow } from './db.ts';

/** Columns the server writes itself (SPEC §6.2); they never travel in an upsert's `data`. Mirrors api/_lib/sync/tables.ts. */
const SERVER_OWNED: ReadonlySet<string> = new Set([
  'id',
  'household_id',
  'created_at',
  'updated_at',
  'sync_seq',
  'name_key',
  'share_token',
]);

/** Tables whose `name_key` is a real, client-sent column (the item_price key); everywhere else the server derives it. Mirrors push.ts. */
const CLIENT_NAME_KEY_TABLES: ReadonlySet<string> = new Set(['item_price']);

/** The fields of a write that may go to the server: the row minus the columns the server owns for `table`. */
export function sendableFields(table: string, fields: Record<string, unknown>): Record<string, unknown> {
  return Object.fromEntries(
    Object.entries(fields).filter(([key]) => !SERVER_OWNED.has(key) || (key === 'name_key' && CLIENT_NAME_KEY_TABLES.has(table))),
  );
}

export type MutateDeps = {
  uuid: () => string;
  now: () => string;
  /** Called after each committed write; the caller passes `() => sync.kick()`. */
  onQueued?: () => void;
};

const defaults: MutateDeps = { uuid: () => crypto.randomUUID(), now: () => new Date().toISOString() };

type Row = { id: string };

/**
 * Every shopping and pantry write goes through here (AGENTS.md: offline is a requirement): the local row and the
 * outbox entry commit in one transaction, so they cannot diverge. UI code never calls `fetch` for data writes.
 */
export function mutate(db: HouseholdDb, overrides: Partial<MutateDeps> = {}) {
  const deps = { ...defaults, ...overrides };

  async function commit(table: SyncTable, op: Mutation['op'], payload: Mutation, local: () => Promise<unknown>): Promise<void> {
    await db.transaction('rw', db.table(table), db.outbox, async () => {
      await local();
      const entry: OutboxRow = { table, op, payload };
      await db.outbox.add(entry);
    });
    deps.onQueued?.();
  }

  return {
    /** Stores the full row locally and queues its fields minus the server-owned columns. */
    upsert(table: SyncTable, row: Row): Promise<void> {
      const data = sendableFields(table, row);
      const payload: Mutation = { id: deps.uuid(), table, op: 'upsert', row_id: row.id, data, client_ts: deps.now() };
      return commit(table, 'upsert', payload, () => db.table(table).put(row));
    },
    /**
     * Updates only `fields` locally and queues just those columns, so a concurrent edit of another column on
     * another device is not overwritten (the server merges the columns present in `data`).
     * `localOnly` columns (e.g. a derived `name_key`) are written locally and never queued.
     */
    patch(table: SyncTable, id: string, fields: Record<string, unknown>, localOnly: Record<string, unknown> = {}): Promise<void> {
      const data = sendableFields(table, fields);
      const payload: Mutation = { id: deps.uuid(), table, op: 'upsert', row_id: id, data, client_ts: deps.now() };
      return commit(table, 'upsert', payload, () => db.table(table).update(id, { ...data, ...localOnly }));
    },
    /** Soft delete (SPEC §6.3): the row stays locally with `deleted_at` set. */
    remove(table: SyncTable, id: string): Promise<void> {
      const payload: Mutation = { id: deps.uuid(), table, op: 'delete', row_id: id, client_ts: deps.now() };
      return commit(table, 'delete', payload, () => db.table(table).update(id, { deleted_at: deps.now() }));
    },
    /** Pantry quantity changes are deltas, never absolute writes (AGENTS.md). Pushed once PANT-03-api accepts them. */
    delta(id: string, delta: number, reason: MovementReason): Promise<void> {
      const payload: Mutation = {
        id: deps.uuid(),
        table: 'pantry_item',
        op: 'delta',
        row_id: id,
        data: { field: 'quantity', delta, reason },
        client_ts: deps.now(),
      };
      return commit('pantry_item', 'delta', payload, () =>
        db.table('pantry_item').where('id').equals(id).modify((r: { quantity?: number }) => {
          r.quantity = (r.quantity ?? 0) + delta;
        }),
      );
    },
  };
}
