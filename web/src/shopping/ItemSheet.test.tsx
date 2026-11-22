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

const milk = () => item('1', 'a', 'Milk', { quantity: 2, unit: 'l', note: 'lactose free' });
const openMilk = async () => {
  fireEvent.click(await screen.findByRole('button', { name: /Milk/ }));
  return sheet();
};

describe('SHOP-03: item sheet', () => {
  it('state new: Details opens a prefilled sheet; Save adds the row with nulls (AC1) and clears the bar', async () => {
    await seed([list('a', 'Groceries')]);
    const { sync } = await renderDetail();
    fireEvent.change(await box(), { target: { value: 'Bread' } });
    fireEvent.click(screen.getByRole('button', { name: 'Details' }));
    const dlg = await sheet();
    expect(within(dlg).getByRole('heading', { name: 'New item' })).toBeTruthy();
    expect((within(dlg).getByLabelText('Name') as HTMLInputElement).value).toBe('Bread');
    fireEvent.click(within(dlg).getByRole('button', { name: 'Save' }));
    expect(await within(await items()).findByText('Bread')).toBeTruthy();
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect((await box() as HTMLInputElement).value).toBe('');
    const { items: rows, out } = await stored();
    expect(rows[0]).toMatchObject({ name: 'Bread', quantity: null, unit: null, note: null });
    expect(out).toHaveLength(1);
    expect(sync.kicks).toBe(1);
  });

  it('state new: all three fields set at creation', async () => {
    await seed([list('a', 'Groceries')]);
    await renderDetail();
    fireEvent.click(await screen.findByRole('button', { name: 'Details' }));
    const dlg = await sheet();
    fireEvent.change(within(dlg).getByLabelText('Name'), { target: { value: 'Rice' } });
    fireEvent.change(within(dlg).getByRole('textbox', { name: 'Quantity' }), { target: { value: '0,5' } });
    fireEvent.change(within(dlg).getByLabelText('Unit'), { target: { value: 'kg' } });
    fireEvent.change(within(dlg).getByLabelText('Note'), { target: { value: 'basmati' } });
    fireEvent.click(within(dlg).getByRole('button', { name: 'Save' }));
    expect(await within(await items()).findByText('0.5 kg')).toBeTruthy();
    expect((await stored()).items[0]).toMatchObject({ name: 'Rice', quantity: 0.5, unit: 'kg', note: 'basmati' });
  });

  it('state edit / AC2: the row shows qty+unit and note, the sheet shows the fields', async () => {
    await seed([list('a', 'Groceries')], [milk()]);
    await renderDetail();
    expect((await items()).textContent).toContain('2 l');
    expect((await items()).textContent).toContain('lactose free');
    const dlg = await openMilk();
    expect(within(dlg).getByRole('heading', { name: 'Edit item' })).toBeTruthy();
    expect((within(dlg).getByRole('textbox', { name: 'Quantity' }) as HTMLInputElement).value).toBe('2');
    expect((within(dlg).getByLabelText('Unit') as HTMLInputElement).value).toBe('l');
    expect((within(dlg).getByLabelText('Note') as HTMLTextAreaElement).value).toBe('lactose free');
  });

  it('AC2: saving new values updates the row', async () => {
    await seed([list('a', 'Groceries')], [item('1', 'a', 'Milk')]);
    await renderDetail();
    const dlg = await openMilk();
    fireEvent.click(within(dlg).getByRole('button', { name: 'Increase quantity' }));
    fireEvent.click(within(dlg).getByRole('button', { name: 'Increase quantity' }));
    fireEvent.change(within(dlg).getByLabelText('Unit'), { target: { value: 'l' } });
    fireEvent.change(within(dlg).getByLabelText('Note'), { target: { value: 'lactose free' } });
    fireEvent.click(within(dlg).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect((screen.getByRole('list', { name: 'Items' }).textContent ?? '')).toContain('2 l'));
    expect((await items()).textContent).toContain('lactose free');
  });

  it('AC3: clearing the note removes it, the quantity stays, only the note is queued', async () => {
    await seed([list('a', 'Groceries')], [milk()]);
    await renderDetail();
    const dlg = await openMilk();
    fireEvent.change(within(dlg).getByLabelText('Note'), { target: { value: '' } });
    fireEvent.click(within(dlg).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect((screen.getByRole('list', { name: 'Items' }).textContent ?? '')).not.toContain('lactose free'));
    expect((await items()).textContent).toContain('2 l');
    const { out } = await stored();
    expect(out).toHaveLength(1);
    expect((out[0]!.payload as { data: object }).data).toEqual({ note: null });
  });

  it('stepper: + on empty gives 1, − at 1 empties it; Save stays enabled', async () => {
    await seed([list('a', 'Groceries')], [item('1', 'a', 'Milk')]);
    await renderDetail();
    const dlg = await openMilk();
    const qty = within(dlg).getByRole('textbox', { name: 'Quantity' }) as HTMLInputElement;
    fireEvent.click(within(dlg).getByRole('button', { name: 'Increase quantity' }));
    expect(qty.value).toBe('1');
    fireEvent.click(within(dlg).getByRole('button', { name: 'Decrease quantity' }));
    expect(qty.value).toBe('');
  });

  it('unit field offers presets and the household units', async () => {
    await seed([list('a', 'Groceries')], [item('1', 'a', 'Parsley', { unit: 'bunch' })]);
    await renderDetail();
    const dlg = await openMilk2('Parsley');
    const options = Array.from(dlg.querySelectorAll('datalist option')).map((o) => o.getAttribute('value'));
    expect(options).toEqual(expect.arrayContaining(['pcs', 'kg', 'can', 'bunch']));
  });

  it('Save is disabled for a blank name or an invalid quantity, with a message', async () => {
    await seed([list('a', 'Groceries')], [item('1', 'a', 'Milk')]);
    await renderDetail();
    const dlg = await openMilk();
    const save = within(dlg).getByRole('button', { name: 'Save' }) as HTMLButtonElement;
    fireEvent.change(within(dlg).getByRole('textbox', { name: 'Quantity' }), { target: { value: '0' } });
    expect(save.disabled).toBe(true);
    expect(within(dlg).getByText('Enter a number above 0')).toBeTruthy();
    fireEvent.change(within(dlg).getByRole('textbox', { name: 'Quantity' }), { target: { value: '' } });
    expect(save.disabled).toBe(false);
    fireEvent.change(within(dlg).getByLabelText('Name'), { target: { value: '  ' } });
    expect(save.disabled).toBe(true);
  });

  it('Esc closes without writing', async () => {
    await seed([list('a', 'Groceries')], [item('1', 'a', 'Milk')]);
    await renderDetail();
    const dlg = await openMilk();
    fireEvent.change(within(dlg).getByLabelText('Note'), { target: { value: 'x' } });
    fireEvent.keyDown(dlg, { key: 'Escape' });
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect((await stored()).out).toHaveLength(0);
  });

  it('delete removes the row and shows an undo toast; Undo restores it', async () => {
    await seed([list('a', 'Groceries')], [milk()]);
    await renderDetail();
    const dlg = await openMilk();
    fireEvent.click(within(dlg).getByRole('button', { name: 'Delete' }));
    await waitFor(() => expect(within(screen.getByRole('list', { name: 'Items' })).queryByText('Milk')).toBeNull());
    expect(await screen.findByText('Deleted Milk')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Undo' }));
    expect(await within(await items()).findByText('Milk')).toBeTruthy();
    expect(screen.queryByText('Deleted Milk')).toBeNull();
    const { out } = await stored();
    expect(out.map((o) => o.op)).toEqual(['delete', 'upsert']);
  });

  it('the undo toast goes away after 5 seconds', async () => {
    await seed([list('a', 'Groceries')], [milk()]);
    await renderDetail();
    const dlg = await openMilk();
    fireEvent.click(within(dlg).getByRole('button', { name: 'Delete' }));
    expect(await screen.findByText('Deleted Milk')).toBeTruthy();
    await waitFor(() => expect(screen.queryByText('Deleted Milk')).toBeNull(), { timeout: 6000 });
  }, 10000);

  it('a checked item opens the sheet too', async () => {
    await seed([list('a', 'Groceries')], [item('1', 'a', 'Bread', { checked: true })]);
    await renderDetail();
    fireEvent.click(await screen.findByText('Checked (1)'));
    fireEvent.click(await screen.findByRole('button', { name: /Bread/ }));
    expect(within(await sheet()).getByRole('heading', { name: 'Edit item' })).toBeTruthy();
  });

  it('closes when the row is deleted elsewhere while open', async () => {
    await seed([list('a', 'Groceries')], [milk()]);
    await renderDetail();
    await openMilk();
    const db = await openHouseholdDb('h1');
    await db.list_item.update('1', { deleted_at: '2026-02-01T00:00:00Z' });
    await db.close();
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  });

  it('module off: nothing renders', async () => {
    await seed([list('a', 'Groceries')], [milk()]);
    await renderDetail('/shopping/a', house({ module_shopping: false }));
    await waitFor(() => expect(screen.queryByTestId('list-detail')).toBeNull());
    expect(screen.queryByRole('dialog')).toBeNull();
  });
});

async function openMilk2(name: string) {
  fireEvent.click(await screen.findByRole('button', { name: new RegExp(name) }));
  return sheet();
}
