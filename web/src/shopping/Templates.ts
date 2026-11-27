import type { ListItem, ShoppingList } from '@abodenestly/contract';
import type { HouseholdDb } from '../sync/db.ts';
import { mutate, type MutateDeps } from '../sync/outbox.ts';

/** Lists a template can be added to: active (not archived, not deleted) and not a template, oldest first like C1. */
export function targetLists(lists: ShoppingList[]): ShoppingList[] {
  return lists
    .filter((l) => l.deleted_at == null && l.archived_at == null && !l.is_template)
    .sort((a, b) => a.created_at.localeCompare(b.created_at));
}

/**
 * SHOP-11: copies every live item of a template onto `targetListId` as fresh unchecked rows and returns how many.
 * The copy is one outer Dexie transaction, so it is all or nothing. One upsert is queued per item and none for the template.
 */
export async function addAllToList(
  db: HouseholdDb,
  deps: Partial<MutateDeps>,
  input: { householdId: string; templateId: string; targetListId: string; userId: string | null },
): Promise<number> {
  const uuid = deps.uuid ?? (() => crypto.randomUUID());
  const now = (deps.now ?? (() => new Date().toISOString()))();
  // onQueued is held back until the whole copy has committed, so a rolled-back copy never kicks the sync.
  const inner = { ...deps, onQueued: undefined };
  const count = await db.transaction('rw', db.list_item, db.outbox, async () => {
    const live = (await db.list_item.where('household_id').equals(input.householdId).toArray()).filter((i) => i.list_id === input.templateId && i.deleted_at == null);
    // The template's display order: unchecked oldest first, then checked, newest check first (as ListDetail shows them).
    const byCreated = (a: ListItem, b: ListItem) => a.created_at.localeCompare(b.created_at);
    const ordered = [
      ...live.filter((i) => !i.checked).sort(byCreated),
      ...live.filter((i) => i.checked).sort((a, b) => (b.checked_at ?? '').localeCompare(a.checked_at ?? '')),
    ];
    for (const src of ordered) {
      const row: ListItem = {
        id: uuid(),
        household_id: input.householdId,
        list_id: input.targetListId,
        name: src.name,
        name_key: src.name_key,
        quantity: src.quantity ?? null,
        unit: src.unit ?? null,
        note: src.note ?? null,
        barcode: src.barcode ?? null,
        checked: false,
        checked_at: null,
        checked_by: null,
        manual_position: null,
        source: 'manual',
        pantry_item_id: null,
        maint_task_id: null,
        created_by: input.userId,
        deleted_at: null,
        created_at: now,
        updated_at: now,
        sync_seq: 0,
      };
      await mutate(db, { ...inner, uuid }).upsert('list_item', row);
    }
    return ordered.length;
  });
  if (count > 0) deps.onQueued?.();
  return count;
}
