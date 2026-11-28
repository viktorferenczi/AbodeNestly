import type { Household, ItemPrice, ListItem, Profile, ShoppingList, Store } from '@abodenestly/contract';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import Dexie from 'dexie';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { RequireModule } from '../app/RequireModule.tsx';
import { Shell } from '../app/Shell.tsx';
import { ApiProvider } from '../core/api/ApiProvider.tsx';
import { FakeApiClient } from '../core/api/FakeApiClient.ts';
import { AuthProvider } from '../core/auth/AuthProvider.tsx';
import { FakeAuthClient } from '../core/auth/FakeAuthClient.ts';
import { writeLastHousehold } from '../core/auth/lastHousehold.ts';
import { FakeSync } from '../sync/FakeSync.ts';
import { openHouseholdDb } from '../sync/db.ts';
import { SyncProvider } from '../sync/SyncEngine.ts';
import { ListDetail } from './ListDetail.tsx';

const house = (over: Partial<Household> = {}) =>
  ({ id: 'h1', name: 'Home One', currency: 'EUR', module_shopping: true, module_pantry: true, module_maintenance: true, ...over }) as Household;
const list = (id: string, name: string, over: Partial<ShoppingList> = {}) =>
  ({ id, household_id: 'h1', name, store_id: null, is_template: false, archived_at: null, deleted_at: null, created_at: '2026-01-01T00:00:00Z', ...over }) as ShoppingList;
const item = (id: string, list_id: string, name: string, over: Partial<ListItem> = {}) =>
  ({ id, household_id: 'h1', list_id, name, name_key: name.toLowerCase(), checked: false, deleted_at: null, created_at: `2026-01-01T00:00:0${id.length}Z`, ...over }) as ListItem;

const store = (id: string, name: string) => ({ id, household_id: 'h1', name, deleted_at: null, created_at: '2026-01-01T00:00:00Z' }) as Store;
const price = (id: string, store_id: string, name_key: string, value: number, over: Partial<ItemPrice> = {}) =>
  ({ id, household_id: 'h1', store_id, name_key, price: value, seen_at: '2026-11-03T12:00:00Z', deleted_at: null, created_at: '2026-11-03T12:00:00Z', ...over }) as ItemPrice;

async function seed(lists: ShoppingList[], items: ListItem[] = [], stores: Store[] = [], prices: ItemPrice[] = []) {
  const db = await openHouseholdDb('h1');
  await db.shopping_list.bulkPut(lists);
  await db.list_item.bulkPut(items);
  await db.store.bulkPut(stores);
  await db.item_price.bulkPut(prices);
  await db.close();
}

async function renderDetail(path = '/shopping/a', household = house()) {
  const api = new FakeApiClient();
  api.fetchHousehold = async () => ({ ok: true, value: { household, role: 'owner' } });
  api.fetchMyHouseholdsResult = { ok: true, value: [{ household, role: 'owner' }] };
  writeLastHousehold('h1');
  const auth = new FakeAuthClient({ user: { id: 'u1' } });
  auth.profile = { id: 'u1', display_name: 'Ada' } as Profile;
  const sync = new FakeSync();
  const router = createMemoryRouter(
    [
      {
        element: <Shell />,
        children: [
          { path: '/', element: <p>home</p> },
          {
            element: <RequireModule module="shopping" />,
            children: [
              { path: '/shopping', element: <p>lists</p> },
              { path: '/shopping/:listId', element: <SyncProvider engine={sync}><ListDetail /></SyncProvider> },
            ],
          },
        ],
      },
    ],
    { initialEntries: [path] },
  );
  render(
    <AuthProvider client={auth}>
      <ApiProvider client={api}>
        <RouterProvider router={router} />
      </ApiProvider>
    </AuthProvider>,
  );
  return { sync, router };
}

