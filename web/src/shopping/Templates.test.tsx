import type { Household, ListItem, Profile, ShoppingList } from '@abodenestly/contract';
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
import { Lists } from './Lists.tsx';

const house = () => ({ id: 'h1', name: 'Home One', currency: 'EUR', module_shopping: true, module_pantry: true, module_maintenance: true }) as Household;
const list = (id: string, name: string, over: Partial<ShoppingList> = {}) =>
  ({ id, household_id: 'h1', name, store_id: null, is_template: false, archived_at: null, deleted_at: null, created_at: `2026-01-01T00:00:0${id.length}Z`, ...over }) as ShoppingList;
const item = (id: string, list_id: string, name: string, over: Partial<ListItem> = {}) =>
  ({ id, household_id: 'h1', list_id, name, name_key: name.toLowerCase(), checked: false, deleted_at: null, created_at: `2026-01-01T00:00:0${id.length}Z`, ...over }) as ListItem;

async function seed(lists: ShoppingList[], items: ListItem[] = []) {
  const db = await openHouseholdDb('h1');
  await db.shopping_list.bulkPut(lists);
  await db.list_item.bulkPut(items);
  await db.close();
}

async function renderAt(path: string) {
  const api = new FakeApiClient();
  api.fetchHousehold = async () => ({ ok: true, value: { household: house(), role: 'owner' } });
  api.fetchMyHouseholdsResult = { ok: true, value: [{ household: house(), role: 'owner' }] };
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
  return { router };
}

async function stored() {
  const db = await openHouseholdDb('h1');
  const [items, out] = [await db.list_item.toArray(), await db.outbox.toArray()];
  await db.close();
  return { items, out };
}

const openMenu = async () => fireEvent.click(await screen.findByRole('button', { name: 'List actions' }));
const dialog = () => screen.findByRole('dialog');

beforeEach(async () => {
  localStorage.clear();
  await Dexie.delete('abodenestly-h1');
});
afterEach(() => {
  cleanup();
  Object.defineProperty(navigator, 'onLine', { value: true, configurable: true });
});

