import type { Household, ListItem, Profile, ShoppingList } from '@abodenestly/contract';
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
import { FakeSync } from '../sync/FakeSync.ts';
import { openHouseholdDb } from '../sync/db.ts';
import { SyncProvider } from '../sync/SyncEngine.ts';
import { ListDetail } from './ListDetail.tsx';
import * as recognizerModule from './VoiceRecognizer.ts';
import type { VoiceError, VoiceHandlers, VoiceRecognizer } from './VoiceRecognizer.ts';

const house = (over: Partial<Household> = {}) =>
  ({ id: 'h1', name: 'Home One', currency: 'EUR', module_shopping: true, module_pantry: true, module_maintenance: true, ...over }) as Household;
const list = (id: string, name: string, over: Partial<ShoppingList> = {}) =>
  ({ id, household_id: 'h1', name, store_id: null, is_template: false, archived_at: null, deleted_at: null, created_at: '2026-01-01T00:00:00Z', ...over }) as ShoppingList;

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



async function stored() {
  const db = await openHouseholdDb('h1');
  const [items, out] = [await db.list_item.toArray(), await db.outbox.toArray()];
  await db.close();
  return { items, out };
}

/** A hand-written recognizer: tests call `say` and `fail` to play the browser. */
class FakeRecognizer implements VoiceRecognizer {
  isSupported = true;
  local = false;
  state: 'granted' | 'denied' | 'prompt' = 'prompt';
  starts: { offline: boolean }[] = [];
  stops = 0;
  private handlers: VoiceHandlers | null = null;
  supported = () => this.isSupported;
  canProcessLocally = () => this.local;
  permission = async () => this.state;
  start(handlers: VoiceHandlers, opts: { offline: boolean }) {
    this.handlers = handlers;
    this.starts.push(opts);
    return () => void (this.stops += 1);
  }
  say(text: string, final = true) {
    act(() => this.handlers?.onTranscript(text, final));
  }
  fail(error: VoiceError) {
    act(() => this.handlers?.onError(error));
  }
}

let rec: FakeRecognizer;

beforeEach(async () => {
  localStorage.clear();
  await Dexie.delete('abodenestly-h1');
  rec = new FakeRecognizer();
  vi.spyOn(recognizerModule, 'browserVoiceRecognizer', 'get').mockReturnValue(rec);
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  Object.defineProperty(navigator, 'onLine', { value: true, configurable: true });
});

async function openVoice() {
  await seed([list('a', 'Groceries')]);
  const r = await renderDetail();
  fireEvent.click(await screen.findByRole('button', { name: 'Voice add' }));
  await screen.findByRole('dialog', { name: 'Voice add' });
  return r;
}
const dialog = () => within(screen.getByRole('dialog'));

