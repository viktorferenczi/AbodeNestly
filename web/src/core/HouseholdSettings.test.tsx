import type { Household, Profile } from '@abodenestly/contract';
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { Shell } from '../app/Shell.tsx';
import { ApiProvider } from './api/ApiProvider.tsx';
import { FakeApiClient } from './api/FakeApiClient.ts';
import { AuthProvider } from './auth/AuthProvider.tsx';
import { FakeAuthClient } from './auth/FakeAuthClient.ts';
import { readLastHousehold, writeLastHousehold } from './auth/lastHousehold.ts';
import { HouseholdSettings } from './HouseholdSettings.tsx';

function setOnline(value: boolean) {
  Object.defineProperty(navigator, 'onLine', { value, configurable: true });
}

async function flush() {
  await act(async () => {
    await new Promise((r) => setTimeout(r, 0));
  });
}

const HOME = {
  id: 'h1',
  name: 'Test Home',
  currency: 'EUR',
  module_shopping: true,
  module_pantry: true,
  module_maintenance: true,
} as Household;

async function renderSettings(role: 'owner' | 'member', api = new FakeApiClient(), fetchFails = false) {
  if (!fetchFails) api.fetchHouseholdResult = { ok: true, value: { household: HOME, role } };
  writeLastHousehold('h1');
  const auth = new FakeAuthClient({ user: { id: 'u1' } });
  auth.profile = { id: 'u1', display_name: 'Ada' } as Profile;
  const router = createMemoryRouter(
    [
      {
        element: <Shell />,
        children: [
          { path: '/household', element: <HouseholdSettings /> },
          { path: '/', element: <p>landing</p> },
        ],
      },
    ],
    { initialEntries: ['/household'] },
  );
  render(
    <AuthProvider client={auth}>
      <ApiProvider client={api}>
        <RouterProvider router={router} />
      </ApiProvider>
    </AuthProvider>,
  );
  await flush();
  return { api, router };
}

const sw = (name: string) => screen.getByRole('switch', { name }) as HTMLInputElement;
const nameInput = () => screen.getByLabelText('Household name') as HTMLInputElement;
const currency = () => screen.getByLabelText('Currency') as HTMLSelectElement;
const tab = (name: string) => screen.queryByRole('link', { name });

beforeEach(() => {
  setOnline(true);
  localStorage.clear();
});
afterEach(() => {
  cleanup();
  setOnline(true);
});