describe('SHOP-11: template lists', () => {
  it('the Template switch is off by default', async () => {
    await seed([list('a', 'Groceries')]);
    await renderAt('/shopping');
    fireEvent.click((await screen.findAllByRole('button', { name: 'New list' }))[0]!);
    expect((within(await dialog()).getByRole('switch', { name: 'Template' }) as HTMLInputElement).checked).toBe(false);
  });

  it('AC1 Template on, Save: the row sits under Templates, not Lists', async () => {
    await seed([list('a', 'Groceries')]);
    const { router } = await renderAt('/shopping');
    fireEvent.click((await screen.findAllByRole('button', { name: 'New list' }))[0]!);
    const d = await dialog();
    fireEvent.change(within(d).getByLabelText('Name'), { target: { value: 'Weekly' } });
    fireEvent.click(within(d).getByRole('switch', { name: 'Template' }));
    fireEvent.click(within(d).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(router.state.location.pathname).not.toBe('/shopping'));
    await router.navigate('/shopping');
    const templates = (await screen.findByRole('heading', { name: 'Templates' })).closest('section')!;
    expect(within(templates).getByText('Weekly')).toBeTruthy();
    const lists = screen.getByRole('heading', { name: 'Lists' }).closest('section')!;
    expect(within(lists).queryByText('Weekly')).toBeNull();
    expect(within(lists).getByText('Groceries')).toBeTruthy();
  });

  it('a template opens C2 with a Template caption and List actions shows Add all to list with nothing checked', async () => {
    await seed([list('t', 'Weekly', { is_template: true })], [item('1', 't', 'Milk')]);
    await renderAt('/shopping/t');
    expect(await screen.findByText('Template')).toBeTruthy();
    await openMenu();
    expect(screen.getByRole('menuitem', { name: 'Add all to list' })).toBeTruthy();
    expect(screen.queryByRole('menuitem', { name: 'Clear checked' })).toBeNull();
  });

  it('a normal list menu has no Add all to list', async () => {
    await seed([list('a', 'Groceries')], [item('1', 'a', 'Milk', { checked: true })]);
    await renderAt('/shopping/a');
    expect(screen.queryByText('Template')).toBeNull();
    await openMenu();
    expect(screen.queryByRole('menuitem', { name: 'Add all to list' })).toBeNull();
    expect(screen.getByRole('menuitem', { name: 'Clear checked' })).toBeTruthy();
  });

  it('AC2 pick Groceries: its items grow and the toast appears', async () => {
    await seed([list('a', 'Groceries'), list('t', 'Weekly', { is_template: true })], [item('1', 't', 'Milk'), item('22', 't', 'Bread', { checked: true })]);
    const { router } = await renderAt('/shopping/t');
    await openMenu();
    fireEvent.click(screen.getByRole('menuitem', { name: 'Add all to list' }));
    fireEvent.click(await within(await dialog()).findByRole('button', { name: 'Groceries' }));
    expect(await screen.findByText('Added 2 items to Groceries')).toBeTruthy();
    expect(router.state.location.pathname).toBe('/shopping/t');
    const { items, out } = await stored();
    expect(items.filter((i) => i.list_id === 'a').map((i) => i.name).sort()).toEqual(['Bread', 'Milk']);
    expect(out).toHaveLength(2);
    await router.navigate('/shopping/a');
    const rows = await screen.findByRole('list', { name: 'Items' });
    await waitFor(() => expect(within(rows).getByText('Bread')).toBeTruthy());
    expect(within(rows).getByText('Milk')).toBeTruthy();
  });

  it('the sheet lists only active non-templates', async () => {
    await seed([
      list('a', 'Groceries'),
      list('t', 'Weekly', { is_template: true }),
      list('t2', 'Other template', { is_template: true }),
      list('o', 'Old', { archived_at: '2026-02-01T00:00:00Z' }),
      list('d', 'Gone', { deleted_at: '2026-02-01T00:00:00Z' }),
    ]);
    await renderAt('/shopping/t');
    await openMenu();
    fireEvent.click(screen.getByRole('menuitem', { name: 'Add all to list' }));
    const d = await dialog();
    expect(within(d).getAllByRole('button').map((b) => b.textContent)).toEqual(['Close', 'Groceries']);
  });

  it('says Make a list first when there is no target', async () => {
    await seed([list('t', 'Weekly', { is_template: true })]);
    await renderAt('/shopping/t');
    await openMenu();
    fireEvent.click(screen.getByRole('menuitem', { name: 'Add all to list' }));
    const d = await dialog();
    expect(within(d).getByText('Make a list first')).toBeTruthy();
    expect(within(d).queryByRole('list', { name: 'Lists' })).toBeNull();
  });

  it('AC3 an empty template tells the person and leaves the target unchanged', async () => {
    await seed([list('a', 'Groceries'), list('t', 'Weekly', { is_template: true })], [item('1', 'a', 'Eggs')]);
    await renderAt('/shopping/t');
    await openMenu();
    fireEvent.click(screen.getByRole('menuitem', { name: 'Add all to list' }));
    fireEvent.click(await within(await dialog()).findByRole('button', { name: 'Groceries' }));
    expect(await screen.findByText('Nothing to add: this template is empty')).toBeTruthy();
    const { items, out } = await stored();
    expect(items).toHaveLength(1);
    expect(out).toHaveLength(0);
  });

  it('AC4 offline: the rows exist and the outbox holds them', async () => {
    Object.defineProperty(navigator, 'onLine', { value: false, configurable: true });
    await seed([list('a', 'Groceries'), list('t', 'Weekly', { is_template: true })], [item('1', 't', 'Milk'), item('22', 't', 'Bread')]);
    await renderAt('/shopping/t');
    await openMenu();
    fireEvent.click(screen.getByRole('menuitem', { name: 'Add all to list' }));
    fireEvent.click(await within(await dialog()).findByRole('button', { name: 'Groceries' }));
    expect(await screen.findByText('Added 2 items to Groceries')).toBeTruthy();
    const { items, out } = await stored();
    expect(items.filter((i) => i.list_id === 'a')).toHaveLength(2);
    expect(out.map((o) => (o.payload as { table: string }).table)).toEqual(['list_item', 'list_item']);
  });
});
