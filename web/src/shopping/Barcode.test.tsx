import type { Household, ListItem, Product, Profile, ShoppingList } from '@abodenestly/contract';
import { createRoot } from 'react-dom/client';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
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
import { BarcodeScanner } from './BarcodeScanner.tsx';
import { FakeSync } from '../sync/FakeSync.ts';
import { openHouseholdDb } from '../sync/db.ts';
import { SyncProvider } from '../sync/SyncEngine.ts';
import { ListDetail } from './ListDetail.tsx';
import * as scannerModule from './ScannerDevice.ts';
import type { ScannerDevice, ScanSession } from './ScannerDevice.ts';

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

async function renderDetail(api = new FakeApiClient(), path = '/shopping/a', household = house()) {
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
  return { sync, api };
}


const nutella: Product = { barcode: '3017620422003', name: 'Nutella', brand: 'Ferrero', category: null, image_url: 'https://img.test/n.jpg', default_unit: null };

async function stored() {
  const db = await openHouseholdDb('h1');
  const [items, out] = [await db.list_item.toArray(), await db.outbox.toArray()];
  await db.close();
  return { items, out };
}

/** A hand-written fake camera: tests call `emit` to "read" a code. */
class FakeDevice implements ScannerDevice {
  isSupported = true;
  failWith: string | null = null;
  torch = false;
  torchCalls: boolean[] = [];
  stops = 0;
  private onCode: ((raw: string) => void) | null = null;
  supported = () => this.isSupported;
  async start(_video: HTMLVideoElement, onCode: (raw: string) => void): Promise<ScanSession> {
    if (this.failWith) throw Object.assign(new Error('denied'), { name: this.failWith });
    this.onCode = onCode;
    return {
      torch: this.torch,
      setTorch: async (on) => void this.torchCalls.push(on),
      stop: () => void (this.stops += 1),
    };
  }
  emit(raw: string) {
    this.onCode?.(raw);
  }
}

let device: FakeDevice;

beforeEach(async () => {
  localStorage.clear();
  await Dexie.delete('abodenestly-h1');
  device = new FakeDevice();
  vi.spyOn(scannerModule, 'browserScannerDevice', 'get').mockReturnValue(device);
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  Object.defineProperty(navigator, 'onLine', { value: true, configurable: true });
});

async function openScanner(api = new FakeApiClient()) {
  await seed([list('a', 'Groceries')]);
  const r = await renderDetail(api);
  fireEvent.click(await screen.findByRole('button', { name: 'Scan barcode' }));
  await screen.findByRole('dialog', { name: 'Scan barcode' });
  return r;
}

