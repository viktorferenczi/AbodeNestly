import type { ListItem } from '@abodenestly/contract';
import { nameKey } from '../lib/nameKey.ts';
import type { HouseholdDb } from '../sync/db.ts';
import { mutate, type MutateDeps } from '../sync/outbox.ts';

export type Suggestion = { key: string; label: string };

export const SUGGESTION_LIMIT = 5;

const keyOf = (row: ListItem) => row.name_key || nameKey(row.name);

/**
 * SHOP-02: what counts as the household's item history. Live rows, plus rows that were checked (bought) and then
 * cleared or deleted. A row typed and deleted unchecked is not history, so typos do not haunt the suggestions.
 */
export const historyOf = (items: ListItem[]): ListItem[] => items.filter((i) => i.deleted_at == null || i.checked);

/**
 * Suggestions for `query`, matched on `name_key`. A name matches when its key, or any word in it, starts with the
 * query ("mi" finds Milk and Oat milk, not Salami). Rows show the casing of the household's most recent entry.
 * Names already unchecked on `listId` are left out so a suggestion never makes a duplicate line.
 */
export function suggest(history: ListItem[], query: string, opts: { listId: string; limit?: number }): Suggestion[] {
  const q = nameKey(query);
  if (!q) return [];
  const onList = new Set(history.filter((i) => i.list_id === opts.listId && i.deleted_at == null && !i.checked).map(keyOf));
  const groups = new Map<string, { label: string; latest: string; count: number }>();
  for (const row of history) {
    const key = keyOf(row);
    const g = groups.get(key);
    if (!g) groups.set(key, { label: row.name, latest: row.created_at, count: 1 });
    else {
      g.count += 1;
      if (row.created_at > g.latest) Object.assign(g, { label: row.name, latest: row.created_at });
    }
  }
  const ranked: { key: string; label: string; prefix: boolean; count: number; latest: string }[] = [];
  for (const [key, g] of groups) {
    if (onList.has(key)) continue;
    const prefix = key.startsWith(q);
    if (!prefix && !key.split(' ').some((w) => w.startsWith(q))) continue;
    ranked.push({ key, label: g.label, prefix, count: g.count, latest: g.latest });
  }
  ranked.sort((a, b) => Number(b.prefix) - Number(a.prefix) || b.count - a.count || b.latest.localeCompare(a.latest) || a.key.localeCompare(b.key));
  return ranked.slice(0, opts.limit ?? SUGGESTION_LIMIT).map(({ key, label }) => ({ key, label }));
}

/** Adds an unchecked row through the outbox. A checked row of the same name stays; re-adding makes a new line. */
export async function addItem(
  db: HouseholdDb,
  deps: Partial<MutateDeps>,
  input: { householdId: string; listId: string; name: string; userId: string | null; quantity?: number | null; unit?: string | null; note?: string | null },
): Promise<string> {
  const id = (deps.uuid ?? (() => crypto.randomUUID()))();
  const now = (deps.now ?? (() => new Date().toISOString()))();
  const name = input.name.trim();
  // The server derives name_key itself; the local value only serves matching until the next pull replaces the row.
  const row: ListItem = {
    id,
    household_id: input.householdId,
    list_id: input.listId,
    name,
    name_key: nameKey(name),
    quantity: input.quantity ?? null,
    unit: input.unit ?? null,
    note: input.note ?? null,
    barcode: null,
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
  await mutate(db, deps).upsert('list_item', row);
  return id;
}
