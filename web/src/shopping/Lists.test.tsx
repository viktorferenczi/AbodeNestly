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
import { Lists } from './Lists.tsx';

// A 5 s wait per case would make the suite crawl; the constant itself is asserted in Lists.test.ts.
vi.mock('./Lists.ts', async (orig) => ({ ...(await orig<typeof import('./Lists.ts')>()), UNDO_MS: 150 }));

const house = (over: Partial<Household> = {}) =>
  ({ id: 'h1', name: 'Home One', currency: 'EUR', module_shopping: true, module_pantry: true, module_maintenance: true, ...over }) as Household;
const list = (id: string, name: string, over: Partial<ShoppingList> = {}) =>
  ({ id, household_id: 'h1', name, store_id: null, is_template: false, archived_at: null, deleted_at: null, created_at: `2026-01-01T00:00:0${id.length}Z`, ...over }) as ShoppingList;

async function seed(lists: ShoppingList[] = [], items: ListItem[] = [], stores: Store[] = []) {
  const db = await openHouseholdDb('h1');
  await db.shopping_list.bulkPut(lists);
  await db.list_item.bulkPut(items);
  await db.store.bulkPut(stores);
  await db.close();
}

async function renderLists(household = house()) {
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
              { path: '/shopping', element: <SyncProvider engine={sync}><Lists /></SyncProvider> },
              { path: '/shopping/:listId', element: <p>detail</p> },
            ],
          },
        ],
      },
    ],
    { initialEntries: ['/shopping'] },
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
const open = (name: string) => screen.findByRole('button', { name: new RegExp(`^${name}`) });
const wait = (ms: number) => new Promise((r) => setTimeout(r, ms));

beforeEach(async () => {
  localStorage.clear();
  await Dexie.delete('abodenestly-h1');
});
afterEach(() => {
  cleanup();
  Object.defineProperty(navigator, 'onLine', { value: true, configurable: true });
});

