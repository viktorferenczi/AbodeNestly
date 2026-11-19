import type { Household, Profile } from '@abodenestly/contract';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { Shell } from '../app/Shell.tsx';
import { FakeSync } from '../sync/FakeSync.ts';
import { openHouseholdDb } from '../sync/db.ts';
import { SyncProvider } from '../sync/SyncEngine.ts';
import { ApiProvider } from './api/ApiProvider.tsx';
import { FakeApiClient } from './api/FakeApiClient.ts';
import { AuthProvider } from './auth/AuthProvider.tsx';
import { FakeAuthClient } from './auth/FakeAuthClient.ts';
import { writeLastHousehold } from './auth/lastHousehold.ts';
import { RecentlyDeleted } from './RecentlyDeleted.tsx';

const DAY = 86_400_000;
const ago = (ms: number) => new Date(Date.now() - ms - 3_600_000).toISOString();
const house = (over: Partial<Household> = {}) =>
  ({ id: 'h1', name: 'Home One', currency: 'EUR', module_shopping: true, module_pantry: true, module_maintenance: true, ...over }) as Household;

async function seed(rows: { store?: [string, number][]; pantry?: [string, number][] } = {}) {
  const db = await openHouseholdDb('h1');
  for (const [name, d] of rows.store ?? []) await db.store.put({ id: `s-${name}`, household_id: 'h1', name, deleted_at: ago(d * DAY) });
  for (const [name, d] of rows.pantry ?? []) await db.pantry_item.put({ id: `p-${name}`, household_id: 'h1', name, deleted_at: ago(d * DAY) });
  await db.close();
}

async function renderDeleted(household = house()) {
  const api = new FakeApiClient();
  api.fetchHousehold = async () => ({ ok: true, value: { household, role: 'owner' } });
  api.fetchMyHouseholdsResult = { ok: true, value: [{ household, role: 'owner' }] };
  writeLastHousehold('h1');
  const auth = new FakeAuthClient({ user: { id: 'u1' } });
  auth.profile = { id: 'u1', display_name: 'Ada' } as Profile;
  const sync = new FakeSync();
  const router = createMemoryRouter([{ element: <Shell />, children: [{ path: '/deleted', element: <SyncProvider engine={sync}><RecentlyDeleted /></SyncProvider> }] }], {
    initialEntries: ['/deleted'],
  });
  render(
    <AuthProvider client={auth}>
      <ApiProvider client={api}>
        <RouterProvider router={router} />
      </ApiProvider>
    </AuthProvider>,
  );
  return { sync };
}

beforeEach(async () => {
  localStorage.clear();
  const { default: Dexie } = await import('dexie');
  await Dexie.delete('abodenestly-h1');
});
afterEach(cleanup);

describe('CORE-10: Recently deleted screen', () => {
  it('AC1 empty state', async () => {
    await seed();
    await renderDeleted();
    const empty = await screen.findByTestId('deleted.empty');
    expect(empty.textContent).toBe('Nothing deleted in the last 30 days.');
  });

  it('AC2 row shows days left', async () => {
    await seed({ store: [['Lidl', 2]] });
    await renderDeleted();
    const row = await screen.findByTestId('deleted.row');
    expect(within(row).getByText('Lidl')).toBeTruthy();
    expect(within(row).getByText('28 days left')).toBeTruthy();
  });

  it('Restore removes the row, kicks sync and leaves one outbox row', async () => {
    await seed({ store: [['Lidl', 2]] });
    const { sync } = await renderDeleted();
    fireEvent.click(await screen.findByTestId('deleted.restore'));
    await waitFor(() => expect(screen.getByTestId('deleted.empty')).toBeTruthy());
    await waitFor(() => expect(sync.kicks).toBe(1));
    const db = await openHouseholdDb('h1');
    const out = await db.outbox.toArray();
    expect(out).toHaveLength(1);
    expect(out[0]!.payload).toMatchObject({ table: 'store', op: 'upsert', row_id: 's-Lidl', data: { deleted_at: null } });
    await db.close();
  });

  it('AC4 a 31-day-old row is not listed', async () => {
    await seed({ store: [['Old', 31]] });
    await renderDeleted();
    await screen.findByTestId('deleted.empty');
    expect(screen.queryByTestId('deleted.row')).toBeNull();
  });

  it('AC5 pantry off hides its segment; Shopping still lists and restores', async () => {
    await seed({ store: [['Lidl', 1]], pantry: [['Rice', 1]] });
    await renderDeleted(house({ module_pantry: false }));
    await screen.findByTestId('deleted.row');
    expect(screen.getByTestId('deleted.segment.shopping')).toBeTruthy();
    expect(screen.queryByTestId('deleted.segment.pantry')).toBeNull();
    expect(screen.getByTestId('deleted.segment.maintenance')).toBeTruthy();
    fireEvent.click(screen.getByTestId('deleted.restore'));
    await waitFor(() => expect(screen.getByTestId('deleted.empty')).toBeTruthy());
  });

  it('segments switch modules', async () => {
    await seed({ pantry: [['Rice', 1]] });
    await renderDeleted();
    fireEvent.click(await screen.findByTestId('deleted.segment.pantry'));
    expect(within(await screen.findByTestId('deleted.row')).getByText('Rice')).toBeTruthy();
  });
});
