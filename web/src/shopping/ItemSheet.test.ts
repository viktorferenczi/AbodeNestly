import type { ListItem } from '@abodenestly/contract';
import Dexie from 'dexie';
import { beforeEach, describe, expect, it } from 'vitest';
import { parseQuantity, quantityText, stepQuantity } from '../lib/quantity.ts';
import { openHouseholdDb } from '../sync/db.ts';
import { createItem, deleteItem, formatQty, toFields, unitSuggestions, updateItem } from './ItemSheet.ts';

let n = 0;
const deps = () => ({ uuid: () => `m${++n}`, now: () => '2026-02-01T00:00:00Z' });
const blank = { name: 'Milk', quantity: '', unit: '', note: '' };

beforeEach(async () => {
  n = 0;
  await Dexie.delete('abodenestly-h1');
});

async function seedItem(over: Partial<ListItem> = {}) {
  const db = await openHouseholdDb('h1');
  const id = await createItem(db, deps(), { householdId: 'h1', listId: 'l1', userId: 'u1', fields: { name: 'Milk', quantity: 2, unit: 'l', note: 'lactose free' } });
  await db.outbox.clear();
  if (Object.keys(over).length) await db.list_item.update(id, over);
  return { db, id, row: (await db.list_item.get(id))! };
}

describe('SHOP-03: toFields', () => {
  it.each([
    ['2', 2],
    ['0,5', 0.5],
    ['0.25', 0.25],
    [' 3 ', 3],
    ['', null],
  ])('quantity "%s" -> %s', (q, expected) => {
    expect(toFields({ ...blank, quantity: q })?.quantity).toBe(expected);
  });

  it.each(['1.2345', '0', '-1', 'abc', '1,5,5'])('quantity "%s" is invalid', (q) => {
    expect(toFields({ ...blank, quantity: q })).toBeNull();
  });

  it('trims, and turns blank unit and note into null', () => {
    expect(toFields({ name: '  Milk ', quantity: '', unit: '  l ', note: '   ' })).toEqual({ name: 'Milk', quantity: null, unit: 'l', note: null });
  });

  it('a blank name cannot be saved', () => {
    expect(toFields({ ...blank, name: '  ' })).toBeNull();
  });
});

describe('SHOP-03: quantity helpers', () => {
  it('stepQuantity: + on empty is 1, − at 1 or below clears, decimals survive', () => {
    expect(stepQuantity('', 1)).toBe('1');
    expect(stepQuantity('1', 1)).toBe('2');
    expect(stepQuantity('0,5', 1)).toBe('1.5');
    expect(stepQuantity('3', -1)).toBe('2');
    expect(stepQuantity('1', -1)).toBe('');
    expect(stepQuantity('0.5', -1)).toBe('');
    expect(stepQuantity('', -1)).toBe('');
    expect(stepQuantity('abc', 1)).toBe('abc');
  });
  it('parseQuantity and quantityText', () => {
    expect(parseQuantity('0')).toEqual({ ok: false });
    expect(quantityText(0.1 + 0.2)).toBe('0.3');
    expect(quantityText(null)).toBe('');
  });
});

describe('SHOP-03: formatQty', () => {
  it.each([
    [2, 'l', '2 l'],
    [0.5, 'kg', '0.5 kg'],
    [3, null, '3'],
    [null, 'l', ''],
    [null, null, ''],
  ])('(%s, %s) -> "%s"', (q, u, expected) => {
    expect(formatQty(q, u).replace(/ /g, ' ')).toBe(expected);
  });
});

describe('SHOP-03: unitSuggestions', () => {
  it('lists presets, then the household units, without duplicates', () => {
    const items = [
      { unit: 'bunch', deleted_at: null },
      { unit: 'L', deleted_at: null },
      { unit: 'gone', deleted_at: 'x' },
      { unit: null, deleted_at: null },
    ] as ListItem[];
    const units = unitSuggestions(items);
    expect(units.slice(0, 8)).toEqual(['pcs', 'g', 'kg', 'ml', 'l', 'pack', 'bottle', 'can']);
    expect(units.slice(8)).toEqual(['bunch']);
  });
});

describe('SHOP-03: writes', () => {
  it('AC1: creating with blanks stores nulls and queues one upsert with null columns', async () => {
    const db = await openHouseholdDb('h1');
    const id = await createItem(db, deps(), { householdId: 'h1', listId: 'l1', userId: 'u1', fields: { name: 'Milk', quantity: null, unit: null, note: null } });
    expect(await db.list_item.get(id)).toMatchObject({ quantity: null, unit: null, note: null, checked: false });
    const out = await db.outbox.toArray();
    expect(out).toHaveLength(1);
    expect(out[0]!.payload).toMatchObject({ op: 'upsert', data: { quantity: null, unit: null, note: null } });
    await db.close();
  });

  it('AC3: clearing the note queues exactly { note: null } and keeps the quantity', async () => {
    const { db, row } = await seedItem();
    expect(await updateItem(db, deps(), row, { name: 'Milk', quantity: 2, unit: 'l', note: null })).toBe(true);
    const out = await db.outbox.toArray();
    expect(out).toHaveLength(1);
    expect(out[0]!.payload).toMatchObject({ row_id: row.id, data: { note: null } });
    expect(Object.keys((out[0]!.payload as { data: object }).data)).toEqual(['note']);
    expect(await db.list_item.get(row.id)).toMatchObject({ quantity: 2, unit: 'l', note: null });
    await db.close();
  });

  it('no change writes nothing', async () => {
    const { db, row } = await seedItem();
    expect(await updateItem(db, deps(), row, { name: 'Milk', quantity: 2, unit: 'l', note: 'lactose free' })).toBe(false);
    expect(await db.outbox.count()).toBe(0);
    await db.close();
  });

  it('a name change queues { name } only and refreshes name_key locally', async () => {
    const { db, row } = await seedItem();
    await updateItem(db, deps(), row, { name: 'Oat Milk', quantity: 2, unit: 'l', note: 'lactose free' });
    const out = await db.outbox.toArray();
    expect((out[0]!.payload as { data: object }).data).toEqual({ name: 'Oat Milk' });
    expect(await db.list_item.get(row.id)).toMatchObject({ name: 'Oat Milk', name_key: 'oat milk' });
    await db.close();
  });

  it('delete soft-deletes the row and queues one delete', async () => {
    const { db, id } = await seedItem();
    await deleteItem(db, deps(), id);
    expect((await db.list_item.get(id))!.deleted_at).toBe('2026-02-01T00:00:00Z');
    expect((await db.outbox.toArray()).map((o) => o.op)).toEqual(['delete']);
    await db.close();
  });

  it('AC4: offline, the local row changes and exactly one upsert is queued', async () => {
    Object.defineProperty(navigator, 'onLine', { value: false, configurable: true });
    const { db, row } = await seedItem();
    await updateItem(db, deps(), row, { name: 'Milk', quantity: 3, unit: 'l', note: 'lactose free' });
    expect((await db.list_item.get(row.id))!.quantity).toBe(3);
    expect(await db.outbox.count()).toBe(1);
    Object.defineProperty(navigator, 'onLine', { value: true, configurable: true });
    await db.close();
  });
});
