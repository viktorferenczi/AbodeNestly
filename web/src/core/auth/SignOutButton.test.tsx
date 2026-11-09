import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { FakeAuthClient } from './FakeAuthClient.ts';
import { AuthProvider } from './AuthProvider.tsx';
import { RequireAuth } from './RequireAuth.tsx';
import { SignIn } from './SignIn.tsx';
import { SignOutButton } from './SignOutButton.tsx';

afterEach(cleanup);

async function setup(client: FakeAuthClient) {
  const router = createMemoryRouter(
    [
      { path: '/signin', element: <SignIn /> },
      { path: '/auth/callback', element: <p>callback</p> },
      { element: <RequireAuth />, children: [{ path: '/profile', element: <SignOutButton /> }] },
    ],
    { initialEntries: ['/profile'] },
  );
  render(
    <AuthProvider client={client}>
      <RouterProvider router={router} />
    </AuthProvider>,
  );
  await act(async () => {
    await new Promise((r) => setTimeout(r, 0));
  });
  return router;
}

describe('CORE-01: sign out', () => {
  it('CORE-01: signing out ends on a clean /signin, never via the callback', async () => {
    const client = new FakeAuthClient({ user: { id: 'u1' } });
    const router = await setup(client);
    const visited: string[] = [];
    router.subscribe((s) => visited.push(s.location.pathname + s.location.search));
    fireEvent.click(screen.getByRole('button', { name: 'Sign out' }));
    await act(async () => {
      await new Promise((r) => setTimeout(r, 0));
    });
    expect(client.signOutCalls).toBe(1);
    expect(router.state.location.pathname + router.state.location.search).toBe('/signin');
    expect(visited).not.toContain('/auth/callback');
  });

  it('CORE-01: a failed sign-out stays put and shows an alert', async () => {
    const client = new FakeAuthClient({ user: { id: 'u1' } });
    client.signOutFail = true;
    const router = await setup(client);
    fireEvent.click(screen.getByRole('button', { name: 'Sign out' }));
    await act(async () => {
      await new Promise((r) => setTimeout(r, 0));
    });
    expect(router.state.location.pathname).toBe('/profile');
    expect(screen.getByRole('alert').textContent).toMatch(/Couldn't sign out/);
  });
});
