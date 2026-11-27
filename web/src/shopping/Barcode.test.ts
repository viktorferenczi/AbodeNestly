import type { ListItem, Product } from '@abodenestly/contract';
import Dexie from 'dexie';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { FakeApiClient } from '../core/api/FakeApiClient.ts';
import { openHouseholdDb } from '../sync/db.ts';
import { addNamed, addOffline, addProduct, cacheName, cachedName, drainLookups, isValidCode, lookup, NAMES_CAP, pendingLookups } from './Barcode.ts';

const input = { householdId: 'h1', listId: 'l1', userId: 'u1' };
const nutella: Product = { barcode: '3017620422003', name: 'Nutella', brand: 'Ferrero', category: null, image_url: null, default_unit: null };

beforeEach(async () => {
  localStorage.clear();
  Object.defineProperty(navigator, 'onLine', { value: true, configurable: true });
  await Dexie.delete('abodenestly-h1');
  await Dexie.delete('abodenestly-h2');
});

describe('SHOP-10: code validation', () => {
  it.each([
    ['12345678', true],
    ['30176204220031', true],
    ['3017620422003', true],
    ['1234567', false],
    ['123456789012345', false],
    ['3017 6204', false],
    ['abcdefgh', false],
    ['', false],
  ])('%j -> %s', (code, ok) => expect(isValidCode(code)).toBe(ok));
});

describe('SHOP-10: manual-name cache', () => {
  it('round-trips a name', () => {
    expect(cachedName('12345678')).toBeNull();
    cacheName('12345678', 'Oat bar');
    expect(cachedName('12345678')).toBe('Oat bar');
  });

  it('keeps the newest NAMES_CAP entries', () => {
    for (let i = 0; i < NAMES_CAP + 5; i++) cacheName(String(10000000 + i), `n${i}`);
    expect(cachedName('10000000')).toBeNull();
    expect(cachedName('10000004')).toBeNull();
    expect(cachedName('10000005')).toBe('n5');
    expect(cachedName(String(10000000 + NAMES_CAP + 4))).toBe(`n${NAMES_CAP + 4}`);
  });

  it('evicts by insertion order with mixed 8- and 13-digit codes', () => {
    // 8-digit codes are integer-like object keys; an object would order them before the 13-digit ones.
    for (let i = 0; i < NAMES_CAP; i++) cacheName(i % 2 ? String(20000000 + i) : String(4000000000000 + i), `n${i}`);
    cacheName('30000000', 'new');
    expect(cachedName('4000000000000')).toBeNull(); // the oldest, a 13-digit code
    expect(cachedName('20000001')).toBe('n1'); // an older 8-digit code survives
    expect(cachedName('30000000')).toBe('new');
    cacheName('20000001', 'again'); // re-saving moves it to the newest
    cacheName('30000001', 'x');
    expect(cachedName('20000001')).toBe('again');
  });

  it('ignores the old object format', () => {
    localStorage.setItem('abodenestly.barcodeNames', JSON.stringify({ '12345678': 'Old' }));
    expect(cachedName('12345678')).toBeNull();
    cacheName('12345678', 'New');
    expect(cachedName('12345678')).toBe('New');
  });

  it('survives corrupt storage', () => {
    localStorage.setItem('abodenestly.barcodeNames', '{oops');
    expect(cachedName('12345678')).toBeNull();
  });
});

describe('SHOP-10: adding', () => {
  it('a found product is added with its name and barcode', async () => {
    const db = await openHouseholdDb('h1');
    await addProduct(db, {}, input, nutella);
    expect(await db.list_item.toArray()).toEqual([expect.objectContaining({ name: 'Nutella', barcode: '3017620422003' })]);
    expect(await db.outbox.count()).toBe(1);
    await db.close();
  });

  it('a not-found name is added with no barcode (foreign key) and remembered', async () => {
    const db = await openHouseholdDb('h1');
    await addNamed(db, {}, input, '12345678', '  Oat bar ');
    const [row] = await db.list_item.toArray();
    expect(row).toMatchObject({ name: 'Oat bar', barcode: null });
    expect(cachedName('12345678')).toBe('Oat bar');
    await db.close();
  });

  it('AC4: offline uses the code as the name, sets no barcode and queues one lookup', async () => {
    const db = await openHouseholdDb('h1');
    const id = await addOffline(db, {}, input, '4006381333931');
    expect(await db.list_item.get(id)).toMatchObject({ name: '4006381333931', barcode: null });
    expect(await db.outbox.count()).toBe(1);
    expect(pendingLookups()).toEqual([{ householdId: 'h1', itemId: id, code: '4006381333931' }]);
    await db.close();
  });
});

describe('SHOP-10: lookup', () => {
  it('maps results', async () => {
    const api = new FakeApiClient();
    expect(await lookup(api, '12345678', false)).toEqual({ kind: 'offline' });
    expect(api.getProductCalls).toEqual([]);
    api.getProductResult = { ok: true, value: nutella };
    expect(await lookup(api, '12345678', true)).toEqual({ kind: 'found', product: nutella });
    api.getProductResult = { ok: false, error: { code: 'not_found' } };
    expect(await lookup(api, '12345678', true)).toEqual({ kind: 'not-found' });
    api.getProductResult = { ok: false, error: { code: 'network' } };
    expect(await lookup(api, '12345678', true)).toEqual({ kind: 'offline' });
    api.getProductResult = { ok: false, error: { code: 'retryable' } };
    expect(await lookup(api, '12345678', true)).toEqual({ kind: 'offline' });
    api.getProductResult = { ok: false, error: { code: 'unknown' } };
    expect(await lookup(api, '12345678', true)).toEqual({ kind: 'error' });
  });
});

