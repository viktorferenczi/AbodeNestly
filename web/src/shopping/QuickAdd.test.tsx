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
  return { sync };
}

async function stored() {
  const db = await openHouseholdDb('h1');
  const [items, out] = [await db.list_item.toArray(), await db.outbox.toArray()];
  await db.close();
  return { items, out };
}

const box = () => screen.findByRole('combobox');

beforeEach(async () => {
  localStorage.clear();
  await Dexie.delete('abodenestly-h1');
});
afterEach(() => {
  cleanup();
  Object.defineProperty(navigator, 'onLine', { value: true, configurable: true });
});

describe('SHOP-02: list detail with quick add', () => {
  it('empty: shows the hint and focuses the bar', async () => {
    await seed([list('a', 'Groceries')]);
    await renderDetail();
    expect(await screen.findByText('Try: 2 l milk')).toBeTruthy();
    // The bar takes focus in an effect after the hint renders, so wait for it rather than reading it once.
    const input = await box();
    await waitFor(() => expect(document.activeElement).toBe(input));
  });

  it('populated: rows show names, the store chip and a read-only Checked section', async () => {
    await seed([list('a', 'Groceries', { store_id: 's1' })], [item('1', 'a', 'Eggs'), item('22', 'a', 'Bread', { checked: true })]);
    const db = await openHouseholdDb('h1');
    await db.store.put({ id: 's1', household_id: 'h1', name: 'Lidl', deleted_at: null } as never);
    await db.close();
    await renderDetail();
    const rows = await screen.findByRole('list', { name: 'Items' });
    expect(within(rows).getByText('Eggs')).toBeTruthy();
    expect(screen.getByText('Lidl')).toBeTruthy();
    expect(screen.getByText('Checked (1)')).toBeTruthy();
    expect(screen.queryByText('Try: 2 l milk')).toBeNull();
  });

  it('AC1/AC2: typing "mi" suggests Milk; tapping it adds a Milk row and clears the field', async () => {
    await seed([list('a', 'Groceries'), list('bb', 'Old')], [item('1', 'bb', 'Milk', { checked: true })]);
    const { sync } = await renderDetail();
    fireEvent.change(await box(), { target: { value: 'mi' } });
    fireEvent.click(await screen.findByRole('option', { name: 'Milk' }));
    expect(await within(await screen.findByRole('list', { name: 'Items' })).findByText('Milk')).toBeTruthy();
    expect((await box() as HTMLInputElement).value).toBe('');
    const { items, out } = await stored();
    expect(items.find((i) => i.list_id === 'a')).toMatchObject({ name: 'Milk', name_key: 'milk', created_by: 'u1' });
    expect(out).toHaveLength(1);
    expect(sync.kicks).toBe(1);
  });

  it('AC3: a name with no history is added as typed with Enter', async () => {
    await seed([list('a', 'Groceries')]);
    await renderDetail();
    const input = await box();
    fireEvent.change(input, { target: { value: 'Lightbulbs' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(await screen.findByText('Lightbulbs')).toBeTruthy();
  });

  it('keyboard: ArrowDown + Enter picks the highlighted suggestion', async () => {
    await seed([list('a', 'Groceries')], [item('1', 'bb', 'Milk', { checked: true })]);
    await renderDetail();
    const input = await box();
    fireEvent.change(input, { target: { value: 'mi' } });
    await screen.findByRole('option', { name: 'Milk' });
    fireEvent.keyDown(input, { key: 'ArrowDown' });
    fireEvent.keyDown(input, { key: 'Enter' });
    await waitFor(async () => expect((await stored()).items.map((i) => i.name)).toContain('Milk'));
  });

  it('re-adding a checked name adds a new unchecked row', async () => {
    await seed([list('a', 'Groceries')], [item('1', 'a', 'Milk', { checked: true })]);
    await renderDetail();
    const input = await box();
    fireEvent.change(input, { target: { value: 'mi' } });
    fireEvent.click(await screen.findByRole('option', { name: 'Milk' }));
    await waitFor(async () => expect((await stored()).items).toHaveLength(2));
  });

  it('AC4 offline: the sync chip says Offline, adding from a suggestion still adds the row and one outbox upsert', async () => {
    Object.defineProperty(navigator, 'onLine', { value: false, configurable: true });
    await seed([list('a', 'Groceries')], [item('1', 'bb', 'Milk', { checked: true })]);
    await renderDetail();
    expect(await screen.findByRole('button', { name: 'Offline' })).toBeTruthy();
    fireEvent.change(await box(), { target: { value: 'mi' } });
    fireEvent.click(await screen.findByRole('option', { name: 'Milk' }));
    await waitFor(async () => {
      const { items, out } = await stored();
      expect(items.filter((i) => i.list_id === 'a')).toHaveLength(1);
      expect(out).toHaveLength(1);
    });
  });

  it('keeps the typed text and shows an alert when the write fails', async () => {
    await seed([list('a', 'Groceries')]);
    await renderDetail();
    const input = (await box()) as HTMLInputElement;
    const spy = vi.spyOn(Dexie.prototype, 'transaction').mockRejectedValueOnce(new Error('closed'));
    fireEvent.change(input, { target: { value: 'Lightbulbs' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(input.value).toBe('Lightbulbs');
    spy.mockRestore();
  });

  it('an unknown list says it is gone and links back', async () => {
    await seed([]);
    await renderDetail('/shopping/nope');
    expect(await screen.findByText('This list is gone')).toBeTruthy();
    expect(within(screen.getByTestId('list-detail')).getByRole('link', { name: 'Shopping' }).getAttribute('href')).toBe('/shopping');
  });

  it('module off: renders nothing and redirects home', async () => {
    await seed([list('a', 'Groceries')]);
    await renderDetail('/shopping/a', house({ module_shopping: false }));
    expect(await screen.findByText('home')).toBeTruthy();
    expect(screen.queryByRole('combobox')).toBeNull();
  });
});
