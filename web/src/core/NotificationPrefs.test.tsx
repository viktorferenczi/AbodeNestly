import type { Household, Profile } from '@abodenestly/contract';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
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
import { NotificationPrefs } from './NotificationPrefs.tsx';

const house = (over: Partial<Household> = {}) =>
  ({ id: 'h1', name: 'Home One', currency: 'EUR', module_shopping: true, module_pantry: true, module_maintenance: true, ...over }) as Household;

async function renderPrefs(household = house()) {
  const api = new FakeApiClient();
  api.fetchHousehold = async () => ({ ok: true, value: { household, role: 'owner' } });
  api.fetchMyHouseholdsResult = { ok: true, value: [{ household, role: 'owner' }] };
  writeLastHousehold('h1');
  const auth = new FakeAuthClient({ user: { id: 'u1' } });
  auth.profile = { id: 'u1', display_name: 'Ada' } as Profile;
  const sync = new FakeSync();
  const router = createMemoryRouter(
    [{ element: <Shell />, children: [{ path: '/prefs', element: <SyncProvider engine={sync}><NotificationPrefs /></SyncProvider> }] }],
    { initialEntries: ['/prefs'] },
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

const stubPermission = (permission: NotificationPermission | undefined, request?: () => Promise<NotificationPermission>) => {
  if (permission === undefined) vi.stubGlobal('Notification', undefined);
  else vi.stubGlobal('Notification', { permission, requestPermission: request ?? (async () => permission) });
};

beforeEach(async () => {
  localStorage.clear();
  stubPermission('granted');
  const { default: Dexie } = await import('dexie');
  await Dexie.delete('abodenestly-h1');
});
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const TYPES = ['eat_soon', 'low_stock', 'task_due', 'warranty', 'activity'];

describe('CORE-12: Notification preferences screen', () => {
  it('AC1 idle shows five switches, all on by default', async () => {
    await renderPrefs();
    await screen.findByRole('heading', { name: 'Notification preferences' });
    for (const t of TYPES) expect((screen.getByTestId(`notif.switch.${t}`) as HTMLInputElement).checked).toBe(true);
    expect(screen.getByRole('switch', { name: 'Eat soon' })).toBeTruthy();
    expect(screen.queryByTestId('notif.denied')).toBeNull();
  });

  it('AC2 toggling Eat soon off queues one upsert and kicks sync', async () => {
    const { sync } = await renderPrefs();
    fireEvent.click(await screen.findByTestId('notif.switch.eat_soon'));
    await waitFor(() => expect((screen.getByTestId('notif.switch.eat_soon') as HTMLInputElement).checked).toBe(false));
    await waitFor(() => expect(sync.kicks).toBe(1));
    const db = await openHouseholdDb('h1');
    const out = await db.outbox.toArray();
    expect(out).toHaveLength(1);
    expect(out[0]!.payload).toMatchObject({ table: 'notification_pref', op: 'upsert', data: { user_id: 'u1', household_id: 'h1', type: 'eat_soon', enabled: false } });
    await db.close();
  });

  it('AC3 permission denied shows the banner and keeps the switches', async () => {
    stubPermission('denied');
    await renderPrefs();
    const banner = await screen.findByTestId('notif.denied');
    expect(banner.getAttribute('role')).toBe('status');
    expect(screen.getAllByRole('switch')).toHaveLength(5);
  });

  it('AC4 pantry off hides Eat soon and Low stock', async () => {
    await renderPrefs(house({ module_pantry: false }));
    await screen.findByTestId('notif.switch.task_due'); // modules are known once a module row shows
    expect(screen.queryByTestId('notif.switch.eat_soon')).toBeNull();
    expect(screen.queryByTestId('notif.switch.low_stock')).toBeNull();
    expect(screen.getByTestId('notif.switch.activity')).toBeTruthy();
  });

  it('AC5 maintenance off hides Task due and Warranty; Activity stays', async () => {
    await renderPrefs(house({ module_maintenance: false }));
    await screen.findByTestId('notif.switch.eat_soon');
    expect(screen.queryByTestId('notif.switch.task_due')).toBeNull();
    expect(screen.queryByTestId('notif.switch.warranty')).toBeNull();
    expect(screen.getByTestId('notif.switch.activity')).toBeTruthy();
  });

  it('settings link asks for permission while it is still undecided', async () => {
    const request = vi.fn(async () => 'denied' as NotificationPermission);
    stubPermission('default', request);
    await renderPrefs();
    fireEvent.click(await screen.findByTestId('notif.settings'));
    await waitFor(() => expect(request).toHaveBeenCalledTimes(1));
    expect(await screen.findByTestId('notif.denied')).toBeTruthy();
  });

  it('settings link explains where to change it once the choice is made', async () => {
    const request = vi.fn(async () => 'granted' as NotificationPermission);
    stubPermission('granted', request);
    await renderPrefs();
    await screen.findByTestId('notif.switch.activity');
    expect(screen.queryByTestId('notif.settings.help')).toBeNull();
    fireEvent.click(screen.getByTestId('notif.settings'));
    expect(screen.getByTestId('notif.settings.help').textContent).toMatch(/browser's site settings/);
    expect(request).not.toHaveBeenCalled();
  });

  it('a browser without the Notification API still lists the switches', async () => {
    stubPermission(undefined);
    await renderPrefs();
    await screen.findByTestId('notif.switch.activity');
    expect(screen.queryByTestId('notif.denied')).toBeNull();
    fireEvent.click(screen.getByTestId('notif.settings'));
    expect(screen.getByTestId('notif.settings.help').textContent).toMatch(/does not support/);
  });

  it('re-reads the permission when the tab regains focus', async () => {
    stubPermission('granted');
    await renderPrefs();
    await screen.findByTestId('notif.switch.activity');
    expect(screen.queryByTestId('notif.denied')).toBeNull();
    stubPermission('denied');
    fireEvent.focus(window);
    expect(await screen.findByTestId('notif.denied')).toBeTruthy();
  });
});
