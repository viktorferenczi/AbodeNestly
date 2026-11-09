import { act, cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { AuthProvider } from '../core/auth/AuthProvider.tsx';
import { FakeAuthClient } from '../core/auth/FakeAuthClient.ts';
import { Shell } from './Shell.tsx';

afterEach(cleanup);

async function renderShell(client: FakeAuthClient) {
  const router = createMemoryRouter([{ path: '/', element: <Shell />, children: [{ index: true, element: <p>child</p> }] }]);
  render(
    <AuthProvider client={client}>
      <RouterProvider router={router} />
    </AuthProvider>,
  );
  await act(async () => {
    await Promise.resolve();
  });
}

describe('Shell', () => {
  it('INIT: shell renders the title, main region and routed child', async () => {
    await renderShell(new FakeAuthClient({ user: { id: 'u1' } }));
    expect(screen.getByRole('heading', { name: 'AbodeNestly' })).toBeTruthy();
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
});
