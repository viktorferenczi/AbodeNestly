import type { Household, ListItem, Profile, ShoppingList } from '@abodenestly/contract';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import Dexie from 'dexie';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
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

vi.mock('./Lists.ts', async (orig) => ({ ...(await orig<typeof import('./Lists.ts')>()), UNDO_MS: 150 }));

const house = (over: Partial<Household> = {}) =>
  ({ id: 'h1', name: 'Home One', currency: 'EUR', module_shopping: true, module_pantry: true, module_maintenance: true, ...over }) as Household;
const list = (id: string, name: string, over: Partial<ShoppingList> = {}) =>
  ({ id, household_id: 'h1', name, store_id: null, is_template: false, archived_at: null, deleted_at: null, created_at: '2026-01-01T00:00:00Z', ...over }) as ShoppingList;
const item = (id: string, list_id: string, name: string, over: Partial<ListItem> = {}) =>
  ({ id, household_id: 'h1', list_id, name, name_key: name.toLowerCase(), checked: false, deleted_at: null, created_at: `2026-01-01T00:00:0${id.length}Z`, ...over }) as ListItem;

async function seed(lists: ShoppingList[], items: ListItem[] = []) {
  const db = await openHouseholdDb('h1');
  await db.shopping_list.bulkPut(lists);
  await db.list_item.bulkPut(items);
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
  const [items, out] = [await db.list_item.toArray(), await db.outbox.toArray()];
  await db.close();
  return { items, out };
}

const items = () => screen.findByRole('list', { name: 'Items' });

beforeEach(async () => {
  localStorage.clear();
  await Dexie.delete('abodenestly-h1');
});
afterEach(() => {
  cleanup();
  Object.defineProperty(navigator, 'onLine', { value: true, configurable: true });
});


const WINDOW = { timeout: 2000 };
const two = () => [item('1', 'a', 'Milk'), item('22', 'a', 'Bread')];

