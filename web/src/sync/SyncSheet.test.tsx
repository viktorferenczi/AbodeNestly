import type { Household, Profile } from '@abodenestly/contract';
import Dexie from 'dexie';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { Shell } from '../app/Shell.tsx';
import { ApiProvider } from '../core/api/ApiProvider.tsx';
import { FakeApiClient } from '../core/api/FakeApiClient.ts';
import { AuthProvider } from '../core/auth/AuthProvider.tsx';
import { FakeAuthClient } from '../core/auth/FakeAuthClient.ts';
import { writeLastHousehold } from '../core/auth/lastHousehold.ts';
import { HouseholdProvider } from '../core/household/HouseholdProvider.tsx';
import { openHouseholdDb } from './db.ts';
import { FakeSync } from './FakeSync.ts';
import { SyncProvider } from './SyncEngine.ts';
import { SyncStatusChip } from './SyncStatusChip.tsx';

const house = () => ({ id: 'h1', name: 'Home', currency: 'EUR', module_shopping: false, module_pantry: false, module_maintenance: false }) as Household;
const envelope = (id: string) => ({ id, table: 'list_item', op: 'upsert', row_id: `r-${id}`, data: {}, client_ts: 'x' });

beforeEach(async () => {
  localStorage.clear();
  await Dexie.delete('abodenestly-h1');
});
afterEach(() => {
  cleanup();
  Object.defineProperty(navigator, 'onLine', { value: true, configurable: true });
});

async function seed(pending: number, failed: Array<{ table: string; op: string; reason: 'validation' | 'rls_denied' }> = []) {
  const db = await openHouseholdDb('h1');
  for (let i = 0; i < pending; i++) await db.outbox.add({ table: 'list_item', op: 'upsert', payload: envelope(`p${i}`) });
  for (const [i, f] of failed.entries()) {
    await db.outbox_failed.put({ seq: 100 + i, ...f, payload: envelope(`f${i}`), detail: 'x', failed_at: 'now' });
  }
  db.close();
}

function renderChip(sync: FakeSync) {
  const api = new FakeApiClient();
  api.fetchHousehold = async () => ({ ok: true, value: { household: house(), role: 'owner' } });
  api.fetchMyHouseholdsResult = { ok: true, value: [{ household: house(), role: 'owner' }] };
  writeLastHousehold('h1');
  const auth = new FakeAuthClient({ user: { id: 'u1' } });
  auth.profile = { id: 'u1', display_name: 'Ada' } as Profile;
  const router = createMemoryRouter([
    {
      element: (
        <HouseholdProvider>
          <SyncProvider engine={sync}>
            <SyncStatusChip />
          </SyncProvider>
        </HouseholdProvider>
      ),
      path: '/',
    },
  ]);
  render(
    <AuthProvider client={auth}>
      <ApiProvider client={api}>
        <RouterProvider router={router} />
      </ApiProvider>
    </AuthProvider>,
  );
}

const chip = () => screen.queryByRole('button', { name: /sync|offline/i });

describe('SHOP-08: SyncStatusChip', () => {
  it('is hidden when idle', async () => {
    await seed(0);
    renderChip(new FakeSync());
    await act(async () => {
      await new Promise((r) => setTimeout(r, 50));
    });
    expect(chip()).toBeNull();
  });

  it('AC4: shows "3 to sync" for three queued changes', async () => {
    await seed(3);
    renderChip(new FakeSync());
    expect((await screen.findByRole('button', { name: /3 to sync/ })).textContent).toContain('3 to sync');
  });

  it('shows Syncing while the engine syncs, then Offline, then hides', async () => {
    await seed(0);
    const sync = new FakeSync();
    renderChip(sync);
    await act(async () => sync.setPhase('syncing'));
    expect(await screen.findByRole('button', { name: /Syncing/ })).toBeTruthy();
    await act(async () => sync.setPhase('offline'));
    expect(await screen.findByRole('button', { name: /Offline/ })).toBeTruthy();
    await act(async () => sync.setPhase('idle'));
    await waitFor(() => expect(chip()).toBeNull());
  });

  it('shows Offline when the browser is offline', async () => {
    Object.defineProperty(navigator, 'onLine', { value: false, configurable: true });
    await seed(0);
    renderChip(new FakeSync());
    expect(await screen.findByRole('button', { name: /Offline/ })).toBeTruthy();
  });
});

