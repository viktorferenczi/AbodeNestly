import type { ListItem } from '@abodenestly/contract';
import { nameKey } from '../lib/nameKey.ts';
import { parseQuantity, quantityText } from '../lib/quantity.ts';
import type { HouseholdDb } from '../sync/db.ts';
import { mutate, type MutateDeps } from '../sync/outbox.ts';
import { addItem } from './QuickAdd.ts';

/** What the sheet edits, as the user typed it. */
export type ItemForm = { name: string; quantity: string; unit: string; note: string };
/** What gets stored: trimmed, blanks as null. */
export type ItemFields = { name: string; quantity: number | null; unit: string | null; note: string | null };

export const UNIT_PRESETS = ['pcs', 'g', 'kg', 'ml', 'l', 'pack', 'bottle', 'can'];

/** Null when the form cannot be saved (blank name, bad quantity). */
export function toFields(form: ItemForm): ItemFields | null {
  const name = form.name.trim();
  const qty = parseQuantity(form.quantity);
  if (!name || !qty.ok) return null;
  return { name, quantity: qty.value, unit: form.unit.trim() || null, note: form.note.trim() || null };
}

export const formFrom = (item: Pick<ListItem, 'name' | 'quantity' | 'unit' | 'note'> | null, name = ''): ItemForm => ({
  name: item?.name ?? name,
  quantity: quantityText(item?.quantity ?? null),
  unit: item?.unit ?? '',
  note: item?.note ?? '',
});

/** "2 l", "0.5 kg", "3". A unit without a quantity shows nothing. */
export function formatQty(quantity: number | null, unit: string | null): string {
  if (quantity == null) return '';
  const n = new Intl.NumberFormat(undefined, { maximumFractionDigits: 3 }).format(quantity);
  return unit ? `${n} ${unit}` : n;
}

/** Presets plus the units this household already uses, without duplicates (case-insensitive). */
export function unitSuggestions(items: ListItem[]): string[] {
  const seen = new Set<string>();
  const out: string[] = [];
  for (const u of [...UNIT_PRESETS, ...items.filter((i) => i.deleted_at == null).map((i) => i.unit ?? '')]) {
    const t = u.trim();
    if (!t || seen.has(t.toLowerCase())) continue;
    seen.add(t.toLowerCase());
    out.push(t);
  }
  return out;
}

export const createItem = (
  db: HouseholdDb,
  deps: Partial<MutateDeps>,
  input: { householdId: string; listId: string; userId: string | null; fields: ItemFields },
) => addItem(db, deps, { householdId: input.householdId, listId: input.listId, userId: input.userId, ...input.fields });

/** Writes only the columns that changed, so editing the note does not undo a check-off made on another device. */
export async function updateItem(db: HouseholdDb, deps: Partial<MutateDeps>, before: ListItem, fields: ItemFields): Promise<boolean> {
  const changed: Record<string, unknown> = {};
  for (const key of ['name', 'quantity', 'unit', 'note'] as const) {
    if (fields[key] !== before[key]) changed[key] = fields[key];
  }
  if (Object.keys(changed).length === 0) return false;
  // name_key is server-owned (never pushed); locally it keeps suggestions matching until the next pull.
  await mutate(db, deps).patch('list_item', before.id, changed, 'name' in changed ? { name_key: nameKey(fields.name) } : {});
  return true;
}

export const deleteItem = (db: HouseholdDb, deps: Partial<MutateDeps>, id: string) => mutate(db, deps).remove('list_item', id);