async function stored() {
  const db = await openHouseholdDb('h1');
  const [items, out, prices] = [await db.list_item.toArray(), await db.outbox.toArray(), await db.item_price.toArray()];
  await db.close();
  return { items, out, prices };
}

const items = () => screen.findByRole('list', { name: 'Items' });
const sheet = () => screen.findByRole('dialog');

beforeEach(async () => {
  localStorage.clear();
  await Dexie.delete('abodenestly-h1');
});
afterEach(() => {
  cleanup();
  Object.defineProperty(navigator, 'onLine', { value: true, configurable: true });
});

const market = () => store('s1', 'Test Market');
const withStore = () => list('a', 'Groceries', { store_id: 's1' });
const milk = () => item('1', 'a', 'Milk', { quantity: null, unit: null, note: null });
const openMilk = async () => {
  fireEvent.click(await screen.findByRole('button', { name: /Milk/ }));
  return sheet();
};

describe('SHOP-12: price memory', () => {
  it('AC3 (edit): a list with no store offers no price field', async () => {
    await seed([list('a', 'Groceries')], [milk()]);
    await renderDetail();
    const dlg = await openMilk();
    expect(within(dlg).queryByLabelText(/^Price at/)).toBeNull();
  });

  it('AC3 (new): a list with no store offers no price field', async () => {
    await seed([list('a', 'Groceries')]);
    await renderDetail();
    fireEvent.click(await screen.findByRole('button', { name: 'Details' }));
    expect(within(await sheet()).queryByLabelText(/^Price at/)).toBeNull();
  });

  it('a store that was deleted counts as no store: no price field', async () => {
    await seed([withStore()], [milk()], [{ ...market(), deleted_at: '2026-02-01T00:00:00Z' }]);
    await renderDetail();
    expect(within(await openMilk()).queryByLabelText(/^Price at/)).toBeNull();
  });

  it('AC2: the row caption shows the last price and the store; the sheet shows the Last bought line', async () => {
    await seed([withStore()], [milk()], [market()], [price('p1', 's1', 'milk', 1.2)]);
    await renderDetail();
    const row = await screen.findByRole('button', { name: /Milk/ });
    expect(row.textContent).toContain('€1.20 · Test Market');
    const dlg = await openMilk();
    expect(within(dlg).getByText(/^Last bought: .*€1\.20 at Test Market$/)).toBeTruthy();
    const field = within(dlg).getByLabelText('Price at Test Market') as HTMLInputElement;
    expect(field.value).toBe('');
    expect(field.getAttribute('inputmode')).toBe('decimal');
  });

  it('a list with no store shows the newest price at any store', async () => {
    await seed([list('a', 'Groceries')], [milk()], [market(), store('s2', 'Corner Shop')], [price('p1', 's1', 'milk', 1.2), price('p2', 's2', 'milk', 0.9, { seen_at: '2026-11-09T12:00:00Z' })]);
    await renderDetail();
    expect((await screen.findByRole('button', { name: /Milk/ })).textContent).toContain('€0.90 · Corner Shop');
  });

  it('an item with no price has no caption and no Last bought line', async () => {
    await seed([withStore()], [milk()], [market()]);
    await renderDetail();
    const dlg = await openMilk();
    expect(within(dlg).queryByText(/Last bought/)).toBeNull();
    expect((await screen.findAllByRole('button', { name: /Milk/ }))[0]!.textContent).not.toContain('€');
  });

  it('AC1 (edit): entering 1,5 and saving queues one item_price and the caption becomes €1.50', async () => {
    await seed([withStore()], [milk()], [market()], [price('p1', 's1', 'milk', 1.2)]);
    const { sync } = await renderDetail();
    const dlg = await openMilk();
    fireEvent.change(within(dlg).getByLabelText('Price at Test Market'), { target: { value: '1,5' } });
    fireEvent.click(within(dlg).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    await waitFor(() => expect(screen.getByRole('button', { name: /Milk/ }).textContent).toContain('€1.50 · Test Market'));
    const { out, prices } = await stored();
    // A price-only save writes the price and no list_item change.
    expect(out.map((o) => o.table)).toEqual(['item_price']);
    expect(out[0]!.payload).toMatchObject({ op: 'upsert', data: { store_id: 's1', name_key: 'milk', price: 1.5 } });
    expect(prices).toHaveLength(2);
    expect(sync.kicks).toBe(1);
  });

  it('an empty price field writes no price', async () => {
    await seed([withStore()], [milk()], [market()]);
    await renderDetail();
    const dlg = await openMilk();
    fireEvent.change(within(dlg).getByLabelText('Note'), { target: { value: 'x' } });
    fireEvent.click(within(dlg).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    const { out, prices } = await stored();
    expect(out.map((o) => o.table)).toEqual(['list_item']);
    expect(prices).toHaveLength(0);
  });

  it('an invalid price shows the message and turns Save off', async () => {
    await seed([withStore()], [milk()], [market()]);
    await renderDetail();
    const dlg = await openMilk();
    const field = within(dlg).getByLabelText('Price at Test Market');
    const save = within(dlg).getByRole('button', { name: 'Save' }) as HTMLButtonElement;
    for (const bad of ['-1', '1.234', 'abc']) {
      fireEvent.change(field, { target: { value: bad } });
      expect(save.disabled).toBe(true);
      expect(within(dlg).getByText('Enter a price like 1.20')).toBeTruthy();
    }
    fireEvent.change(field, { target: { value: '1.20' } });
    expect(save.disabled).toBe(false);
    expect(within(dlg).queryByText('Enter a price like 1.20')).toBeNull();
  });

  it('state new: the item and its price are written together; the Last bought line follows the typed name', async () => {
    await seed([withStore()], [], [market()], [price('p1', 's1', 'bread', 2.4)]);
    await renderDetail();
    fireEvent.click(await screen.findByRole('button', { name: 'Details' }));
    const dlg = await sheet();
    expect(within(dlg).queryByText(/Last bought/)).toBeNull();
    fireEvent.change(within(dlg).getByLabelText('Name'), { target: { value: 'Bread' } });
    expect(within(dlg).getByText(/Last bought: .*€2\.40 at Test Market/)).toBeTruthy();
    fireEvent.change(within(dlg).getByLabelText('Price at Test Market'), { target: { value: '2.5' } });
    fireEvent.click(within(dlg).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    const { out } = await stored();
    expect(out.map((o) => o.table)).toEqual(['list_item', 'item_price']);
    await waitFor(() => expect(within(screen.getByRole('list', { name: 'Items' })).getByRole('button', { name: /Bread/ }).textContent).toContain('€2.50 · Test Market'));
  });

  it('AC4: offline, the price is queued and the caption updates locally', async () => {
    Object.defineProperty(navigator, 'onLine', { value: false, configurable: true });
    await seed([withStore()], [milk()], [market()]);
    await renderDetail();
    const dlg = await openMilk();
    fireEvent.change(within(dlg).getByLabelText('Price at Test Market'), { target: { value: '1.2' } });
    fireEvent.click(within(dlg).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect((screen.getByRole('list', { name: 'Items' }).textContent ?? '')).toContain('€1.20 · Test Market'));
    const { out } = await stored();
    expect(out).toHaveLength(1);
    expect(out[0]).toMatchObject({ table: 'item_price', op: 'upsert' });
    expect((await items()).textContent).toContain('Milk');
  });

  it('module off: nothing renders', async () => {
    await seed([withStore()], [milk()], [market()], [price('p1', 's1', 'milk', 1.2)]);
    await renderDetail('/shopping/a', house({ module_shopping: false }));
    await waitFor(() => expect(screen.queryByTestId('list-detail')).toBeNull());
    expect(screen.queryByRole('dialog')).toBeNull();
  });
});
