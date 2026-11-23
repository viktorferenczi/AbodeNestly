import type { Store } from '@abodenestly/contract';
import type { HouseholdDb } from '../sync/db.ts';
import { mutate, type MutateDeps } from '../sync/outbox.ts';

export type StoreRowModel = { id: string; name: string; learned: number };

/** Live stores by name, each with the number of items whose aisle position was learned for it. */
export function storeRows(stores: Store[], orders: { store_id: string; deleted_at?: string | null }[]): StoreRowModel[] {
  const learned = new Map<string, number>();
  for (const o of orders) {
    if (o.deleted_at != null) continue;
    learned.set(o.store_id, (learned.get(o.store_id) ?? 0) + 1);
  }
  return stores
    .filter((s) => s.deleted_at == null)
    .sort((a, b) => a.name.localeCompare(b.name, undefined, { sensitivity: 'base' }))
    .map((s) => ({ id: s.id, name: s.name, learned: learned.get(s.id) ?? 0 }));
}

/** DESIGN C4 wording; nothing to say until something was learned. */
export const learnedLabel = (n: number): string | null => (n <= 0 ? null : `learned ${n} ${n === 1 ? 'item' : 'items'}`);

/** `exceptId` is the store being renamed, so keeping its own name is not a duplicate. */
export function validateStoreName(name: string, stores: Store[], exceptId?: string): 'empty' | 'duplicate' | null {
  const key = name.trim().toLocaleLowerCase();
  if (!key) return 'empty';
  const clash = stores.some((s) => s.deleted_at == null && s.id !== exceptId && s.name.trim().toLocaleLowerCase() === key);
  return clash ? 'duplicate' : null;
}

export async function createStore(db: HouseholdDb, deps: Partial<MutateDeps>, input: { householdId: string; name: string }): Promise<string> {
  const id = (deps.uuid ?? (() => crypto.randomUUID()))();
  const now = (deps.now ?? (() => new Date().toISOString()))();
  // The server owns updated_at and sync_seq; the local row carries placeholders until the next pull replaces it.
  const row: Store = {
    id,
    household_id: input.householdId,
    name: input.name.trim(),
    deleted_at: null,
    created_at: now,
    updated_at: now,
    sync_seq: 0,
  };
  await mutate(db, deps).upsert('store', row);
  return id;
}

// Lists keep their store_id: their chip hides a deleted store, and Recently deleted restores the link with it.
export const deleteStore = (db: HouseholdDb, deps: Partial<MutateDeps>, id: string) => mutate(db, deps).remove('store', id);

// Only store_id travels, so a rename made on another device is not overwritten.
export const setListStore = (db: HouseholdDb, deps: Partial<MutateDeps>, listId: string, storeId: string | null) =>
  mutate(db, deps).patch('shopping_list', listId, { store_id: storeId });
