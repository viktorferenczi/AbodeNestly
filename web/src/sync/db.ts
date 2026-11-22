import type { Activity, Attachment, AttachmentEntity, NotificationPref, SyncRejection } from '@abodenestly/contract';
import Dexie, { type Table } from 'dexie';

// The contract has no ShoppingList type yet; SHOP-01/08 replace this with it and add the other tables.
export type ShoppingListRow = { id: string; household_id: string; name: string; deleted_at: string | null };
// Minimal local rows for CORE-09 search; each module's list story appends columns with its own version bump.
type NamedRow = { id: string; household_id: string; name: string; deleted_at: string | null };
export type ListItemRow = NamedRow & { list_id: string };
export type MaintTaskRow = { id: string; household_id: string; title: string; deleted_at: string | null };
export type OutboxRow = { seq?: number; table: string; op: string; payload: unknown };
/** SHOP-08: a mutation the server rejected. Same `seq` as the outbox row it came from, so a retry keeps its place. */
export type OutboxFailedRow = {
  seq: number;
  table: string;
  op: string;
  payload: unknown;
  reason: SyncRejection['reason'];
  detail: string;
  failed_at: string;
};
/** Pull-only tables (SPEC §6.3) whose rows the client stores as received. */
type PulledRow = { household_id: string };
/**
 * CORE-11: a pick made offline. Separate from `outbox` on purpose: /sync carries rows, not bytes, so the sync
 * loop (SHOP-08) must not drain this queue. The attachment control drains this queue itself when online.
 */
export type AttachmentOutboxRow = {
  id: string;
  household_id: string;
  entity: AttachmentEntity;
  entity_id: string;
  filename: string;
  mime: string;
  size: number;
  /** An ArrayBuffer, not a Blob: it clones under every IndexedDB implementation, fake-indexeddb included. */
  bytes: ArrayBuffer;
  created_at: string;
};
export type MetaRow = { key: string; value: number };

/** One database per household (SPEC §6.1): own rows, own outbox, own pull cursor. */
export class HouseholdDb extends Dexie {
  shopping_list!: Table<ShoppingListRow, string>;
  activity!: Table<Activity, string>;
  list_item!: Table<ListItemRow, string>;
  pantry_item!: Table<NamedRow, string>;
  recipe!: Table<NamedRow, string>;
  asset!: Table<NamedRow, string>;
  maint_task!: Table<MaintTaskRow, string>;
  store!: Table<NamedRow, string>;
  location!: Table<NamedRow, string>;
  contractor!: Table<NamedRow, string>;
  attachment!: Table<Attachment, string>;
  attachment_outbox!: Table<AttachmentOutboxRow, string>;
  notification_pref!: Table<NotificationPref, [string, string, string]>;
  outbox!: Table<OutboxRow, number>;
  outbox_failed!: Table<OutboxFailedRow, number>;
  household!: Table<{ id: string }, string>;
  member!: Table<PulledRow & { user_id: string }, [string, string]>;
  invite!: Table<PulledRow & { id: string }, string>;
  store_item_order!: Table<PulledRow & { store_id: string; name_key: string }, [string, string]>;
  meta!: Table<MetaRow, string>;

  constructor(householdId: string) {
    super(dbName(householdId));
    // Version bumps are append-only (react-web skill).
    this.version(1).stores({
      shopping_list: 'id, household_id',
      outbox: '++seq',
      meta: 'key',
    });
    // CORE-08: the Home feed reads one household's rows by time.
    this.version(2).stores({ activity: 'id, household_id, created_at' });
    // CORE-09: names that household-wide search reads, per module table.
    this.version(3).stores({
      list_item: 'id, household_id, list_id',
      pantry_item: 'id, household_id',
      recipe: 'id, household_id',
      asset: 'id, household_id',
      maint_task: 'id, household_id',
      store: 'id, household_id',
      location: 'id, household_id',
      contractor: 'id, household_id',
    });
    // CORE-11: attachments of pantry items, assets and log entries, plus the offline upload queue.
    this.version(4).stores({
      attachment: 'id, [entity+entity_id]',
      attachment_outbox: 'id, created_at',
    });
    // CORE-12: my switches for this household. Keyed by the server's PK; no `id`, no soft delete (a missing row means enabled).
    this.version(5).stores({ notification_pref: '[user_id+household_id+type], household_id' });
    // SHOP-08: rejected mutations, and the pull-only tables the sync loop now stores. Rule: any later version that
    // adds a pulled table must reset the cursor like this one does, or rows below the old cursor never arrive.
    this.version(6)
      .stores({
        outbox_failed: 'seq',
        household: 'id',
        member: '[household_id+user_id], household_id',
        invite: 'id, household_id',
        store_item_order: '[store_id+name_key], household_id',
      })
      .upgrade((tx) => tx.table('meta').put({ key: 'cursor', value: 0 }));
  }
}

export const dbName = (householdId: string) => `abodenestly-${householdId}`;

export async function openHouseholdDb(householdId: string): Promise<HouseholdDb> {
  const db = new HouseholdDb(householdId);
  await db.open();
  if ((await db.meta.get('cursor')) === undefined) await db.meta.put({ key: 'cursor', value: 0 });
  return db;
}

export async function readCursor(db: HouseholdDb): Promise<number> {
  return (await db.meta.get('cursor'))?.value ?? 0;
}

/** The sync loop moves the cursor after each pulled page. */
export async function writeCursor(db: HouseholdDb, value: number): Promise<void> {
  await db.meta.put({ key: 'cursor', value });
}

export async function failedCount(db: HouseholdDb): Promise<number> {
  return db.outbox_failed.count();
}

/**
 * Queued writes of a household. Counts on `open` when that is the household's own open database; otherwise
 * uses a short-lived connection, which callers must not run while the same name is being opened.
 */
export async function pendingCount(householdId: string, open?: HouseholdDb | null): Promise<number> {
  if (open && open.name === dbName(householdId) && open.isOpen()) return open.outbox.count();
  const db = new HouseholdDb(householdId);
  try {
    await db.open();
    return await db.outbox.count();
  } finally {
    db.close();
  }
}
