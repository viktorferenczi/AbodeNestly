import type { ListItem, ShoppingList } from '@abodenestly/contract';
import Dexie from 'dexie';
import { beforeEach, describe, expect, it } from 'vitest';
import { openHouseholdDb } from '../sync/db.ts';
import { createList } from './Lists.ts';
import { addAllToList, targetLists } from './Templates.ts';

const list = (id: string, over: Partial<ShoppingList> = {}) =>
  ({ id, household_id: 'h1', name: id, store_id: null, is_template: false, archived_at: null, deleted_at: null, created_at: `2026-01-0${id.length}T00:00:00Z`, ...over }) as ShoppingList;
const item = (id: string, list_id: string, over: Partial<ListItem> = {}) =>
  ({ id, household_id: 'h1', list_id, name: `N-${id}`, name_key: `n-${id}`, checked: false, deleted_at: null, created_at: `2026-01-01T00:00:0${id.length}Z`, ...over }) as ListItem;

const input = { householdId: 'h1', templateId: 't', targetListId: 'g', userId: 'u1' };
let n = 0;
const deps = () => ({ uuid: () => `new-${++n}`, now: () => '2026-05-05T00:00:00Z' });

async function setup(items: ListItem[]) {
  const db = await openHouseholdDb('h1');
  await db.shopping_list.bulkPut([list('t', { is_template: true }), list('g')]);
  await db.list_item.bulkPut(items);
  return db;
}

beforeEach(async () => {
  n = 0;
  await Dexie.delete('abodenestly-h1');
});

describe('SHOP-11: addAllToList', () => {
  it('AC2 copies each template item as a fresh unchecked row and queues one upsert per item, none for the template', async () => {
    const db = await setup([
      item('1', 't', { name: 'Milk', quantity: 2, unit: 'l', note: 'lactose free', barcode: null }),
      item('22', 't', { name: 'Bread', checked: true, checked_at: '2026-02-01T00:00:00Z', checked_by: 'u2', manual_position: 3 }),
      item('333', 't', { name: 'Eggs' }),
    ]);
    const before = await db.shopping_list.get('t');
    const kicks: number[] = [];
    expect(await addAllToList(db, { ...deps(), onQueued: () => kicks.push(1) }, input)).toBe(3);
    const copies = await db.list_item.where('list_id').equals('g').toArray();
    expect(new Set(copies.map((c) => c.id)).size).toBe(3);
    expect(copies.every((c) => c.id.startsWith('new-'))).toBe(true);
    expect(copies.every((c) => c.checked === false && c.checked_at == null && c.checked_by == null && c.manual_position == null)).toBe(true);
    expect(copies.every((c) => c.created_by === 'u1' && c.source === 'manual' && c.household_id === 'h1')).toBe(true);
    expect(copies.find((c) => c.name === 'Milk')).toMatchObject({ quantity: 2, unit: 'l', note: 'lactose free' });
    const out = (await db.outbox.toArray()).map((o) => o.payload as { table: string; op: string; row_id: string });
    expect(out).toHaveLength(3);
    // Queued in the template's display order: unchecked oldest first, then checked.
    expect(out.map((o) => copies.find((c) => c.id === o.row_id)?.name)).toEqual(['Milk', 'Eggs', 'Bread']);
    expect(out.every((o) => o.table === 'list_item' && o.op === 'upsert')).toBe(true);
    expect(out.some((o) => o.table === 'shopping_list')).toBe(false);
    expect(kicks).toHaveLength(1);
    // The template and its items are untouched.
    expect(await db.shopping_list.get('t')).toEqual(before);
    expect((await db.list_item.where('list_id').equals('t').toArray()).filter((i) => i.checked)).toHaveLength(1);
    await db.close();
  });

  it('skips deleted template items', async () => {
    const db = await setup([item('1', 't', { name: 'Milk' }), item('22', 't', { deleted_at: '2026-02-01T00:00:00Z' })]);
    expect(await addAllToList(db, deps(), input)).toBe(1);
    expect((await db.list_item.where('list_id').equals('g').toArray()).map((i) => i.name)).toEqual(['Milk']);
    await db.close();
  });

  it('AC3 an empty template writes nothing and returns 0', async () => {
    const db = await setup([item('1', 'g', { name: 'Already' }), item('22', 't', { deleted_at: '2026-02-01T00:00:00Z' })]);
    const kicks: number[] = [];
    expect(await addAllToList(db, { ...deps(), onQueued: () => kicks.push(1) }, input)).toBe(0);
    expect(await db.outbox.count()).toBe(0);
    expect(await db.list_item.where('list_id').equals('g').count()).toBe(1);
    expect(kicks).toHaveLength(0);
    await db.close();
  });

  it('a failure inside the copy leaves no partial rows', async () => {
    const db = await setup([item('1', 't'), item('22', 't'), item('333', 't')]);
    let calls = 0;
    const kicks: number[] = [];
    const failing = {
      ...deps(),
      uuid: () => {
        if (++calls === 3) throw new Error('boom');
        return `new-${calls}`;
      },
      onQueued: () => kicks.push(1),
    };
    await expect(addAllToList(db, failing, input)).rejects.toThrow();
    expect(await db.list_item.where('list_id').equals('g').count()).toBe(0);
    expect(await db.outbox.count()).toBe(0);
    expect(kicks).toHaveLength(0);
    await db.close();
  });
});

describe('SHOP-11: createList and targetLists', () => {
  it('createList with isTemplate writes is_template locally and in the outbox data', async () => {
    const db = await openHouseholdDb('h1');
    const id = await createList(db, deps(), { householdId: 'h1', name: 'Weekly', storeId: null, isTemplate: true });
    expect((await db.shopping_list.get(id))?.is_template).toBe(true);
    const out = (await db.outbox.toArray())[0]!.payload as { data: Record<string, unknown> };
    expect(out.data.is_template).toBe(true);
    const plain = await createList(db, deps(), { householdId: 'h1', name: 'Plain', storeId: null });
    expect((await db.shopping_list.get(plain))?.is_template).toBe(false);
    await db.close();
  });

  it('targetLists keeps active non-templates, oldest first', () => {
    const got = targetLists([
      list('bb'),
      list('a', { created_at: '2025-01-01T00:00:00Z' }),
      list('ccc', { is_template: true }),
      list('dddd', { archived_at: 'x' }),
      list('eeeee', { deleted_at: 'x' }),
    ]);
    expect(got.map((l) => l.id)).toEqual(['a', 'bb']);
  });
});