describe('SHOP-10: drainLookups', () => {
  async function setup() {
    const db = await openHouseholdDb('h1');
    const id = await addOffline(db, {}, input, '3017620422003');
    await db.outbox.clear();
    return { db, id, api: new FakeApiClient() };
  }

  it('a found product patches the name and barcode only and drops the entry', async () => {
    const { db, id, api } = await setup();
    api.getProductResult = { ok: true, value: nutella };
    await drainLookups(db, {}, api, 'h1');
    expect(await db.list_item.get(id)).toMatchObject({ name: 'Nutella', name_key: 'nutella', barcode: '3017620422003' });
    const [out] = await db.outbox.toArray();
    expect(out?.payload).toMatchObject({ row_id: id, data: { name: 'Nutella', barcode: '3017620422003' } });
    expect(pendingLookups()).toEqual([]);
    await db.close();
  });

  it('a renamed item is left alone but the entry is dropped', async () => {
    const { db, id, api } = await setup();
    await db.list_item.update(id, { name: 'Hazelnut spread' });
    api.getProductResult = { ok: true, value: nutella };
    await drainLookups(db, {}, api, 'h1');
    expect(await db.list_item.get(id)).toMatchObject({ name: 'Hazelnut spread', barcode: null });
    expect(await db.outbox.count()).toBe(0);
    expect(pendingLookups()).toEqual([]);
    await db.close();
  });

  it('a deleted item is left alone', async () => {
    const { db, id, api } = await setup();
    await db.list_item.update(id, { deleted_at: '2026-01-01T00:00:00Z' } as Partial<ListItem>);
    api.getProductResult = { ok: true, value: nutella };
    await drainLookups(db, {}, api, 'h1');
    expect(await db.outbox.count()).toBe(0);
    expect(pendingLookups()).toEqual([]);
    await db.close();
  });

  it('a 404 drops the entry and keeps the name', async () => {
    const { db, id, api } = await setup();
    await drainLookups(db, {}, api, 'h1');
    expect(await db.list_item.get(id)).toMatchObject({ name: '3017620422003' });
    expect(pendingLookups()).toEqual([]);
    await db.close();
  });

  it('a network error or 502 keeps the entry', async () => {
    const { db, api } = await setup();
    for (const code of ['network', 'retryable'] as const) {
      api.getProductResult = { ok: false, error: { code } };
      await drainLookups(db, {}, api, 'h1');
      expect(pendingLookups()).toHaveLength(1);
    }
    await db.close();
  });

  it('an unknown failure (401, 500) drops the entry', async () => {
    const { db, id, api } = await setup();
    api.getProductResult = { ok: false, error: { code: 'unknown' } };
    await drainLookups(db, {}, api, 'h1');
    expect(pendingLookups()).toEqual([]);
    expect(await db.list_item.get(id)).toMatchObject({ name: '3017620422003' });
    await db.close();
  });

  it('a lookup queued during a slow pass is handled by a follow-up pass that skips the entry that just failed', async () => {
    const { db, id, api } = await setup();
    const other = '4006381333931';
    api.getProductResult = (code) =>
      code === other ? { ok: true, value: { ...nutella, barcode: other, name: 'Pasta' } } : { ok: false, error: { code: 'network' } };
    api.pending = true;
    const first = drainLookups(db, {}, api, 'h1');
    await vi.waitFor(() => expect(api.getProductCalls).toEqual(['3017620422003']));
    const id2 = await addOffline(db, {}, input, other); // queued while the pass is waiting on the network
    await drainLookups(db, {}, api, 'h1'); // returns at once, flags a follow-up
    api.resolvePending();
    await vi.waitFor(() => expect(api.getProductCalls).toEqual(['3017620422003', other]));
    api.resolvePending();
    await first;
    expect(api.getProductCalls).toEqual(['3017620422003', other]); // the failed one is not fetched twice
    expect(await db.list_item.get(id2)).toMatchObject({ name: 'Pasta', barcode: other });
    expect(pendingLookups()).toEqual([{ householdId: 'h1', itemId: id, code: '3017620422003' }]);
    await db.close();
  });

  it('a request for another household made during a pass that then throws still runs with its own db', async () => {
    const { db, id, api } = await setup();
    const db2 = await openHouseholdDb('h2');
    const id2 = await addOffline(db2, {}, { ...input, householdId: 'h2' }, '4006381333931');
    api.getProductResult = (code) => ({ ok: true, value: { ...nutella, barcode: code, name: code === '4006381333931' ? 'Pasta' : 'Nutella' } });
    api.pending = true;
    const first = drainLookups(db, {}, api, 'h1');
    await vi.waitFor(() => expect(api.getProductCalls).toEqual(['3017620422003']));
    await db.close(); // the household switch: the first pass will fail on its closed database
    await drainLookups(db2, {}, api, 'h2');
    api.resolvePending();
    await vi.waitFor(() => expect(api.getProductCalls).toEqual(['3017620422003', '4006381333931']));
    api.resolvePending();
    await first.catch(() => undefined);
    expect(await db2.list_item.get(id2)).toMatchObject({ name: 'Pasta', barcode: '4006381333931' });
    expect(pendingLookups().map((e) => e.itemId)).toEqual([id]); // h1's entry waits for h1 to open again
    await db2.close();
  });

  it('does nothing offline, and skips other households', async () => {
    const { db, api } = await setup();
    Object.defineProperty(navigator, 'onLine', { value: false, configurable: true });
    await drainLookups(db, {}, api, 'h1');
    expect(api.getProductCalls).toEqual([]);
    Object.defineProperty(navigator, 'onLine', { value: true, configurable: true });
    await drainLookups(db, {}, api, 'h2');
    expect(api.getProductCalls).toEqual([]);
    expect(pendingLookups()).toHaveLength(1);
    await db.close();
  });
});
