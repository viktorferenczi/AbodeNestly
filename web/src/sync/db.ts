import Dexie, { type Table } from 'dexie';

// The contract has no ShoppingList type yet; SHOP-01/08 replace this with it and add the other tables.
export type ShoppingListRow = { id: string; household_id: string; name: string; deleted_at: string | null };
export type OutboxRow = { seq?: number; table: string; op: string; payload: unknown };
export type MetaRow = { key: string; value: number };

/** One database per household (SPEC §6.1): own rows, own outbox, own pull cursor. */
export class HouseholdDb extends Dexie {
  shopping_list!: Table<ShoppingListRow, string>;
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