describe('SHOP-04: check off', () => {
  it('AC1: tapping the checkbox ticks the row in place and shows the Undo toast', async () => {
    await seed([list('a', 'Groceries')], two());
    await renderDetail();
    fireEvent.click(await screen.findByRole('checkbox', { name: 'Check off Milk' }));
    expect((screen.getByRole('checkbox', { name: 'Uncheck Milk' }) as HTMLInputElement).checked).toBe(true);
    expect(within(await items()).getByText('Milk')).toBeTruthy();
    expect(screen.getByText('Checked Milk')).toBeTruthy();
  });

  it('AC2: Undo within the window leaves the row and the outbox untouched', async () => {
    await seed([list('a', 'Groceries')], [item('1', 'a', 'Milk')]);
    await renderDetail();
    fireEvent.click(await screen.findByRole('checkbox', { name: 'Check off Milk' }));
    fireEvent.click(screen.getByRole('button', { name: 'Undo' }));
    expect((screen.getByRole('checkbox', { name: 'Check off Milk' }) as HTMLInputElement).checked).toBe(false);
    expect(screen.queryByText('Checked Milk')).toBeNull();
    await new Promise((r) => setTimeout(r, 300));
    const { items: rows, out } = await stored();
    expect(rows[0]!.checked).toBe(false);
    expect(out).toHaveLength(0);
  });

  it('AC3: after the window the item moves to Checked (newest first) and one outbox row is queued each', async () => {
    await seed([list('a', 'Groceries')], two());
    const { sync } = await renderDetail();
    fireEvent.click(await screen.findByRole('checkbox', { name: 'Check off Milk' }));
    await new Promise((r) => setTimeout(r, 20));
    fireEvent.click(screen.getByRole('checkbox', { name: 'Check off Bread' }));
    await screen.findByText('Checked (2)', undefined, WINDOW);
    const group = screen.getByText('Checked (2)').closest('details')!;
    expect(within(group).getAllByRole('checkbox').map((c) => c.getAttribute('aria-label'))).toEqual(['Uncheck Bread', 'Uncheck Milk']);
    const { items: rows, out } = await stored();
    expect(rows.every((r) => r.checked && r.checked_by === 'u1' && r.checked_at)).toBe(true);
    expect(out).toHaveLength(2);
    expect(out[0]!.payload.data).toEqual({ checked: true, checked_at: expect.any(String), checked_by: 'u1' });
    expect(sync.kicks).toBe(2);
  });

  it('AC4/AC5: all checked shows All done; Clear checked soft-deletes, Undo restores', async () => {
    await seed([list('a', 'Groceries')], [item('1', 'a', 'Milk', { checked: true, checked_at: '2026-01-02T00:00:00Z' }), item('22', 'a', 'Bread', { checked: true, checked_at: '2026-01-03T00:00:00Z' })]);
    await renderDetail();
    expect(await screen.findByText('All done')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Clear checked' }));
    expect(screen.getByText('Cleared 2 items')).toBeTruthy();
    expect(screen.queryByText('Checked (2)')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Undo' }));
    expect(await screen.findByText('Checked (2)')).toBeTruthy();
    await new Promise((r) => setTimeout(r, 300));
    expect((await stored()).out).toHaveLength(0);

    fireEvent.click(screen.getByRole('button', { name: 'Clear checked' }));
    await waitFor(async () => expect((await stored()).out).toHaveLength(2), WINDOW);
    const { items: rows } = await stored();
    expect(rows.every((r) => r.deleted_at)).toBe(true);
    expect(screen.queryByText('Checked (2)')).toBeNull();
  });

  it('overflow: List actions offers Clear checked only when something is checked', async () => {
    await seed([list('a', 'Groceries')], [item('1', 'a', 'Milk'), item('22', 'a', 'Bread', { checked: true, checked_at: '2026-01-03T00:00:00Z' })]);
    await renderDetail();
    fireEvent.click(await screen.findByRole('button', { name: 'List actions' }));
    fireEvent.click(screen.getByRole('menuitem', { name: 'Clear checked' }));
    expect(screen.getByText('Cleared 1 item')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'List actions' })).toBeNull();
  });

  it('no checked items: no overflow menu', async () => {
    await seed([list('a', 'Groceries')], [item('1', 'a', 'Milk')]);
    await renderDetail();
    await screen.findByText('Milk');
    expect(screen.queryByRole('button', { name: 'List actions' })).toBeNull();
  });

  it('unchecking from the Checked group writes at once, with no toast', async () => {
    await seed([list('a', 'Groceries')], [item('1', 'a', 'Milk', { checked: true, checked_at: '2026-01-03T00:00:00Z', checked_by: 'u1' })]);
    await renderDetail();
    fireEvent.click(await screen.findByText('Checked (1)'));
    fireEvent.click(screen.getByRole('checkbox', { name: 'Uncheck Milk' }));
    expect(await within(await items()).findByText('Milk')).toBeTruthy();
    expect(screen.queryByRole('status')).toBeNull();
    const { items: rows, out } = await stored();
    expect(rows[0]).toMatchObject({ checked: false, checked_at: null, checked_by: null });
    expect(out).toHaveLength(1);
  });

  it('AC6 offline: the check-off is queued after the window', async () => {
    Object.defineProperty(navigator, 'onLine', { value: false, configurable: true });
    await seed([list('a', 'Groceries')], [item('1', 'a', 'Milk')]);
    await renderDetail();
    fireEvent.click(await screen.findByRole('checkbox', { name: 'Check off Milk' }));
    await screen.findByText('Checked (1)', undefined, WINDOW);
    expect((await stored()).out).toHaveLength(1);
  });

  it('module off renders nothing', async () => {
    await seed([list('a', 'Groceries')], [item('1', 'a', 'Milk')]);
    await renderDetail('/shopping/a', house({ module_shopping: false }));
    await new Promise((r) => setTimeout(r, 50));
    expect(screen.queryByTestId('list-detail')).toBeNull();
  });
});
