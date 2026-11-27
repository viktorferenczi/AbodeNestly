import type { ListItem, ShoppingList, Store } from '@abodenestly/contract';
import type { HouseholdDb } from '../sync/db.ts';
import { mutate, type MutateDeps } from '../sync/outbox.ts';

export type ListRowModel = {
  id: string;
  name: string;
  /** Name of the list's store when it points to a live store. */
  storeName: string | null;
  /** "3 of 12" (checked of live items), or "No items". */
  count: string;
};

export type ListSections = { lists: ListRowModel[]; templates: ListRowModel[]; archived: ListRowModel[] };

/** How long a swipe action waits before it is written; Undo inside the window cancels it (DESIGN A6). */
export const UNDO_MS = 5000;

/**
 * Splits a household's lists into the three C1 sections. Lists and Templates: oldest first, so the default list
 * leads and new ones land at the bottom. Archived: newest archived first.
 */
export function listSections(lists: ShoppingList[], items: ListItem[], stores: Store[]): ListSections {
  const storeNames = new Map(stores.filter((s) => s.deleted_at == null).map((s) => [s.id, s.name]));
  const totals = new Map<string, { checked: number; all: number }>();
  for (const item of items) {
    if (item.deleted_at != null) continue;
    const t = totals.get(item.list_id) ?? { checked: 0, all: 0 };
    t.all += 1;
    if (item.checked) t.checked += 1;
    totals.set(item.list_id, t);
  }
  const model = (l: ShoppingList): ListRowModel => {
    const t = totals.get(l.id);
    return {
      id: l.id,
      name: l.name,
      storeName: (l.store_id && storeNames.get(l.store_id)) || null,
      count: t ? `${t.checked} of ${t.all}` : 'No items',
    };
  };
  const live = lists.filter((l) => l.deleted_at == null);
  const byCreated = (a: ShoppingList, b: ShoppingList) => a.created_at.localeCompare(b.created_at);
  const active = live.filter((l) => l.archived_at == null).sort(byCreated);
  return {
    lists: active.filter((l) => !l.is_template).map(model),
    templates: active.filter((l) => l.is_template).map(model),
    archived: live
      .filter((l) => l.archived_at != null)
      .sort((a, b) => b.archived_at!.localeCompare(a.archived_at!))
      .map(model),
  };
}

export async function createList(
  db: HouseholdDb,
  deps: Partial<MutateDeps>,
  input: { householdId: string; name: string; storeId: string | null; isTemplate?: boolean },
): Promise<string> {
  const id = (deps.uuid ?? (() => crypto.randomUUID()))();
  const now = (deps.now ?? (() => new Date().toISOString()))();
  // The server owns updated_at and sync_seq; the local row carries placeholders until the next pull replaces it.
  const row: ShoppingList = {
    id,
    household_id: input.householdId,
    name: input.name.trim(),
    store_id: input.storeId,
    is_template: input.isTemplate ?? false,
    share_token: null,
    archived_at: null,
    deleted_at: null,
    created_at: now,
    updated_at: now,
    sync_seq: 0,
  };
  await mutate(db, deps).upsert('shopping_list', row);
  return id;
}

// Only the changed column travels, so a rename made on another device is not overwritten.
export const archiveList = (db: HouseholdDb, deps: Partial<MutateDeps>, id: string) =>
  mutate(db, deps).patch('shopping_list', id, { archived_at: (deps.now ?? (() => new Date().toISOString()))() });

export const restoreList = (db: HouseholdDb, deps: Partial<MutateDeps>, id: string) =>
  mutate(db, deps).patch('shopping_list', id, { archived_at: null });

export const deleteList = (db: HouseholdDb, deps: Partial<MutateDeps>, id: string) => mutate(db, deps).remove('shopping_list', id);
