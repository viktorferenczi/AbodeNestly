import type { Store } from '@abodenestly/contract';
import Dexie from 'dexie';
import { beforeEach, describe, expect, it } from 'vitest';
import { openHouseholdDb } from '../sync/db.ts';
import { createStore, deleteStore, learnedLabel, setListStore, storeRows, validateStoreName } from './Stores.ts';

const store = (id: string, name: string, over: Partial<Store> = {}) => ({ id, household_id: 'h1', name, deleted_at: null, ...over }) as Store;
const deps = { uuid: (() => { let n = 0; return () => `m${++n}`; })(), now: () => '2026-11-23T10:00:00.000Z' };

beforeEach(async () => {
  await Dexie.delete('abodenestly-h1');
});

describe('SHOP-05: store model', () => {
  it('storeRows sorts by name, drops deleted stores and counts only that store\'s order rows', () => {
    const rows = storeRows(
      [store('b', 'lidl'), store('a', 'Aldi'), store('c', 'Gone', { deleted_at: '2026-01-01T00:00:00Z' })],
      [{ store_id: 'a' }, { store_id: 'a' }, { store_id: 'a', deleted_at: '2026-01-01T00:00:00Z' }, { store_id: 'b' }, { store_id: 'zz' }],
    );
    expect(rows).toEqual([
      { id: 'a', name: 'Aldi', learned: 2 },
      { id: 'b', name: 'lidl', learned: 1 },
    ]);
  });

  it.each([
    [0, null],
    [1, 'learned 1 item'],
    [42, 'learned 42 items'],
  ])('learnedLabel(%i)', (n, label) => expect(learnedLabel(n)).toBe(label));

  it('validateStoreName', () => {
    const stores = [store('a', 'Test Market'), store('b', 'Old', { deleted_at: '2026-01-01T00:00:00Z' })];
    expect(validateStoreName('', stores)).toBe('empty');
    expect(validateStoreName('   ', stores)).toBe('empty');
    expect(validateStoreName(' test market ', stores)).toBe('duplicate');
    expect(validateStoreName('Test Market', stores, 'a')).toBeNull();
    expect(validateStoreName('Old', stores)).toBeNull();
    expect(validateStoreName('Corner Shop', stores)).toBeNull();
  });
});

describe('SHOP-05: store writes', () => {
  it('AC4 createStore while offline writes the local row and exactly one outbox upsert', async () => {
    Object.defineProperty(navigator, 'onLine', { value: false, configurable: true });
    const db = await openHouseholdDb('h1');
    const id = await createStore(db, deps, { householdId: 'h1', name: '  Test Market ' });
    expect(await db.store.get(id)).toMatchObject({ name: 'Test Market', household_id: 'h1', deleted_at: null });
    const out = await db.outbox.toArray();
    expect(out).toHaveLength(1);
    expect(out[0]!.payload).toMatchObject({ table: 'store', op: 'upsert', row_id: id });
    expect((out[0]!.payload as { data: Record<string, unknown> }).data).toMatchObject({ name: 'Test Market' });
    Object.defineProperty(navigator, 'onLine', { value: true, configurable: true });
    await db.close();
  });

  it('deleteStore soft deletes with one outbox delete and leaves lists pointing at it', async () => {
    const db = await openHouseholdDb('h1');
    await db.store.put(store('s1', 'Test Market'));
    await db.shopping_list.put({ id: 'l1', household_id: 'h1', name: 'G', store_id: 's1', deleted_at: null } as never);
    await deleteStore(db, deps, 's1');
    expect((await db.store.get('s1'))!.deleted_at).toBeTruthy();
    const out = await db.outbox.toArray();
    expect(out).toHaveLength(1);
    expect(out[0]!.payload).toMatchObject({ table: 'store', op: 'delete', row_id: 's1' });
    expect((await db.shopping_list.get('l1'))!.store_id).toBe('s1');
    await db.close();
  });

  it('setListStore queues only store_id; null clears it', async () => {
    const db = await openHouseholdDb('h1');
    await db.shopping_list.put({ id: 'l1', household_id: 'h1', name: 'G', store_id: null, deleted_at: null } as never);
    await setListStore(db, deps, 'l1', 's1');
    await setListStore(db, deps, 'l1', null);
    const out = (await db.outbox.toArray()).map((r) => r.payload as { table: string; op: string; data: Record<string, unknown> });
    expect(out).toHaveLength(2);
    expect(out[0]).toMatchObject({ table: 'shopping_list', op: 'upsert', data: { store_id: 's1' } });
    expect(out[1]!.data).toEqual({ store_id: null });
    expect((await db.shopping_list.get('l1'))!.store_id).toBeNull();
    await db.close();
  });
});
