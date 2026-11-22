import type { ShoppingList } from '@abodenestly/contract';
import { afterEach, describe, expect, it } from 'vitest';
import { dbName, openHouseholdDb, pendingCount, readCursor, writeCursor, type HouseholdDb } from '../sync/db.ts';
import { startTab } from './auth/landing.ts';
import { switchTargetPath, switcherState } from './Switcher.ts';

const open: HouseholdDb[] = [];
const track = async (id: string) => {
  const db = await openHouseholdDb(id);
  open.push(db);
  return db;
};
afterEach(() => {
  open.splice(0).forEach((db) => db.close());
});

const list = (id: string, household_id: string, name: string) => ({ id, household_id, name, deleted_at: null }) as ShoppingList;

describe('CORE-07: local databases', () => {
  it('CORE-07: AC2 switching closes the current database and opens one named for the target', async () => {
    const h1 = await track('h1');
    expect(h1.name).toBe('abodenestly-h1');
    h1.close();
    const h2 = await track('h2');
    expect(h2.name).toBe(dbName('h2'));
    expect(h2.isOpen()).toBe(true);
    expect(h1.isOpen()).toBe(false);
  });

  it('CORE-07: AC4 a query sees only the rows of the household that is open', async () => {
    const h1 = await track('h1');
    await h1.shopping_list.put(list('a', 'h1', 'Weekly'));
    const h2 = await track('h2');
    await h2.shopping_list.put(list('b', 'h2', 'Party'));
    h1.close();
    h2.close();
    const back = await track('h1');
    expect((await back.shopping_list.toArray()).map((r) => r.name)).toEqual(['Weekly']);
  });

  it('CORE-07: the cursor seeds at 0, survives reopen, and is per household', async () => {
    const h1 = await track('h1');
    expect(await readCursor(h1)).toBe(0);
    await writeCursor(h1, 42);
    h1.close();
    expect(await readCursor(await track('h1'))).toBe(42);
    expect(await readCursor(await track('h2'))).toBe(0);
  });

  it('CORE-07: pendingCount counts the outbox of a household that is not open', async () => {
    const h1 = await track('h1');
    await h1.outbox.bulkAdd([
      { table: 'shopping_list', op: 'upsert', payload: {} },
      { table: 'shopping_list', op: 'upsert', payload: {} },
    ]);
    h1.close();
    expect(await pendingCount('h1')).toBe(2);
    expect(await pendingCount('h-none')).toBe(0);
  });

  it('CORE-07: pendingCount reads an already open database without closing it', async () => {
    const h1 = await track('h-open');
    await h1.outbox.add({ table: 'shopping_list', op: 'upsert', payload: {} });
    expect(await pendingCount('h-open', h1)).toBe(1);
    expect(h1.isOpen()).toBe(true);
  });
});

describe('CORE-07: switchTargetPath', () => {
  const all = { id: 'x', module_shopping: true, module_pantry: true, module_maintenance: true };
  const noPantry = { ...all, module_pantry: false };
  const none = { ...all, module_shopping: false, module_pantry: false, module_maintenance: false };
  it.each([
    ['/shopping', all, '/shopping'],
    ['/pantry', all, '/pantry'],
    ['/pantry', noPantry, '/shopping'],
    ['/pantry', none, '/home'],
    ['/home', none, '/home'],
    ['/household/members', noPantry, '/household/members'],
    ['/shopping?x=1', all, '/shopping?x=1'],
  ])('%s into %j → %s', (path, target, expected) => {
    expect(switchTargetPath(path, target)).toBe(expected);
    expect(startTab(none)).toBe('/home');
  });
});

describe('CORE-07: switcherState', () => {
  it.each([
    [[], false, 'one-household'],
    [[1], false, 'one-household'],
    [[1, 2], false, 'many'],
    [[1, 2], true, 'switching'],
  ] as const)('%j switching=%s → %s', (households, switching, expected) => {
    expect(switcherState(households, switching)).toBe(expected);
  });
});
