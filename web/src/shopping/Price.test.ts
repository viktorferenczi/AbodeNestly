import type { ItemPrice, Store } from '@abodenestly/contract';
import Dexie from 'dexie';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { FakeApiClient } from '../core/api/FakeApiClient.ts';
import { openHouseholdDb } from '../sync/db.ts';
import { createSyncEngine, PUSHABLE } from '../sync/SyncEngine.ts';
import { createItem } from './ItemSheet.ts';
import { formatMoney, lastBoughtLine, lastBoughtLookup, lastPrice, lastPriceAnywhere, nameKey, parsePrice, priceCaption, recordPrice, saveWithPrice } from './Price.ts';

let n = 0;
const deps = () => ({ uuid: () => `m${++n}`, now: () => '2026-02-01T00:00:00Z' });
const price = (id: string, over: Partial<ItemPrice> = {}): ItemPrice => ({
  id,
  household_id: 'h1',
  store_id: 's1',
  name_key: 'milk',
  price: 1,
  seen_at: '2026-01-01T00:00:00Z',
  deleted_at: null,
  created_at: '2026-01-01T00:00:00Z',
  updated_at: '2026-01-01T00:00:00Z',
  sync_seq: 1,
  ...over,
});
const store = (id: string, name: string, over: Partial<Store> = {}) => ({ id, household_id: 'h1', name, deleted_at: null, ...over }) as Store;

beforeEach(async () => {
  n = 0;
  await Dexie.delete('abodenestly-h1');
});

describe('SHOP-12: nameKey', () => {
  it('matches the SQL rule: trim spaces, collapse whitespace, lower-case', () => {
    expect(nameKey('  Whole  Milk')).toBe('whole milk');
    expect(nameKey('Whole\t Milk ')).toBe('whole milk');
  });
});

describe('SHOP-12: parsePrice', () => {
  it.each([
    ['', null],
    ['  ', null],
    ['1.2', 1.2],
    ['1,5', 1.5],
    ['0', 0],
    ['12', 12],
    ['1.20', 1.2],
  ])('accepts %j', (text, value) => expect(parsePrice(text)).toEqual({ ok: true, value }));
  it.each(['-1', '1.234', 'abc', '1.', '.5', '1,2,3', '1e3'])('rejects %j', (text) => expect(parsePrice(text)).toEqual({ ok: false }));
});

describe('SHOP-12: lastPrice', () => {
  it('picks the newest seen_at, then the newest created_at, then the id', () => {
    const rows = [price('a', { seen_at: '2026-01-05T00:00:00Z', price: 2 }), price('b', { seen_at: '2026-01-02T00:00:00Z', price: 3 })];
    expect(lastPrice(rows, 's1', 'milk')!.id).toBe('a');
    const tie = [price('a', { created_at: '2026-01-01T00:00:00Z' }), price('b', { created_at: '2026-01-03T00:00:00Z' })];
    expect(lastPrice(tie, 's1', 'milk')!.id).toBe('b');
    expect(lastPrice([price('a'), price('b')], 's1', 'milk')!.id).toBe('b');
  });
  it('skips deleted rows and ignores other stores and keys', () => {
    const rows = [
      price('del', { seen_at: '2026-03-01T00:00:00Z', deleted_at: '2026-03-02T00:00:00Z' }),
      price('other-store', { store_id: 's2', seen_at: '2026-03-01T00:00:00Z' }),
      price('other-key', { name_key: 'bread', seen_at: '2026-03-01T00:00:00Z' }),
      price('ok', { seen_at: '2026-01-02T00:00:00Z' }),
    ];
    expect(lastPrice(rows, 's1', 'milk')!.id).toBe('ok');
    expect(lastPrice(rows, 's3', 'milk')).toBeNull();
    expect(lastPriceAnywhere(rows, 'milk')!.id).toBe('other-store');
  });
});

