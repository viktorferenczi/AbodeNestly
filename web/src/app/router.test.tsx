import type { Profile } from '@abodenestly/contract';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { ApiProvider } from '../core/api/ApiProvider.tsx';
import { FakeApiClient } from '../core/api/FakeApiClient.ts';
import { AuthProvider } from '../core/auth/AuthProvider.tsx';
import { FakeAuthClient } from '../core/auth/FakeAuthClient.ts';
import { readLastHousehold } from '../core/auth/lastHousehold.ts';
import { Welcome } from '../core/Welcome.tsx';
import { JoinRedirect } from './router.tsx';

async function flush() {
  await act(async () => {
    await new Promise((r) => setTimeout(r, 0));
  });
}

beforeEach(() => localStorage.clear());
afterEach(cleanup);

describe('CORE-03: invite link', () => {
  it('AC4: opening /join/:code fills the join card, and a tap joins and lands on /home', async () => {
    const auth = new FakeAuthClient({ user: { id: 'u1' } });
    auth.profile = { id: 'u1', display_name: 'Ada', avatar_path: null } as Profile;
    const api = new FakeApiClient();
    api.acceptResult = { ok: true, value: { household_id: 'h-joined' } };
    const router = createMemoryRouter(
      [
        { path: '/join/:code', element: <JoinRedirect /> },
        { path: '/welcome', element: <Welcome /> },
        { path: '/home', element: <p>home</p> },
      ],
      { initialEntries: ['/join/0123456789ab'] },
    );
    render(
      <AuthProvider client={auth}>
        <ApiProvider client={api}>
          <RouterProvider router={router} />
        </ApiProvider>
      </AuthProvider>,
    );
    await flush();
    expect(router.state.location.pathname).toBe('/welcome');
    expect((screen.getByLabelText('Invite code') as HTMLInputElement).value).toBe('0123456789ab');
    expect(api.acceptCalls).toEqual([]);

    fireEvent.click(screen.getByRole('button', { name: 'Join household' }));
    await flush();
    expect(api.acceptCalls).toEqual(['0123456789ab']);
    expect(router.state.location.pathname).toBe('/home');
    expect(readLastHousehold()).toBe('h-joined');
  });
});
