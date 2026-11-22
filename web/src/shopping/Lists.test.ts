import type { ListItem, ShoppingList, Store } from '@abodenestly/contract';
import Dexie from 'dexie';
import { beforeEach, describe, expect, it } from 'vitest';
import { openHouseholdDb } from '../sync/db.ts';
import { archiveList, createList, deleteList, listSections, restoreList, UNDO_MS } from './Lists.ts';

const list = (id: string, over: Partial<ShoppingList> = {}) =>
  ({ id, household_id: 'h1', name: id, store_id: null, is_template: false, archived_at: null, deleted_at: null, created_at: `2026-01-0${id.length}T00:00:00Z`, ...over }) as ShoppingList;
const item = (id: string, list_id: string, over: Partial<ListItem> = {}) => ({ id, list_id, checked: false, deleted_at: null, ...over }) as ListItem;
const store = (id: string, over: Partial<Store> = {}) => ({ id, name: `S-${id}`, deleted_at: null, ...over }) as Store;

describe('SHOP-01: listSections', () => {
  it('splits lists, templates and archived; hides deleted', () => {
    const s = listSections(
      [list('a'), list('bb', { is_template: true }), list('ccc', { archived_at: '2026-02-01T00:00:00Z' }), list('dddd', { deleted_at: '2026-02-01T00:00:00Z' })],
      [],
      [],
    );
    expect(s.lists.map((l) => l.id)).toEqual(['a']);
    expect(s.templates.map((l) => l.id)).toEqual(['bb']);
    expect(s.archived.map((l) => l.id)).toEqual(['ccc']);
  });

  it('orders lists oldest first and archived newest archived first', () => {
    const s = listSections(
      [list('bb', { archived_at: '2026-02-01T00:00:00Z' }), list('a', { archived_at: '2026-03-01T00:00:00Z' }), list('ccc'), list('dddd', { created_at: '2025-01-01T00:00:00Z' })],
      [],
      [],
    );
    expect(s.lists.map((l) => l.id)).toEqual(['dddd', 'ccc']);
    expect(s.archived.map((l) => l.id)).toEqual(['a', 'bb']);
  });

  it('counts checked of live items, or says No items', () => {
    const s = listSections(
      [list('a'), list('bb')],
      [item('1', 'a', { checked: true }), item('2', 'a'), item('3', 'a'), item('4', 'a', { checked: true, deleted_at: 'x' })],
      [],
    );
    expect(s.lists.map((l) => l.count)).toEqual(['1 of 3', 'No items']);
  });

  it.each([
    ['a live store', store('s1'), 'S-s1'],
    ['a deleted store', store('s1', { deleted_at: 'x' }), null],
    ['no matching store', store('other'), null],
  ])('store chip: %s', (_label, st, expected) => {
    const s = listSections([list('a', { store_id: 's1' })], [], [st]);
    expect(s.lists[0]!.storeName).toBe(expected);
  });

  it('undo window is 5 seconds', () => expect(UNDO_MS).toBe(5000));
});

describe('SHOP-01: write helpers', () => {
  let n = 0;
  const deps = { uuid: () => `m${++n}`, now: () => '2026-11-22T10:00:00.000Z' };
  beforeEach(async () => {
    n = 0;
    await Dexie.delete('abodenestly-h1');
  });

  it('AC6 createList while offline writes the local row and exactly one outbox upsert', async () => {
    Object.defineProperty(navigator, 'onLine', { value: false, configurable: true });
    const db = await openHouseholdDb('h1');
    const id = await createList(db, deps, { householdId: 'h1', name: '  Hardware ', storeId: null });
    expect(await db.shopping_list.get(id)).toMatchObject({ name: 'Hardware', is_template: false, archived_at: null });
    const out = await db.outbox.toArray();
    expect(out).toHaveLength(1);
    expect(out[0]!.payload).toMatchObject({ table: 'shopping_list', op: 'upsert', row_id: id });
    const data = (out[0]!.payload as { data: Record<string, unknown> }).data;
    expect(data).toEqual({ name: 'Hardware', store_id: null, is_template: false, archived_at: null, deleted_at: null });
    Object.defineProperty(navigator, 'onLine', { value: true, configurable: true });
    await db.close();
  });

  it('archiveList queues only archived_at; restoreList clears it; deleteList soft deletes', async () => {
    const db = await openHouseholdDb('h1');
    await db.shopping_list.put(list('a'));
    await archiveList(db, deps, 'a');
    expect((await db.shopping_list.get('a'))!.archived_at).toBe(deps.now());
    await restoreList(db, deps, 'a');
    expect((await db.shopping_list.get('a'))!.archived_at).toBeNull();
    await deleteList(db, deps, 'a');
    expect((await db.shopping_list.get('a'))!.deleted_at).toBe(deps.now());
    const out = (await db.outbox.toArray()).map((o) => o.payload as { op: string; data?: unknown });
    expect(out.map((p) => p.op)).toEqual(['upsert', 'upsert', 'delete']);
    expect(out[0]!.data).toEqual({ archived_at: deps.now() });
    expect(out[1]!.data).toEqual({ archived_at: null });
    await db.close();
  });
});