describe('SHOP-08: SyncSheet (DESIGN B12)', () => {
  async function open(sync = new FakeSync()) {
    renderChip(sync);
    fireEvent.click(await screen.findByRole('button', { name: /to sync|Offline|Syncing/ }));
    return { sync, dialog: await screen.findByRole('dialog', { name: 'Sync' }) };
  }

  it('state pending: counts the queued changes and lists no failures', async () => {
    await seed(2);
    const { dialog } = await open();
    expect(await within(dialog).findByText('Pending changes: 2')).toBeTruthy();
    expect(within(dialog).getByText('Failed changes: 0')).toBeTruthy();
    expect(within(dialog).queryByRole('button', { name: 'Retry' })).toBeNull();
  });

  it('AC3, state failed: lists the failed change with Retry and Discard', async () => {
    await seed(0, [{ table: 'list_item', op: 'upsert', reason: 'validation' }]);
    const { dialog } = await open();
    expect(await within(dialog).findByText('Failed changes: 1')).toBeTruthy();
    expect(within(dialog).getByText(/list_item · save · Couldn’t save: invalid/)).toBeTruthy();
    expect(within(dialog).getByRole('button', { name: 'Retry' })).toBeTruthy();
    expect(within(dialog).getByRole('button', { name: 'Discard' })).toBeTruthy();
  });

  it('state idle: says everything is synced', async () => {
    await seed(0);
    const sync = new FakeSync();
    renderChip(sync);
    await act(async () => sync.setPhase('syncing'));
    fireEvent.click(await screen.findByRole('button', { name: /Syncing/ }));
    await act(async () => sync.setPhase('idle'));
    expect(await screen.findByText('Everything is synced.')).toBeTruthy();
  });

  it('Retry and Discard call the engine with the change', async () => {
    await seed(0, [{ table: 'list_item', op: 'delete', reason: 'rls_denied' }]);
    const { sync, dialog } = await open();
    fireEvent.click(await within(dialog).findByRole('button', { name: 'Retry' }));
    fireEvent.click(within(dialog).getByRole('button', { name: 'Discard' }));
    expect(sync.retried).toEqual([100]);
    expect(sync.discarded).toEqual([100]);
  });

  it('AC5: Sync now kicks', async () => {
    await seed(1);
    const { sync, dialog } = await open();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Sync now' }));
    expect(sync.kicks).toBe(1);
  });

  it('works offline without an error', async () => {
    Object.defineProperty(navigator, 'onLine', { value: false, configurable: true });
    await seed(1);
    const { dialog } = await open();
    expect(within(dialog).getByText(/You're offline/)).toBeTruthy();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Sync now' }));
    expect(within(dialog).queryByRole('alert')).toBeNull();
  });
});

describe('SHOP-08: Shell', () => {
  it('puts the chip in the top bar, with every module off', async () => {
    await seed(1);
    const api = new FakeApiClient();
    api.fetchHousehold = async () => ({ ok: true, value: { household: house(), role: 'owner' } });
    api.fetchMyHouseholdsResult = { ok: true, value: [{ household: house(), role: 'owner' }] };
    // The push never reaches a server here, so the queued change stays and the chip stays.
    api.syncPushResults = [{ ok: false, error: { code: 'unknown' } }];
    writeLastHousehold('h1');
    const auth = new FakeAuthClient({ user: { id: 'u1' } });
    const router = createMemoryRouter([{ element: <Shell />, children: [{ path: '/', element: <p>x</p> }] }]);
    render(
      <AuthProvider client={auth}>
        <ApiProvider client={api}>
          <RouterProvider router={router} />
        </ApiProvider>
      </AuthProvider>,
    );
    expect(await screen.findByRole('button', { name: /1 to sync/ })).toBeTruthy();
  });
});
