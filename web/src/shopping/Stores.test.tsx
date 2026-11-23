import type { Household, ListItem, Profile, ShoppingList, Store } from '@abodenestly/contract';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import Dexie from 'dexie';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { Shell } from '../app/Shell.tsx';
import { RequireModule } from '../app/RequireModule.tsx';
import { ApiProvider } from '../core/api/ApiProvider.tsx';
import { FakeApiClient } from '../core/api/FakeApiClient.ts';
import { AuthProvider } from '../core/auth/AuthProvider.tsx';
import { FakeAuthClient } from '../core/auth/FakeAuthClient.ts';
import { writeLastHousehold } from '../core/auth/lastHousehold.ts';
import { FakeSync } from '../sync/FakeSync.ts';
import { openHouseholdDb } from '../sync/db.ts';
import { SyncProvider } from '../sync/SyncEngine.ts';
import { ListDetail } from './ListDetail.tsx';
import { Lists } from './Lists.tsx';
import { Stores } from './Stores.tsx';

vi.mock('./Lists.ts', async (orig) => ({ ...(await orig<typeof import('./Lists.ts')>()), UNDO_MS: 150 }));

const house = (over: Partial<Household> = {}) =>
  ({ id: 'h1', name: 'Home One', currency: 'EUR', module_shopping: true, module_pantry: true, module_maintenance: true, ...over }) as Household;
const store = (id: string, name: string, over: Partial<Store> = {}) => ({ id, household_id: 'h1', name, deleted_at: null, ...over }) as Store;
const list = (id: string, name: string, over: Partial<ShoppingList> = {}) =>
  ({ id, household_id: 'h1', name, store_id: null, is_template: false, archived_at: null, deleted_at: null, created_at: '2026-01-01T00:00:00Z', ...over }) as ShoppingList;

async function seed(stores: Store[] = [], lists: ShoppingList[] = [], orders: { store_id: string; name_key: string }[] = []) {
  const db = await openHouseholdDb('h1');
  await db.store.bulkPut(stores);
  await db.shopping_list.bulkPut(lists);
  await db.list_item.bulkPut([] as ListItem[]);
  await db.store_item_order.bulkPut(orders.map((o) => ({ ...o, household_id: 'h1' })));
  await db.close();
}

