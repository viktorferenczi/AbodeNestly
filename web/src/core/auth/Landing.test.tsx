import { act, cleanup, render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { FakeAuthClient } from './FakeAuthClient.ts';
import { AuthProvider } from './AuthProvider.tsx';
import { Landing } from './Landing.tsx';
import type { Membership } from './landing.ts';

function setOnline(value: boolean) {
  Object.defineProperty(navigator, 'onLine', { value, configurable: true });
}

const household: Membership = {
  household_id: 'h1',
  joined_at: '2026-01-01T00:00:00Z',
  household: { id: 'h1', module_shopping: false, module_pantry: true, module_maintenance: true },
};

async function renderLanding(client: FakeAuthClient, path = '/auth/callback') {
  const router = createMemoryRouter(
    [
      { path: '/auth/callback', element: <Landing /> },
      { path: '/signin', element: <p>signin</p> },
      { path: '/welcome', element: <p>welcome</p> },
      { path: '/pantry', element: <p>pantry</p> },
      { path: '/shopping', element: <p>shopping</p> },
      { path: '/home', element: <p>home</p> },
    ],
    { initialEntries: [path] },
  );
  const view = render(
    <AuthProvider client={client}>
      <RouterProvider router={router} />
    </AuthProvider>,
  );
  await act(async () => {
    await new Promise((r) => setTimeout(r, 0));
  });
  return { router, view };
}

beforeEach(() => {
  localStorage.clear();
  setOnline(true);
});
afterEach(() => {
  cleanup();
  setOnline(true);
});

describe('CORE-01: Landing (deep link and callback)', () => {
  it('CORE-01: AC4 no household lands on /welcome', async () => {
    const { router } = await renderLanding(new FakeAuthClient({ user: { id: 'u1' } }));
    expect(router.state.location.pathname).toBe('/welcome');
  });

  it('CORE-01: AC4 a household lands on its start tab and is remembered', async () => {
    const client = new FakeAuthClient({ user: { id: 'u1' } });
    client.memberships = [household];
    const { router } = await renderLanding(client);
    expect(router.state.location.pathname).toBe('/pantry');
    expect(localStorage.getItem('abodenestly:lastHousehold')).toBe('h1');
  });

  it('CORE-01: a safe next is followed when the user has a household', async () => {
    const client = new FakeAuthClient({ user: { id: 'u1' } });
    client.memberships = [household];
    const { router } = await renderLanding(client, '/auth/callback?next=%2Fshopping');
    expect(router.state.location.pathname).toBe('/shopping');
  });

  it('CORE-01: signed out goes to /signin', async () => {
    const { router } = await renderLanding(new FakeAuthClient());
    expect(router.state.location.pathname).toBe('/signin');
  });

  it('CORE-01: a failed membership fetch online shows a blocking alert', async () => {
    const client = new FakeAuthClient({ user: { id: 'u1' } });
    client.membershipsFail = true;
    const { router, view } = await renderLanding(client);
    expect(router.state.location.pathname).toBe('/auth/callback');
    expect(view.getByRole('alert').textContent).toMatch(/Couldn't load your households/);
  });

  it('CORE-01: a failed membership fetch offline falls back to /home', async () => {
    setOnline(false);
    const client = new FakeAuthClient({ user: { id: 'u1' } });
    client.membershipsFail = true;
    const { router } = await renderLanding(client);
    expect(router.state.location.pathname).toBe('/home');
  });
});
