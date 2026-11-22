import type { ListItem } from '@abodenestly/contract';
import Dexie from 'dexie';
import { beforeEach, describe, expect, it } from 'vitest';
import { openHouseholdDb } from '../sync/db.ts';
import { addItem, historyOf, suggest } from './QuickAdd.ts';

let n = 0;
const item = (name: string, over: Partial<ListItem> = {}) =>
  ({
    id: `i${++n}`,
    household_id: 'h1',
    list_id: 'other',
    name,
    name_key: name.trim().replace(/\s+/g, ' ').toLowerCase(),
    checked: true,
    deleted_at: null,
    created_at: `2026-01-01T00:00:${String(n).padStart(2, '0')}Z`,
    ...over,
  }) as ListItem;
const labels = (items: ListItem[], q: string, listId = 'here') => suggest(historyOf(items), q, { listId }).map((s) => s.label);

beforeEach(async () => {
  n = 0;
  await Dexie.delete('abodenestly-h1');
});

describe('SHOP-02: suggest', () => {
  it('AC1: "mi" suggests Milk bought on another list, whatever the case or padding', () => {
    const items = [item('Milk')];
    expect(labels(items, 'mi')).toEqual(['Milk']);
    expect(labels(items, 'MI')).toEqual(['Milk']);
    expect(labels(items, ' mi')).toEqual(['Milk']);
  });

  it('groups by name_key and shows the most recent casing', () => {
    expect(labels([item('milk'), item('Milk')], 'mi')).toEqual(['Milk']);
  });

  it.each([
    ['excludes deleted-unchecked rows', [item('Milk', { checked: false, deleted_at: 'x' })], 'mi', []],
    ['includes deleted-checked rows', [item('Milk', { deleted_at: 'x' })], 'mi', ['Milk']],
    ['matches a word start', [item('Oat milk')], 'mi', ['Oat milk']],
    ['has no infix match', [item('Salami')], 'mi', []],
    ['excludes names already unchecked on this list', [item('Milk', { list_id: 'here', checked: false })], 'mi', []],
    ['still suggests a name that is only checked on this list', [item('Milk', { list_id: 'here' })], 'mi', ['Milk']],
    ['returns nothing for a blank query', [item('Milk')], '   ', []],
  ])('%s', (_name, items, q, expected) => {
    expect(labels(items, q)).toEqual(expected);
  });

  it('orders whole-key prefix, then count, then recency', () => {
    const items = [item('Oat milk'), item('Oat milk'), item('Milk'), item('Mint'), item('Mint')];
    expect(labels(items, 'mi')).toEqual(['Mint', 'Milk', 'Oat milk']);
  });

  it('limits to 5', () => {
    const items = ['m1', 'm2', 'm3', 'm4', 'm5', 'm6'].map((x) => item(x));
    expect(labels(items, 'm')).toHaveLength(5);
  });
});

describe('SHOP-02: addItem', () => {
  const deps = { uuid: () => 'mut-1', now: () => '2026-02-01T00:00:00Z' };

  it('AC2/AC3: writes the typed or picked name as a manual row; name_key stays local', async () => {
    const db = await openHouseholdDb('h1');
    const id = await addItem(db, { ...deps }, { householdId: 'h1', listId: 'L', name: '  Milk ', userId: 'u1' });
    const row = await db.list_item.get(id);
    expect(row).toMatchObject({ name: 'Milk', name_key: 'milk', source: 'manual', created_by: 'u1', checked: false, list_id: 'L' });
    const out = await db.outbox.toArray();
    expect(out).toHaveLength(1);
    expect(out[0]!.payload).toMatchObject({ table: 'list_item', op: 'upsert', row_id: id, data: { name: 'Milk', list_id: 'L' } });
    expect((out[0]!.payload as { data: Record<string, unknown> }).data).not.toHaveProperty('name_key');
    await db.close();
  });

  it('AC4: offline it still leaves the row and exactly one outbox upsert', async () => {
    const original = Object.getOwnPropertyDescriptor(Navigator.prototype, 'onLine');
    Object.defineProperty(navigator, 'onLine', { value: false, configurable: true });
    const db = await openHouseholdDb('h1');
    await addItem(db, { ...deps }, { householdId: 'h1', listId: 'L', name: 'Milk', userId: null });
    expect(await db.list_item.count()).toBe(1);
    expect(await db.outbox.count()).toBe(1);
    await db.close();
    delete (navigator as { onLine?: boolean }).onLine;
    if (original) Object.defineProperty(Navigator.prototype, 'onLine', original);
  });
});