describe('SHOP-14: voice add', () => {
  it('the QuickAddBar has a Voice add button next to Scan barcode', async () => {
    await seed([list('a', 'Groceries')]);
    await renderDetail();
    expect(await screen.findByRole('button', { name: 'Voice add' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Scan barcode' })).toBeTruthy();
  });

  it('listening: shows the heading, the indicator and the interim transcript in a live region', async () => {
    await openVoice();
    await waitFor(() => expect(rec.starts).toHaveLength(1));
    expect(dialog().getByRole('heading', { name: 'Listening…' })).toBeTruthy();
    expect(screen.getByTestId('listening-indicator')).toBeTruthy();
    rec.say('2 litr', false);
    const live = dialog().getByText('2 litr');
    expect(live.getAttribute('aria-live')).toBe('polite');
    expect(dialog().queryByRole('button', { name: 'Add' })).toBeNull();
  });

  it('Cancel closes the sheet and stops the recognizer', async () => {
    await openVoice();
    await waitFor(() => expect(rec.starts).toHaveLength(1));
    fireEvent.click(dialog().getByRole('button', { name: 'Cancel' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(rec.stops).toBe(1);
    expect((await stored()).items).toHaveLength(0);
  });

  it('AC1: "2 litres milk" shows chips for quantity 2, unit l and name Milk, and adds nothing yet', async () => {
    await openVoice();
    await waitFor(() => expect(rec.starts).toHaveLength(1));
    rec.say('2 litres milk');
    const chips = within(await dialog().findByRole('list', { name: 'Parsed item' }));
    expect(chips.getAllByRole('listitem').map((li) => li.textContent)).toEqual(['2', 'l', 'Milk']);
    expect((await stored()).items).toHaveLength(0);
  });

  it('a name without a number shows only the name chip', async () => {
    await openVoice();
    await waitFor(() => expect(rec.starts).toHaveLength(1));
    rec.say('milk');
    const chips = within(await dialog().findByRole('list', { name: 'Parsed item' }));
    expect(chips.getAllByRole('listitem').map((li) => li.textContent)).toEqual(['Milk']);
  });

  it('AC2: Add writes one list_item upsert with quantity and unit, closes the sheet and kicks sync', async () => {
    const { sync } = await openVoice();
    await waitFor(() => expect(rec.starts).toHaveLength(1));
    rec.say('2 litres of milk');
    fireEvent.click(await dialog().findByRole('button', { name: 'Add' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(await within(await screen.findByRole('list', { name: 'Items' })).findByText('Milk')).toBeTruthy();
    const { items, out } = await stored();
    expect(items).toHaveLength(1);
    expect(items[0]).toMatchObject({ name: 'Milk', quantity: 2, unit: 'l', checked: false });
    expect(out).toHaveLength(1);
    expect(out[0]).toMatchObject({ table: 'list_item', op: 'upsert' });
    expect(sync.kicks).toBe(1);
    expect(rec.stops).toBe(1);
  });

  it('AC2 offline: Add still queues the row', async () => {
    Object.defineProperty(navigator, 'onLine', { value: false, configurable: true });
    rec.local = true;
    await openVoice();
    await waitFor(() => expect(rec.starts).toEqual([{ offline: true }]));
    rec.say('3 apples');
    fireEvent.click(await dialog().findByRole('button', { name: 'Add' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    const { items, out } = await stored();
    expect(items[0]).toMatchObject({ name: 'Apples', quantity: 3, unit: null });
    expect(out).toHaveLength(1);
  });

  it('AC3: Try again clears the chips and restarts the recognizer', async () => {
    await openVoice();
    await waitFor(() => expect(rec.starts).toHaveLength(1));
    rec.say('2 litres milk');
    fireEvent.click(await dialog().findByRole('button', { name: 'Try again' }));
    expect(await dialog().findByRole('heading', { name: 'Listening…' })).toBeTruthy();
    expect(dialog().queryByRole('list', { name: 'Parsed item' })).toBeNull();
    expect(dialog().queryByText('2 litres milk')).toBeNull();
    await waitFor(() => expect(rec.starts).toHaveLength(2));
    expect(rec.stops).toBe(1);
    rec.say('bread');
    expect(await dialog().findByText('Bread')).toBeTruthy();
  });

  it('a transcript with no item says so and offers only Try again', async () => {
    await openVoice();
    await waitFor(() => expect(rec.starts).toHaveLength(1));
    rec.say('2 litres');
    expect(await dialog().findByText("Didn't catch an item. Try again.")).toBeTruthy();
    expect(dialog().queryByRole('button', { name: 'Add' })).toBeNull();
    fireEvent.click(dialog().getByRole('button', { name: 'Try again' }));
    await waitFor(() => expect(rec.starts).toHaveLength(2));
  });

  it.each<VoiceError>(['no-speech', 'other'])('a %s error says "Didn\'t catch that." with Try again', async (error) => {
    await openVoice();
    await waitFor(() => expect(rec.starts).toHaveLength(1));
    rec.fail(error);
    expect(await dialog().findByText("Didn't catch that.")).toBeTruthy();
    fireEvent.click(dialog().getByRole('button', { name: 'Try again' }));
    await waitFor(() => expect(rec.starts).toHaveLength(2));
  });

  it('AC4: a not-allowed error shows permission-denied', async () => {
    await openVoice();
    await waitFor(() => expect(rec.starts).toHaveLength(1));
    rec.fail('not-allowed');
    expect(await dialog().findByText(/Microphone access is off\. Allow it in your browser settings, or type the item instead\./)).toBeTruthy();
    expect(dialog().getByRole('button', { name: 'Type instead' })).toBeTruthy();
  });

  it('AC4: a permission already denied on open shows permission-denied without listening', async () => {
    rec.state = 'denied';
    await openVoice();
    expect(await dialog().findByText(/Microphone access is off/)).toBeTruthy();
    expect(rec.starts).toHaveLength(0);
  });

  it('AC5: no recognition API shows unsupported, and Type instead closes the sheet and focuses Quick add', async () => {
    rec.isSupported = false;
    await openVoice();
    expect(await dialog().findByText("Voice input isn't available here. Type the item instead.")).toBeTruthy();
    expect(rec.starts).toHaveLength(0);
    fireEvent.click(dialog().getByRole('button', { name: 'Type instead' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    const field = screen.getByRole('combobox', { name: 'Add an item' });
    await waitFor(() => expect(document.activeElement).toBe(field));
  });

  it('offline with no local recognition shows unsupported', async () => {
    Object.defineProperty(navigator, 'onLine', { value: false, configurable: true });
    rec.local = false;
    await openVoice();
    expect(await dialog().findByText("Voice input isn't available here. Type the item instead.")).toBeTruthy();
    expect(rec.starts).toHaveLength(0);
  });

  it('closing the sheet with Escape stops the recognizer', async () => {
    await openVoice();
    await waitFor(() => expect(rec.starts).toHaveLength(1));
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' });
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(rec.stops).toBe(1);
  });
});
