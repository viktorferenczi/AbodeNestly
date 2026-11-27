import type { Household, ListItem, Profile, ShoppingList } from '@abodenestly/contract';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
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
import { useHousehold } from '../core/household/HouseholdProvider.tsx';
import { RealtimeProvider } from '../core/realtime/RealtimeProvider.tsx';
import { openHouseholdDb } from '../sync/db.ts';
import { ListDetail } from './ListDetail.tsx';
import { REALTIME_TABLES, type RealtimeChannelLike, type RealtimeClientLike } from '../sync/realtime.ts';

const house = (id: string, over: Partial<Household> = {}) =>
  ({ id, name: `Home ${id}`, currency: 'EUR', module_shopping: true, module_pantry: true, module_maintenance: true, ...over }) as Household;
const list = (id: string, over: Partial<ShoppingList> = {}) =>
  ({ id, household_id: 'h1', name: 'Groceries', store_id: null, is_template: false, archived_at: null, deleted_at: null, created_at: '2026-01-01T00:00:00Z', ...over }) as ShoppingList;
const item = (id: string, name: string, over: Partial<ListItem> = {}) =>
  ({ id, household_id: 'h1', list_id: 'a', name, name_key: name.toLowerCase(), checked: false, deleted_at: null, created_at: '2026-01-01T00:00:01Z', ...over }) as ListItem;

class FakeRealtime implements RealtimeClientLike {
  channels: string[] = [];
  tables: string[] = [];
  removed = 0;
  private fire: Array<() => void> = [];
  private status: Array<(s: string) => void> = [];
  channel(name: string): RealtimeChannelLike {
    this.channels.push(name);
    const chan: RealtimeChannelLike = {
      on: (_t, f, callback) => {
        this.tables.push(f.table);
        this.fire.push(callback);
        return chan;
      },
      subscribe: (cb) => {
        if (cb) this.status.push(cb);
        return chan;
      },
    };
    return chan;
  }
  removeChannel() {
    this.removed += 1;
  }
  emit() {
    this.fire.forEach((f) => f());
  }
  setStatus(s: string) {
    this.status.forEach((f) => f(s));
  }
}

async function seed(householdId = 'h1') {
  const db = await openHouseholdDb(householdId);
  await db.shopping_list.bulkPut([list('a', { household_id: householdId })]);
  await db.list_item.bulkPut([item('1', 'Milk', { household_id: householdId })]);
  await db.close();
}

function Switch() {
  const { switchTo } = useHousehold();
  return <button onClick={() => void switchTo('h2')}>go h2</button>;
}

async function renderIt({ shopping = true, path = shopping ? '/shopping/a' : '/' } = {}) {
  const households = [house('h1', { module_shopping: shopping }), house('h2')];
  const api = new FakeApiClient();
  api.fetchHousehold = async (id: string) => ({ ok: true, value: { household: households.find((h) => h.id === id)!, role: 'owner' } });
  api.fetchMyHouseholdsResult = { ok: true, value: households.map((household) => ({ household, role: 'owner' as const })) };
  writeLastHousehold('h1');
  const auth = new FakeAuthClient({ user: { id: 'u1' } });
  auth.profile = { id: 'u1', display_name: 'Ada' } as Profile;
  const realtime = new FakeRealtime();
  const router = createMemoryRouter(
    [
      {
        element: <Shell />,
        children: [
          { path: '/', element: <Switch /> },
          { element: <RequireModule module="shopping" />, children: [{ path: '/shopping/:listId', element: <ListDetail /> }] },
        ],
      },
    ],
    { initialEntries: [path] },
  );
  render(
    <AuthProvider client={auth}>
      <ApiProvider client={api}>
        <RealtimeProvider client={realtime}>
          <RouterProvider router={router} />
        </RealtimeProvider>
      </ApiProvider>
    </AuthProvider>,
  );
  return { api, realtime };
}

beforeEach(async () => {
  localStorage.clear();
  await Dexie.delete('abodenestly-h1');
  await Dexie.delete('abodenestly-h2');
});
afterEach(() => {
  cleanup();
  Object.defineProperty(navigator, 'onLine', { value: true, configurable: true });
});

describe('SHOP-09: realtime in the shell', () => {
  it('AC2: an event triggers a pull and the remote check-off appears without a remount', async () => {
    await seed();
    const { api, realtime } = await renderIt();
    expect(await screen.findByRole('checkbox', { name: 'Check off Milk' })).toBeTruthy();
    await waitFor(() => expect(realtime.channels).toContain('household:h1'));
    await waitFor(() => expect(api.syncPullCalls.length).toBeGreaterThan(0));
    await new Promise((r) => setTimeout(r, 50));
    const before = api.syncPullCalls.length;
    api.syncPullResults = [
      { ok: true, value: { cursor: 5, has_more: false, changes: { list_item: [item('1', 'Milk', { checked: true, checked_at: '2026-01-01T00:00:09Z' } as Partial<ListItem>)] } } },
    ];
    realtime.emit();
    await waitFor(() => expect(api.syncPullCalls.length).toBeGreaterThan(before));
    expect(await screen.findByText('Checked (1)')).toBeTruthy();
    expect((screen.getByRole('checkbox', { name: 'Uncheck Milk' }) as HTMLInputElement).checked).toBe(true);
  });

  it('AC3: offline with a failing channel still shows the local rows and no error', async () => {
    Object.defineProperty(navigator, 'onLine', { value: false, configurable: true });
    await seed();
    const { realtime } = await renderIt();
    expect(await screen.findByRole('checkbox', { name: 'Check off Milk' })).toBeTruthy();
    await waitFor(() => expect(realtime.channels.length).toBeGreaterThan(0));
    realtime.setStatus('CHANNEL_ERROR');
    realtime.setStatus('TIMED_OUT');
    realtime.emit();
    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.getByRole('checkbox', { name: 'Check off Milk' })).toBeTruthy();
  });

  it('switching household unsubscribes and subscribes to the new one', async () => {
    const { realtime } = await renderIt({ path: '/' });
    await waitFor(() => expect(realtime.channels).toContain('household:h1'));
    fireEvent.click(await screen.findByRole('button', { name: 'go h2' }));
    await waitFor(() => expect(realtime.channels.at(-1)).toBe('household:h2'));
    expect(new Set(realtime.channels)).toEqual(new Set(['household:h1', 'household:h2']));
    expect(realtime.removed).toBeGreaterThanOrEqual(1);
  });

  it('AC4: shopping off subscribes to no shopping table, and the household channel stays for core tables', async () => {
    const { realtime } = await renderIt({ shopping: false });
    await screen.findByRole('button', { name: 'go h2' });
    await waitFor(() => expect(realtime.channels).toContain('household:h1'));
    for (const t of REALTIME_TABLES.shopping) expect(realtime.tables).not.toContain(t);
    expect(realtime.tables).toContain('invite');
  });
});
