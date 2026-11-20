import type { Activity, Attachment, AttachmentEntity, NotificationPref } from '@abodenestly/contract';
import Dexie, { type Table } from 'dexie';

// The contract has no ShoppingList type yet; SHOP-01/08 replace this with it and add the other tables.
export type ShoppingListRow = { id: string; household_id: string; name: string; deleted_at: string | null };
// Minimal local rows for CORE-09 search; each module's list story appends columns with its own version bump.
type NamedRow = { id: string; household_id: string; name: string; deleted_at: string | null };
export type ListItemRow = NamedRow & { list_id: string };
export type MaintTaskRow = { id: string; household_id: string; title: string; deleted_at: string | null };
export type OutboxRow = { seq?: number; table: string; op: string; payload: unknown };
/**
 * CORE-11: a pick made offline. Separate from `outbox` on purpose: /sync carries rows, not bytes, and nothing
 * drains `outbox` yet (SHOP-08). The attachment control drains this queue itself when online.
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

/** SHOP-08's pull loop moves the cursor; this story only stores it. */
export async function writeCursor(db: HouseholdDb, value: number): Promise<void> {
  await db.meta.put({ key: 'cursor', value });
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