describe('SHOP-12: caption lookup', () => {
  const stores = [store('s1', 'Test Market'), store('s2', 'Corner Shop'), store('s3', 'Gone', { deleted_at: '2026-01-01T00:00:00Z' })];
  const rows = [price('a', { price: 1.2 }), price('b', { store_id: 's2', price: 0.9, seen_at: '2026-01-09T00:00:00Z' })];
  it('uses the list store first', () => {
    expect(lastBoughtLookup(rows, stores, 's1')('Milk')).toEqual({ price: 1.2, seen_at: '2026-01-01T00:00:00Z', storeName: 'Test Market' });
  });
  it('with no list store uses the newest price at any store', () => {
    expect(lastBoughtLookup(rows, stores, null)('  MILK')).toMatchObject({ price: 0.9, storeName: 'Corner Shop' });
  });
  it('has nothing for an unknown name, and a deleted store leaves just the price', () => {
    expect(lastBoughtLookup(rows, stores, 's1')('Bread')).toBeNull();
    expect(lastBoughtLookup(rows, stores, null)('')).toBeNull();
    const gone = lastBoughtLookup([price('c', { store_id: 's3', price: 2 })], stores, null)('milk')!;
    expect(gone.storeName).toBeNull();
    expect(priceCaption(gone, 'EUR', 'en-GB')).toBe('€2.00');
  });
  it('formats the caption and the line with the household currency', () => {
    const last = { price: 1.2, seen_at: '2026-11-03T12:00:00Z', storeName: 'Test Market' };
    expect(formatMoney(1.2, 'EUR', 'en-GB')).toBe('€1.20');
    expect(priceCaption(last, 'EUR', 'en-GB')).toBe('€1.20 · Test Market');
    expect(lastBoughtLine(last, 'EUR', 'en-GB')).toBe('Last bought: 3 Nov, €1.20 at Test Market');
  });
});

describe('SHOP-12: recordPrice', () => {
  it('AC1: queues one item_price upsert with the decimal price, store, normalised name_key and seen_at', async () => {
    const db = await openHouseholdDb('h1');
    const id = await recordPrice(db, deps(), { householdId: 'h1', storeId: 's1', name: '  Whole  Milk', price: 1.2, now: '2026-02-01T00:00:00Z' });
    expect(await db.item_price.get(id)).toMatchObject({ household_id: 'h1', store_id: 's1', name_key: 'whole milk', price: 1.2, seen_at: '2026-02-01T00:00:00Z', deleted_at: null });
    const out = await db.outbox.toArray();
    expect(out).toHaveLength(1);
    expect(out[0]).toMatchObject({ table: 'item_price', op: 'upsert' });
    expect(out[0]!.payload).toMatchObject({ row_id: id, data: { store_id: 's1', name_key: 'whole milk', price: 1.2, seen_at: '2026-02-01T00:00:00Z' } });
    expect((out[0]!.payload as { data: object }).data).not.toHaveProperty('household_id');
  });

  it('keeps every observation: a second price is a second row', async () => {
    const db = await openHouseholdDb('h1');
    const input = { householdId: 'h1', storeId: 's1', name: 'Milk', price: 1.2, now: '2026-02-01T00:00:00Z' };
    await recordPrice(db, deps(), input);
    await recordPrice(db, deps(), { ...input, price: 1.3 });
    expect(await db.item_price.count()).toBe(2);
  });

  it('saveWithPrice writes the item and the price together, and kicks once after commit', async () => {
    const db = await openHouseholdDb('h1');
    const onQueued = vi.fn();
    const d = { ...deps(), onQueued };
    await saveWithPrice(db, d, { householdId: 'h1', storeId: 's1', name: 'Milk', price: 1.2, now: '2026-02-01T00:00:00Z' }, (quiet) =>
      createItem(db, quiet, { householdId: 'h1', listId: 'l1', userId: 'u1', fields: { name: 'Milk', quantity: null, unit: null, note: null } }),
    );
    expect((await db.outbox.toArray()).map((o) => o.table)).toEqual(['list_item', 'item_price']);
    expect(onQueued).toHaveBeenCalledTimes(1);
  });

  it('saveWithPrice with no price writes only the item', async () => {
    const db = await openHouseholdDb('h1');
    await saveWithPrice(db, deps(), null, (quiet) =>
      createItem(db, quiet, { householdId: 'h1', listId: 'l1', userId: 'u1', fields: { name: 'Milk', quantity: null, unit: null, note: null } }),
    );
    expect((await db.outbox.toArray()).map((o) => o.table)).toEqual(['list_item']);
    expect(await db.item_price.count()).toBe(0);
  });
});

describe('SHOP-12: sync', () => {
  it('PUSHABLE includes item_price and a push run sends it', async () => {
    expect(PUSHABLE.has('item_price')).toBe(true);
    const db = await openHouseholdDb('h1');
    await recordPrice(db, deps(), { householdId: 'h1', storeId: 's1', name: 'Milk', price: 1.2, now: '2026-02-01T00:00:00Z' });
    const api = new FakeApiClient();
    api.syncPullResults = [{ ok: true, value: { cursor: 1, has_more: false, changes: {} } }];
    const engine = createSyncEngine({ api, householdId: 'h1', getDb: () => db, isOnline: () => true });
    await engine.kick();
    expect(api.syncPushCalls).toHaveLength(1);
    expect(api.syncPushCalls[0]!.mutations[0]).toMatchObject({ table: 'item_price', op: 'upsert' });
    expect(await db.outbox.count()).toBe(0);
  });
});