async function renderAt(path: string, household = house()) {
  const api = new FakeApiClient();
  api.fetchHousehold = async () => ({ ok: true, value: { household, role: 'owner' } });
  api.fetchMyHouseholdsResult = { ok: true, value: [{ household, role: 'owner' }] };
  writeLastHousehold('h1');
  const auth = new FakeAuthClient({ user: { id: 'u1' } });
  auth.profile = { id: 'u1', display_name: 'Ada' } as Profile;
  const sync = new FakeSync();
  const wrap = (el: React.ReactNode) => <SyncProvider engine={sync}>{el}</SyncProvider>;
  const router = createMemoryRouter(
    [
      {
        element: <Shell />,
        children: [
          { path: '/', element: <p>home</p> },
          {
            element: <RequireModule module="shopping" />,
            children: [
              { path: '/shopping', element: wrap(<Lists />) },
              { path: '/shopping/stores', element: wrap(<Stores />) },
              { path: '/shopping/:listId', element: wrap(<ListDetail />) },
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

async function outbox() {
  const db = await openHouseholdDb('h1');
  const rows = await db.outbox.toArray();
  await db.close();
  return rows.map((r) => r.payload as { table: string; op: string; row_id: string; data?: Record<string, unknown> });
}

const swipe = (el: HTMLElement) => {
  fireEvent.pointerDown(el, { clientX: 300 });
  fireEvent.pointerMove(el, { clientX: 100 });
  fireEvent.pointerUp(el, { clientX: 100 });
};
const wait = (ms: number) => new Promise((r) => setTimeout(r, ms));
const addStore = async (name: string) => {
  fireEvent.click((await screen.findAllByRole('button', { name: 'New store' }))[0]!);
  fireEvent.change(await screen.findByLabelText('Name'), { target: { value: name } });
  fireEvent.click(screen.getByRole('button', { name: 'Save' }));
};

beforeEach(async () => {
  localStorage.clear();
  await Dexie.delete('abodenestly-h1');
});
afterEach(() => cleanup());

describe('SHOP-05: Stores screen', () => {
  it('AC1 empty state offers New store', async () => {
    await seed();
    await renderAt('/shopping/stores');
    expect(await screen.findByText('No stores yet')).toBeTruthy();
    expect(screen.getAllByRole('button', { name: 'New store' }).length).toBeGreaterThan(0);
  });

  it('AC2 add Test Market through + shows the row and queues one upsert', async () => {
    await seed();
    const { sync } = await renderAt('/shopping/stores');
    await addStore('Test Market');
    expect(await screen.findByText('Test Market')).toBeTruthy();
    expect(screen.queryByText('No stores yet')).toBeNull();
    const out = await outbox();
    expect(out).toHaveLength(1);
    expect(out[0]).toMatchObject({ table: 'store', op: 'upsert' });
    await waitFor(() => expect(sync.kicks).toBe(1));
  });

  it('shows "learned N items" only when something was learned', async () => {
    await seed([store('s1', 'Lidl'), store('s2', 'Aldi')], [], [
      { store_id: 's1', name_key: 'a' },
      { store_id: 's1', name_key: 'b' },
      { store_id: 's1', name_key: 'c' },
    ]);
    await renderAt('/shopping/stores');
    expect(await screen.findByText('learned 3 items')).toBeTruthy();
    expect(screen.getAllByText(/learned/)).toHaveLength(1);
  });

  it('empty and duplicate names show an inline error and write nothing', async () => {
    await seed([store('s1', 'Test Market')]);
    await renderAt('/shopping/stores');
    await addStore('   ');
    expect(await screen.findByText('Give the store a name')).toBeTruthy();
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'test market' } });
    expect(await screen.findByText('You already have test market')).toBeTruthy();
    expect(await outbox()).toHaveLength(0);
  });

  it('swipe hides the row with an Undo toast; Undo writes nothing', async () => {
    await seed([store('s1', 'Test Market')]);
    await renderAt('/shopping/stores');
    swipe((await screen.findByRole('button', { name: /^Test Market/ })).parentElement!);
    expect(screen.queryByRole('button', { name: /^Test Market/ })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Undo' }));
    expect(await screen.findByRole('button', { name: /^Test Market/ })).toBeTruthy();
    await wait(300);
    expect(await outbox()).toHaveLength(0);
  });

  it('the Delete button writes one delete after the undo window', async () => {
    await seed([store('s1', 'Test Market')]);
    await renderAt('/shopping/stores');
    fireEvent.click(await screen.findByRole('button', { name: 'Delete Test Market' }));
    expect(screen.getByText('Deleted Test Market')).toBeTruthy();
    await waitFor(async () => expect(await outbox()).toHaveLength(1));
    expect((await outbox())[0]).toMatchObject({ table: 'store', op: 'delete', row_id: 's1' });
  });

  it('module off renders nothing and does not throw', async () => {
    await seed();
    await renderAt('/shopping/stores', house({ module_shopping: false }));
    await wait(50);
    expect(screen.queryByTestId('stores')).toBeNull();
  });

  it('the Lists header Stores link opens the screen', async () => {
    await seed();
    const { router } = await renderAt('/shopping');
    fireEvent.click(await screen.findByRole('link', { name: 'Stores' }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/shopping/stores'));
  });
});

describe('SHOP-05: store picker on List detail', () => {
  it('AC3 pick Test Market and Save: chip reads Test Market, only store_id is queued', async () => {
    await seed([store('s1', 'Test Market')], [list('l1', 'Groceries')]);
    await renderAt('/shopping/l1');
    fireEvent.click(await screen.findByRole('button', { name: 'Add store' }));
    expect(screen.getByRole('button', { name: 'Add store' }).textContent).toBe('Add store');
    fireEvent.click(await screen.findByRole('radio', { name: 'Test Market' }));
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    const chip = await screen.findByRole('button', { name: 'Store: Test Market' });
    expect(chip.textContent).toBe('Test Market');
    const out = await outbox();
    expect(out).toHaveLength(1);
    expect(out[0]).toMatchObject({ table: 'shopping_list', op: 'upsert', row_id: 'l1', data: { store_id: 's1' } });
  });

  it('No store clears the chip back to Add store', async () => {
    await seed([store('s1', 'Test Market')], [list('l1', 'Groceries', { store_id: 's1' })]);
    await renderAt('/shopping/l1');
    fireEvent.click(await screen.findByRole('button', { name: 'Store: Test Market' }));
    fireEvent.click(await screen.findByRole('radio', { name: 'No store' }));
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect((await screen.findByRole('button', { name: 'Add store' })).textContent).toBe('Add store');
    expect((await outbox())[0]!.data).toEqual({ store_id: null });
  });

  it('Save without a change writes nothing', async () => {
    await seed([store('s1', 'Test Market')], [list('l1', 'Groceries')]);
    await renderAt('/shopping/l1');
    fireEvent.click(await screen.findByRole('button', { name: 'Add store' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Save' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(await outbox()).toHaveLength(0);
  });

  it('a deleted store makes the chip read Add store', async () => {
    await seed([store('s1', 'Gone', { deleted_at: '2026-01-01T00:00:00Z' })], [list('l1', 'Groceries', { store_id: 's1' })]);
    await renderAt('/shopping/l1');
    expect((await screen.findByRole('button', { name: 'Add store' })).textContent).toBe('Add store');
  });

  it('inline New store creates and selects it in one step', async () => {
    await seed([], [list('l1', 'Groceries')]);
    await renderAt('/shopping/l1');
    fireEvent.click(await screen.findByRole('button', { name: 'Add store' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('New store'), { target: { value: 'Corner Shop' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Add' }));
    const radio = await within(dialog).findByRole('radio', { name: 'Corner Shop' });
    await waitFor(() => expect((radio as HTMLInputElement).checked).toBe(true));
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }));
    expect(await screen.findByRole('button', { name: 'Store: Corner Shop' })).toBeTruthy();
    const out = await outbox();
    expect(out.map((o) => o.table)).toEqual(['store', 'shopping_list']);
  });

  it('Save right after Add keeps the new store selected without waiting for the list to refresh', async () => {
    await seed([], [list('l1', 'Groceries')]);
    await renderAt('/shopping/l1');
    fireEvent.click(await screen.findByRole('button', { name: 'Add store' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('New store'), { target: { value: 'Corner Shop' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Add' }));
    // The field clears in the same turn the new id is selected; Save follows before the live query has published the row.
    await waitFor(() => expect((within(dialog).getByLabelText('New store') as HTMLInputElement).value).toBe(''));
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }));
    expect(await screen.findByRole('button', { name: 'Store: Corner Shop' })).toBeTruthy();
    expect((await outbox()).map((o) => o.table)).toEqual(['store', 'shopping_list']);
  });

  it('Manage stores navigates to /shopping/stores', async () => {
    await seed([], [list('l1', 'Groceries')]);
    const { router } = await renderAt('/shopping/l1');
    fireEvent.click(await screen.findByRole('button', { name: 'Add store' }));
    fireEvent.click(await screen.findByRole('link', { name: 'Manage stores' }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/shopping/stores'));
  });
});