describe('SHOP-01: Lists overview', () => {
  it('AC1 empty state', async () => {
    await seed();
    await renderLists();
    expect(await screen.findByText('Make your first list')).toBeTruthy();
    expect(screen.queryByRole('button', { name: /^Archive/ })).toBeNull();
  });

  it('AC2 populated: name, store chip, checked count; templates and archived sections', async () => {
    await seed(
      [list('a', 'Groceries', { store_id: 's1' }), list('bb', 'Weekly', { is_template: true }), list('ccc', 'Old', { archived_at: '2026-02-01T00:00:00Z' })],
      Array.from({ length: 12 }, (_, i) => ({ id: `i${i}`, household_id: 'h1', list_id: 'a', checked: i < 3, deleted_at: null }) as ListItem),
      [{ id: 's1', household_id: 'h1', name: 'Lidl', deleted_at: null } as Store],
    );
    await renderLists();
    const row = (await open('Groceries')).closest('li')!;
    expect(within(row).getByText('Lidl')).toBeTruthy();
    expect(within(row).getByText('3 of 12')).toBeTruthy();
    expect(screen.getByText('Templates')).toBeTruthy();
    expect(screen.getByText('Archived (1)')).toBeTruthy();
  });

  it('tap opens the list', async () => {
    await seed([list('a', 'Groceries')]);
    const { router } = await renderLists();
    fireEvent.click(await open('Groceries'));
    await waitFor(() => expect(router.state.location.pathname).toBe('/shopping/a'));
  });

  it('AC3 swipe archives after the undo window and moves the row to Archived', async () => {
    await seed([list('a', 'Groceries'), list('bb', 'Other')]);
    const { sync } = await renderLists();
    swipe((await open('Groceries')).parentElement!);
    expect(screen.queryByRole('button', { name: /^Groceries/ })).toBeNull();
    expect(screen.getByText('Archived Groceries')).toBeTruthy();
    expect(await outbox()).toHaveLength(0);
    await waitFor(() => expect(screen.getByText('Archived (1)')).toBeTruthy());
    const out = await outbox();
    expect(out).toHaveLength(1);
    expect(out[0]).toMatchObject({ table: 'shopping_list', op: 'upsert', row_id: 'a' });
    expect(Object.keys(out[0]!.data!)).toEqual(['archived_at']);
    await waitFor(() => expect(sync.kicks).toBe(1));
  });

  it('AC4 Undo keeps the list and writes nothing', async () => {
    await seed([list('a', 'Groceries')]);
    await renderLists();
    swipe((await open('Groceries')).parentElement!);
    fireEvent.click(screen.getByRole('button', { name: 'Undo' }));
    expect(await open('Groceries')).toBeTruthy();
    await wait(300);
    expect(await outbox()).toHaveLength(0);
    expect(screen.queryByText(/^Archived \(/)).toBeNull();
  });

  it('the Archive button works without a swipe', async () => {
    await seed([list('a', 'Groceries')]);
    await renderLists();
    fireEvent.click(await screen.findByRole('button', { name: 'Archive Groceries' }));
    await waitFor(() => expect(screen.getByText('Archived (1)')).toBeTruthy());
  });

  it('leaving the screen writes a pending archive', async () => {
    await seed([list('a', 'Groceries')]);
    const { router } = await renderLists();
    fireEvent.click(await screen.findByRole('button', { name: 'Archive Groceries' }));
    await router.navigate('/');
    await waitFor(async () => expect(await outbox()).toHaveLength(1));
  });

  it('Restore clears archived_at; swipe on an archived row deletes with undo', async () => {
    await seed([list('a', 'Old', { archived_at: '2026-02-01T00:00:00Z' }), list('bb', 'Older', { archived_at: '2026-01-01T00:00:00Z' })]);
    await renderLists();
    fireEvent.click(await screen.findByText('Archived (2)'));
    fireEvent.click(screen.getByRole('button', { name: 'Restore Old' }));
    await waitFor(() => expect(screen.getByText('Archived (1)')).toBeTruthy());
    swipe(screen.getByRole('button', { name: /^Older/ }).parentElement!);
    await waitFor(async () => expect((await outbox()).map((o) => o.op)).toEqual(['upsert', 'delete']));
  });

  it('"+" and the FAB open the sheet; empty name cannot be saved', async () => {
    await seed([list('a', 'Groceries')]);
    await renderLists();
    await open('Groceries');
    fireEvent.click(screen.getAllByRole('button', { name: 'New list' })[0]!);
    const dialog = await screen.findByRole('dialog');
    expect((within(dialog).getByRole('button', { name: 'Save' }) as HTMLButtonElement).disabled).toBe(true);
    expect(within(dialog).queryByLabelText('Store')).toBeNull();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Close' }));
    expect(screen.queryByRole('dialog')).toBeNull();
    fireEvent.click(screen.getAllByRole('button', { name: 'New list' }).at(-1)!);
    expect(await screen.findByRole('dialog')).toBeTruthy();
  });

  it('AC5 save creates the list with its store and opens it', async () => {
    await seed([list('a', 'Groceries')], [], [{ id: 's1', household_id: 'h1', name: 'Lidl', deleted_at: null } as Store]);
    const { router, sync } = await renderLists();
    await open('Groceries');
    fireEvent.click(screen.getAllByRole('button', { name: 'New list' })[0]!);
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('Name'), { target: { value: 'Hardware' } });
    fireEvent.change(within(dialog).getByLabelText('Store'), { target: { value: 's1' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(router.state.location.pathname).toMatch(/^\/shopping\/.+/));
    const out = await outbox();
    expect(out[0]).toMatchObject({ table: 'shopping_list', op: 'upsert', data: { name: 'Hardware', store_id: 's1' } });
    expect(sync.kicks).toBe(1);
  });

  it('AC6 offline: banner shows and a created list is queued once', async () => {
    Object.defineProperty(navigator, 'onLine', { value: false, configurable: true });
    await seed();
    const { router } = await renderLists();
    expect(await screen.findByText(/Offline\. Changes sync/)).toBeTruthy();
    fireEvent.click(screen.getAllByRole('button', { name: 'New list' })[0]!);
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('Name'), { target: { value: 'Hardware' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(router.state.location.pathname).toMatch(/^\/shopping\/.+/));
    expect(await outbox()).toHaveLength(1);
  });

  it('module off redirects to /', async () => {
    await seed([list('a', 'Groceries')]);
    const { router } = await renderLists(house({ module_shopping: false }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/'));
    expect(screen.queryByText('Shopping')).toBeNull();
  });
});