describe('CORE-05: HouseholdSettings', () => {
  it('AC1 owner: editable name, three enabled switches, currency select', async () => {
    await renderSettings('owner');
    expect(nameInput().readOnly).toBe(false);
    expect(nameInput().value).toBe('Test Home');
    for (const n of ['Shopping', 'Pantry', 'Maintenance']) {
      expect(sw(n).disabled).toBe(false);
      expect(sw(n).checked).toBe(true);
    }
    expect(currency().disabled).toBe(false);
    // Owners can leave too while Delete has no endpoint; the DB refuses the sole owner.
    expect(screen.getByRole('button', { name: 'Leave household' })).toBeTruthy();
  });

  it('AC2 member: read-only fields, disabled controls, Leave visible', async () => {
    await renderSettings('member');
    expect(nameInput().readOnly).toBe(true);
    for (const n of ['Shopping', 'Pantry', 'Maintenance']) expect(sw(n).disabled).toBe(true);
    expect(currency().disabled).toBe(true);
    expect((screen.getByRole('button', { name: 'Leave household' }) as HTMLButtonElement).disabled).toBe(false);
  });

  it('AC3 saving: controls disabled with Saving…, then the Pantry tab disappears', async () => {
    const api = new FakeApiClient();
    api.updateModulesResult = { ok: true, value: { ...HOME, module_pantry: false } };
    await renderSettings('owner', api);
    expect(tab('Pantry')).toBeTruthy();
    api.pending = true;
    fireEvent.click(sw('Pantry'));
    await flush();
    expect(api.updateModulesCalls).toEqual([{ householdId: 'h1', body: { pantry: false } }]);
    expect(sw('Shopping').disabled).toBe(true);
    expect(nameInput().disabled).toBe(true);
    expect(screen.getByText('Saving…').getAttribute('aria-live')).toBe('polite');
    api.pending = false;
    api.resolvePending();
    await flush();
    expect(tab('Pantry')).toBeNull();
    expect(tab('Shopping')).toBeTruthy();
    expect(sw('Pantry').checked).toBe(false);
    expect(sw('Shopping').disabled).toBe(false);
    expect(screen.queryByText('Saving…')).toBeNull();
    // Cached for offline reads.
    expect(JSON.parse(localStorage.getItem('abodenestly:household:u1:h1')!).household.module_pantry).toBe(false);
  });

  it('AC4 offline: banner shown and every control disabled', async () => {
    await renderSettings('owner');
    setOnline(false);
    await act(async () => {
      window.dispatchEvent(new Event('offline'));
    });
    expect(screen.getByRole('status').textContent).toBe("You're offline. Connect to change household settings.");
    for (const n of ['Shopping', 'Pantry', 'Maintenance']) expect(sw(n).disabled).toBe(true);
    expect(nameInput().readOnly).toBe(true);
    expect(currency().disabled).toBe(true);
  });

  it('offline from the start reads the cached household', async () => {
    localStorage.setItem('abodenestly:household:u1:h1', JSON.stringify({ household: { ...HOME, name: 'Cached Home' }, role: 'owner' }));
    setOnline(false);
    const api = new FakeApiClient();
    api.fetchHouseholdResult = { ok: false, error: { code: 'network' } };
    await renderSettings('owner', api, true);
    expect(nameInput().value).toBe('Cached Home');
    expect(sw('Pantry').disabled).toBe(true);
  });

  it('a failed toggle shows an error and restores the switch', async () => {
    const api = new FakeApiClient();
    api.updateModulesResult = { ok: false, error: { code: 'unknown' } };
    await renderSettings('owner', api);
    fireEvent.click(sw('Pantry'));
    await flush();
    expect(screen.getByRole('alert').textContent).toContain("Couldn't save");
    expect(sw('Pantry').checked).toBe(true);
  });

  it('forbidden toggle shows the error and reloads the household', async () => {
    const api = new FakeApiClient();
    api.updateModulesResult = { ok: false, error: { code: 'forbidden' } };
    await renderSettings('owner', api);
    expect(api.fetchHouseholdCalls).toHaveLength(1);
    fireEvent.click(sw('Pantry'));
    await flush();
    expect(screen.getByRole('alert').textContent).toContain('Only owners');
    expect(api.fetchHouseholdCalls).toHaveLength(2);
  });

  it('name saves on blur when changed and trimmed, not when unchanged or empty', async () => {
    const api = new FakeApiClient();
    api.updateHouseholdResult = { ok: true, value: { ...HOME, name: 'New Name' } };
    await renderSettings('owner', api);
    fireEvent.blur(nameInput());
    fireEvent.change(nameInput(), { target: { value: '   ' } });
    fireEvent.blur(nameInput());
    await flush();
    expect(api.updateHouseholdCalls).toEqual([]);
    fireEvent.change(nameInput(), { target: { value: '  New Name ' } });
    fireEvent.blur(nameInput());
    await flush();
    expect(api.updateHouseholdCalls).toEqual([{ householdId: 'h1', patch: { name: 'New Name' } }]);
    expect(nameInput().value).toBe('New Name');
  });

  it('Enter saves the name once', async () => {
    const api = new FakeApiClient();
    api.updateHouseholdResult = { ok: true, value: { ...HOME, name: 'Enter Home' } };
    await renderSettings('owner', api);
    fireEvent.change(nameInput(), { target: { value: 'Enter Home' } });
    fireEvent.keyDown(nameInput(), { key: 'Enter' });
    fireEvent.blur(nameInput());
    await flush();
    expect(api.updateHouseholdCalls).toHaveLength(1);
  });

  it('currency change calls updateHousehold', async () => {
    const api = new FakeApiClient();
    api.updateHouseholdResult = { ok: true, value: { ...HOME, currency: 'USD' } };
    await renderSettings('owner', api);
    fireEvent.change(currency(), { target: { value: 'USD' } });
    await flush();
    expect(api.updateHouseholdCalls).toEqual([{ householdId: 'h1', patch: { currency: 'USD' } }]);
    expect(currency().value).toBe('USD');
  });

  it('AC5 leave: Leave opens the sheet, confirming calls leaveHousehold and goes to /', async () => {
    const { api, router } = await renderSettings('member');
    fireEvent.click(screen.getByRole('button', { name: 'Leave household' }));
    const dialog = screen.getByRole('dialog', { name: 'Leave Test Home?' });
    expect(api.leaveCalls).toEqual([]);
    fireEvent.click(within(dialog).getByRole('button', { name: 'Leave' }));
    await flush();
    expect(api.leaveCalls).toEqual([{ householdId: 'h1', userId: 'u1' }]);
    expect(router.state.location.pathname).toBe('/');
    expect(readLastHousehold()).toBeNull();
    expect(localStorage.getItem('abodenestly:household:u1:h1')).toBeNull();
  });

  it('the select shows the new currency while the update is pending', async () => {
    const api = new FakeApiClient();
    api.updateHouseholdResult = { ok: true, value: { ...HOME, currency: 'USD' } };
    await renderSettings('owner', api);
    api.pending = true;
    fireEvent.change(currency(), { target: { value: 'USD' } });
    await flush();
    expect(currency().disabled).toBe(true);
    expect(currency().value).toBe('USD');
    api.pending = false;
    api.resolvePending();
    await flush();
  });

  it("another user's cached household is not shown as ready", async () => {
    localStorage.setItem(
      'abodenestly:household:u2:h1',
      JSON.stringify({ household: { ...HOME, name: 'Other Home' }, role: 'owner' }),
    );
    const api = new FakeApiClient();
    api.pending = true;
    await renderSettings('member', api);
    expect(screen.queryByLabelText('Household name')).toBeNull();
    expect(screen.getByText('Loading…')).toBeTruthy();
    api.pending = false;
    api.resolvePending();
    await flush();
  });

  it('clicking a switch description toggles the switch', async () => {
    const api = new FakeApiClient();
    api.updateModulesResult = { ok: true, value: { ...HOME, module_pantry: false } };
    await renderSettings('owner', api);
    fireEvent.click(screen.getByText(/What you have at home/));
    await flush();
    expect(api.updateModulesCalls[0]?.body).toEqual({ pantry: false });
  });

  it('Cancel closes the leave sheet without leaving', async () => {
    const { api } = await renderSettings('member');
    fireEvent.click(screen.getByRole('button', { name: 'Leave household' }));
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(api.leaveCalls).toEqual([]);
  });

  it('last_owner leave shows an inline message and stays', async () => {
    const api = new FakeApiClient();
    api.leaveResult = { ok: false, error: { code: 'last_owner' } };
    const { router } = await renderSettings('owner', api);
    fireEvent.click(screen.getByRole('button', { name: 'Leave household' }));
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Leave' }));
    await flush();
    expect(within(screen.getByRole('dialog')).getByRole('alert').textContent).toContain('last owner');
    expect(router.state.location.pathname).toBe('/household');
    expect(readLastHousehold()).toBe('h1');
  });
});