describe('SHOP-10: barcode scanner', () => {
  it('the QuickAddBar button opens the scanner', async () => {
    await openScanner();
    expect(screen.getByRole('dialog', { name: 'Scan barcode' })).toBeTruthy();
  });

  it('AC1: scanning shows the video and the guide box', async () => {
    await openScanner();
    expect(screen.getByTestId('scanner-video')).toBeTruthy();
    expect(screen.getByTestId('guide-box')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Torch' })).toBeNull();
  });

  it('AC2: found shows name, brand and image, and Add puts the name on the list and closes', async () => {
    const api = new FakeApiClient();
    api.getProductResult = { ok: true, value: nutella };
    const { sync } = await openScanner(api);
    await waitFor(() => expect(device.emit).toBeTruthy());
    device.emit('3017620422003');
    expect(await screen.findByRole('heading', { name: 'Nutella' })).toBeTruthy();
    expect(screen.getByText('Ferrero')).toBeTruthy();
    expect(screen.getByRole('img', { name: 'Nutella' }).getAttribute('src')).toBe('https://img.test/n.jpg');
    expect(device.stops).toBe(1);
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Add' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(await within(await screen.findByRole('list', { name: 'Items' })).findByText('Nutella')).toBeTruthy();
    const { items, out } = await stored();
    expect(items[0]).toMatchObject({ name: 'Nutella', barcode: '3017620422003' });
    expect(out).toHaveLength(1);
    expect(sync.kicks).toBe(1);
  });

  it('a found product without an image shows no image', async () => {
    const api = new FakeApiClient();
    api.getProductResult = { ok: true, value: { ...nutella, image_url: null, brand: null } };
    await openScanner(api);
    await waitFor(() => device.emit('3017620422003'));
    await screen.findByRole('heading', { name: 'Nutella' });
    expect(screen.queryByRole('img')).toBeNull();
  });

  it('Scan again returns to scanning', async () => {
    const api = new FakeApiClient();
    api.getProductResult = { ok: true, value: nutella };
    await openScanner(api);
    device.emit('3017620422003');
    await screen.findByRole('heading', { name: 'Nutella' });
    fireEvent.click(screen.getByRole('button', { name: 'Scan again' }));
    expect(await screen.findByTestId('guide-box')).toBeTruthy();
    expect((await stored()).items).toHaveLength(0);
  });

  it('AC3: not-found shows a name field; typing a name adds it with no barcode and remembers it', async () => {
    await openScanner();
    device.emit('12345678');
    expect(await screen.findByText("We don't know this product")).toBeTruthy();
    const field = screen.getByRole('textbox', { name: 'Name' });
    await waitFor(() => expect(document.activeElement).toBe(field));
    fireEvent.change(field, { target: { value: 'Oat bar' } });
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Add' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect((await stored()).items[0]).toMatchObject({ name: 'Oat bar', barcode: null });
    expect(JSON.parse(localStorage.getItem('abodenestly.barcodeNames')!)).toEqual([{ code: '12345678', name: 'Oat bar' }]);
  });

  it('not-found prefills a name typed before, and Add is off while blank', async () => {
    localStorage.setItem('abodenestly.barcodeNames', JSON.stringify([{ code: '12345678', name: 'Oat bar' }]));
    await openScanner();
    device.emit('12345678');
    const field = (await screen.findByRole('textbox', { name: 'Name' })) as HTMLInputElement;
    expect(field.value).toBe('Oat bar');
    fireEvent.change(field, { target: { value: ' ' } });
    expect((within(screen.getByRole('dialog')).getByRole('button', { name: 'Add' }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('AC4: offline adds the code as the name, queues the lookup and says so', async () => {
    Object.defineProperty(navigator, 'onLine', { value: false, configurable: true });
    const api = new FakeApiClient();
    await openScanner(api);
    device.emit('4006381333931');
    expect(await screen.findByText("Added 4006381333931. We'll look it up when you're back online")).toBeTruthy();
    expect(api.getProductCalls).toEqual([]);
    expect(await within(await screen.findByRole('list', { name: 'Items' })).findByText('4006381333931')).toBeTruthy();
    const { items, out } = await stored();
    expect(items[0]).toMatchObject({ name: '4006381333931', barcode: null });
    expect(out).toHaveLength(1);
    expect(JSON.parse(localStorage.getItem('abodenestly.barcodeLookups')!)).toHaveLength(1);
  });

  it('a network failure online is treated like offline', async () => {
    const api = new FakeApiClient();
    api.getProductResult = { ok: false, error: { code: 'network' } };
    await openScanner(api);
    device.emit('4006381333931');
    expect(await screen.findByText(/We'll look it up when you're back online/)).toBeTruthy();
  });

  it('an unexpected lookup failure adds nothing, offers Retry and manual name entry', async () => {
    const api = new FakeApiClient();
    api.getProductResult = { ok: false, error: { code: 'unknown' } };
    await openScanner(api);
    device.emit('4006381333931');
    expect(await screen.findByText("Couldn't look that up. Try again.")).toBeTruthy();
    expect((await stored()).items).toHaveLength(0);
    expect(localStorage.getItem('abodenestly.barcodeLookups')).toBeNull();
    api.getProductResult = { ok: true, value: nutella };
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByRole('heading', { name: 'Nutella' })).toBeTruthy();
  });

  it('Retry clicked twice during a slow lookup that resolves offline leaves one item', async () => {
    const api = new FakeApiClient();
    api.getProductResult = { ok: false, error: { code: 'unknown' } };
    await openScanner(api);
    device.emit('4006381333931');
    const retry = await screen.findByRole('button', { name: 'Retry' });
    api.getProductResult = { ok: false, error: { code: 'network' } };
    api.pending = true;
    act(() => {
      fireEvent.click(retry);
      fireEvent.click(retry);
    });
    await waitFor(() => expect(api.getProductCalls).toHaveLength(2));
    api.pending = false;
    act(() => api.resolvePending());
    await screen.findByText(/We'll look it up when you're back online/);
    expect(api.getProductCalls).toHaveLength(2);
    expect((await stored()).items).toHaveLength(1);
  });

  it.each(['error', 'not-found'] as const)('a name typed on the error card survives a retry that ends in %s', async (next) => {
    const api = new FakeApiClient();
    api.getProductResult = { ok: false, error: { code: 'unknown' } };
    await openScanner(api);
    device.emit('4006381333931');
    fireEvent.change(await screen.findByRole('textbox', { name: 'Name' }), { target: { value: 'Pasta' } });
    api.getProductResult = next === 'error' ? { ok: false, error: { code: 'unknown' } } : { ok: false, error: { code: 'not_found' } };
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    await screen.findByText(next === 'error' ? "Couldn't look that up. Try again." : "We don't know this product");
    expect((screen.getByRole('textbox', { name: 'Name' }) as HTMLInputElement).value).toBe('Pasta');
  });

  it('the failed-lookup state still lets a name be typed', async () => {
    const api = new FakeApiClient();
    api.getProductResult = { ok: false, error: { code: 'unknown' } };
    await openScanner(api);
    device.emit('4006381333931');
    fireEvent.change(await screen.findByRole('textbox', { name: 'Name' }), { target: { value: 'Pasta' } });
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Add' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect((await stored()).items[0]).toMatchObject({ name: 'Pasta', barcode: null });
  });

  it.each(['NotFoundError', 'NotReadableError', 'OverconstrainedError'])('%s shows camera-unavailable with manual entry', async (name) => {
    device.failWith = name;
    await openScanner();
    expect(await screen.findByText("Can't use the camera")).toBeTruthy();
    expect(screen.getByText(/Another app may be using it/)).toBeTruthy();
    expect(screen.getByRole('textbox', { name: 'Barcode' })).toBeTruthy();
  });

  it('NotSupportedError shows the unsupported layout', async () => {
    device.failWith = 'NotSupportedError';
    await openScanner();
    expect(await screen.findByText("Scanning isn't available in this browser")).toBeTruthy();
  });

  it('AC5: permission-denied explains and manual entry still works', async () => {
    device.failWith = 'NotAllowedError';
    const api = new FakeApiClient();
    api.getProductResult = { ok: true, value: nutella };
    await openScanner(api);
    expect(await screen.findByText('Camera access is off')).toBeTruthy();
    const field = screen.getByRole('textbox', { name: 'Barcode' });
    fireEvent.change(field, { target: { value: '3017620422003' } });
    fireEvent.click(screen.getByRole('button', { name: 'Look up' }));
    expect(await screen.findByRole('heading', { name: 'Nutella' })).toBeTruthy();
    expect(api.getProductCalls).toEqual(['3017620422003']);
  });

  it('manual entry rejects a bad code with the error', async () => {
    device.failWith = 'NotAllowedError';
    const api = new FakeApiClient();
    await openScanner(api);
    fireEvent.change(await screen.findByRole('textbox', { name: 'Barcode' }), { target: { value: '123' } });
    fireEvent.click(screen.getByRole('button', { name: 'Look up' }));
    expect(await screen.findByText('Enter 8 to 14 digits')).toBeTruthy();
    expect(api.getProductCalls).toEqual([]);
  });

  it('"Enter code manually" reveals the field while scanning', async () => {
    const api = new FakeApiClient();
    api.getProductResult = { ok: true, value: nutella };
    await openScanner(api);
    expect(screen.queryByRole('textbox', { name: 'Barcode' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Enter code manually' }));
    const field = screen.getByRole('textbox', { name: 'Barcode' });
    await waitFor(() => expect(document.activeElement).toBe(field));
    fireEvent.change(field, { target: { value: '3017620422003' } });
    fireEvent.click(screen.getByRole('button', { name: 'Look up' }));
    expect(await screen.findByRole('heading', { name: 'Nutella' })).toBeTruthy();
  });

  it('the torch toggle appears only when supported and applies the constraint', async () => {
    device.torch = true;
    await openScanner();
    const torch = await screen.findByRole('button', { name: 'Torch' });
    expect(torch.getAttribute('aria-pressed')).toBe('false');
    fireEvent.click(torch);
    await waitFor(() => expect(torch.getAttribute('aria-pressed')).toBe('true'));
    fireEvent.click(torch);
    await waitFor(() => expect(torch.getAttribute('aria-pressed')).toBe('false'));
    expect(device.torchCalls).toEqual([true, false]);
  });

  it('a browser without a detector shows the unsupported layout with the field focused', async () => {
    device.isSupported = false;
    await openScanner();
    expect(screen.getByText("Scanning isn't available in this browser")).toBeTruthy();
    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('textbox', { name: 'Barcode' })));
  });

  it('Escape closes and stops the camera', async () => {
    await openScanner();
    await waitFor(() => expect(screen.getByTestId('scanner-video')).toBeTruthy());
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' });
    expect(screen.queryByRole('dialog')).toBeNull();
    await waitFor(() => expect(device.stops).toBe(1));
  });

  it('Tab wraps inside the dialog', async () => {
    await openScanner();
    const dialog = screen.getByRole('dialog');
    const buttons = within(dialog).getAllByRole('button');
    const last = buttons[buttons.length - 1]!;
    last.focus();
    fireEvent.keyDown(dialog, { key: 'Tab' });
    expect(document.activeElement).toBe(buttons[0]);
  });

  it('a read that is not 8 to 14 digits is ignored', async () => {
    const api = new FakeApiClient();
    await openScanner(api);
    device.emit('123');
    expect(api.getProductCalls).toEqual([]);
    expect(screen.getByTestId('guide-box')).toBeTruthy();
  });

  it('queued lookups resolve when the list opens', async () => {
    const api = new FakeApiClient();
    api.getProductResult = { ok: true, value: nutella };
    await seed([list('a', 'Groceries')], [item('i1', 'a', '3017620422003')]);
    localStorage.setItem('abodenestly.barcodeLookups', JSON.stringify([{ householdId: 'h1', itemId: 'i1', code: '3017620422003' }]));
    const { sync } = await renderDetail(api);
    expect(await screen.findByText('Nutella')).toBeTruthy();
    const { items, out } = await stored();
    expect(items[0]).toMatchObject({ name: 'Nutella', barcode: '3017620422003' });
    expect(out).toHaveLength(1);
    expect(sync.kicks).toBe(1);
  });
});

describe('SHOP-10: initial focus', () => {
  it('the manual field keeps focus after Sheet takes it for its panel (real effect ordering, no act)', async () => {
    const flag = globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean };
    const before = flag.IS_REACT_ACT_ENVIRONMENT;
    flag.IS_REACT_ACT_ENVIRONMENT = false; // effects then flush on the scheduler, after microtasks queued during commit
    const unsupported = new FakeDevice();
    unsupported.isSupported = false;
    const db = await openHouseholdDb('h1');
    const host = document.createElement('div');
    document.body.appendChild(host);
    const root = createRoot(host);
    root.render(
      <ApiProvider client={new FakeApiClient()}>
        <BarcodeScanner db={db} deps={{}} householdId="h1" listId="a" userId="u1" onClose={() => undefined} device={unsupported} />
      </ApiProvider>,
    );
    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('textbox', { name: 'Barcode' })));
    root.unmount();
    host.remove();
    await db.close();
    flag.IS_REACT_ACT_ENVIRONMENT = before;
  });
});
