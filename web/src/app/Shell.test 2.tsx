import { act, cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { ApiProvider } from '../core/api/ApiProvider.tsx';
import { FakeApiClient } from '../core/api/FakeApiClient.ts';
import { AuthProvider } from '../core/auth/AuthProvider.tsx';
import { FakeAuthClient } from '../core/auth/FakeAuthClient.ts';
import type { Household } from '@abodenestly/contract';
import { writeLastHousehold } from '../core/auth/lastHousehold.ts';
import { Shell } from './Shell.tsx';

beforeEach(() => localStorage.clear());
afterEach(cleanup);

async function renderShell(client: FakeAuthClient, api = new FakeApiClient()) {
  const router = createMemoryRouter([{ path: '/', element: <Shell />, children: [{ index: true, element: <p>child</p> }] }]);
  render(
    <AuthProvider client={client}>
      <ApiProvider client={api}>
        <RouterProvider router={router} />
      </ApiProvider>
    </AuthProvider>,
  );
  await act(async () => {
    await Promise.resolve();
  });
}

describe('Shell', () => {
  it('INIT: shell renders the title, main region and routed child', async () => {
    await renderShell(new FakeAuthClient({ user: { id: 'u1' } }));
    expect(screen.getByRole('heading', { name: 'AbodeNestly ▾' })).toBeTruthy();
    expect(screen.getByRole('main').textContent).toBe('child');
  });

  it('CORE-01: AC8 TopBar shows the profile display_name', async () => {
    const client = new FakeAuthClient({ user: { id: 'u1' } });
    client.profile = {
      id: 'u1',
      display_name: 'Ada Lovelace',
      avatar_path: null,
      created_at: '2026-01-01T00:00:00Z',
      updated_at: '2026-01-01T00:00:00Z',
    };
    await renderShell(client);
    expect(screen.getByText('Ada Lovelace')).toBeTruthy();
  });

  it('CORE-01: AC8 a missing profile row shows no name', async () => {
    await renderShell(new FakeAuthClient({ user: { id: 'u1' } }));
    expect(screen.queryByText('Ada Lovelace')).toBeNull();
  });

  it('CORE-05: the tab bar shows only enabled modules once the household is ready', async () => {
    const api = new FakeApiClient();
    api.fetchHouseholdResult = {
      ok: true,
      value: {
        role: 'owner',
        household: { id: 'h1', module_shopping: true, module_pantry: false, module_maintenance: true } as Household,
      },
    };
    writeLastHousehold('h1');
    await renderShell(new FakeAuthClient({ user: { id: 'u1' } }), api);
    // The TopBar magnifier (CORE-09) is a link too; this test is about the tab bar.
    const links = screen.getAllByRole('link', { name: /^(?!Search$)/ }).map((a) => a.textContent);
    expect(links).toEqual(['Shopping', 'Maintenance', 'Home', 'Profile']);
  });

  it('CORE-05: no household means no tab bar', async () => {
    await renderShell(new FakeAuthClient({ user: { id: 'u1' } }));
    expect(screen.queryByRole('navigation')).toBeNull();
  });
});
