import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { FakeAuthClient } from './FakeAuthClient.ts';
import { AuthProvider } from './AuthProvider.tsx';
import { RequireAuth } from './RequireAuth.tsx';
import { SignIn } from './SignIn.tsx';

function setOnline(value: boolean) {
  Object.defineProperty(navigator, 'onLine', { value, configurable: true });
}

async function flush() {
  await act(async () => {
    await Promise.resolve();
  });
}

async function renderAt(path: string, client = new FakeAuthClient()) {
  const router = createMemoryRouter(
    [
      { path: '/signin', element: <SignIn /> },
      { path: '/auth/callback', element: <p>callback</p> },
      { element: <RequireAuth />, children: [{ path: '/pantry', element: <p>pantry</p> }] },
    ],
    { initialEntries: [path] },
  );
  render(
    <AuthProvider client={client}>
      <RouterProvider router={router} />
    </AuthProvider>,
  );
  await flush();
  return { router, client };
}

async function submitEmail(value: string) {
  fireEvent.change(screen.getByLabelText('Email'), { target: { value } });
  fireEvent.click(screen.getByRole('button', { name: 'Send magic link' }));
  await flush();
}

beforeEach(() => {
  vi.useFakeTimers();
  setOnline(true);
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  setOnline(true);
});

describe('CORE-01: SignIn', () => {
  it('CORE-01: AC1 signed out at /pantry?x=1 redirects to /signin keeping the return path', async () => {
    const { router } = await renderAt('/pantry?x=1');
    expect(router.state.location.pathname).toBe('/signin');
    expect(router.state.location.search).toBe('?next=%2Fpantry%3Fx%3D1');
  });

  it('CORE-01: idle renders the B1 layout', async () => {
    await renderAt('/signin');
    expect(screen.getByRole('heading', { name: 'Sign in to AbodeNestly' })).toBeTruthy();
    expect(screen.getByLabelText('Email')).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Send magic link' }) as HTMLButtonElement).disabled).toBe(false);
    expect(screen.getByRole('button', { name: 'Sign in with Apple' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Sign in with Google' })).toBeTruthy();
  });

  it('CORE-01: AC2 sending shows link-sent, then Resend enables after 30 s', async () => {
    const { client } = await renderAt('/signin?next=%2Fpantry');
    await submitEmail('me@example.com');

    expect(client.otpCalls).toEqual([
      { email: 'me@example.com', redirectTo: `${window.location.origin}/auth/callback?next=%2Fpantry` },
    ]);
    expect(screen.getByRole('heading', { name: 'Check your email' })).toBeTruthy();
    expect(screen.queryByLabelText('Email')).toBeNull();

    const resend = () => screen.getByRole('button', { name: /^Resend/ }) as HTMLButtonElement;
    expect(resend().disabled).toBe(true);
    expect(resend().textContent).toBe('Resend (30 s)');

    await act(async () => void vi.advanceTimersByTime(29_000));
    expect(resend().disabled).toBe(true);

    await act(async () => void vi.advanceTimersByTime(1_000));
    expect(resend().disabled).toBe(false);
    expect(resend().textContent).toBe('Resend');
  });

  it('CORE-01: sending disables the button while the request is pending', async () => {
    const client = new FakeAuthClient();
    let release: (error: null) => void = () => {};
    client.signInWithOtp = () => new Promise((resolve) => (release = resolve));
    await renderAt('/signin', client);

    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'me@example.com' } });
    fireEvent.click(screen.getByRole('button', { name: 'Send magic link' }));
    await flush();
    expect((screen.getByRole('button', { name: 'Send magic link' }) as HTMLButtonElement).disabled).toBe(true);

    release(null);
    await flush();
    expect(screen.getByRole('heading', { name: 'Check your email' })).toBeTruthy();
  });

  it('CORE-01: AC3 invalid email shows an inline error and sends no request', async () => {
    const { client } = await renderAt('/signin');
    await submitEmail('not-an-email');

    const input = screen.getByLabelText('Email');
    expect(input.getAttribute('aria-invalid')).toBe('true');
    const errorId = input.getAttribute('aria-describedby');
    expect(document.getElementById(errorId!)?.textContent).toBe('Enter a valid email address.');
    expect(client.otpCalls).toHaveLength(0);
  });

  it('CORE-01: an error from the client shows the inline error', async () => {
    const client = new FakeAuthClient();
    client.otpError = { code: 'rate_limited' };
    await renderAt('/signin', client);
    await submitEmail('me@example.com');

    const input = screen.getByLabelText('Email');
    expect(document.getElementById(input.getAttribute('aria-describedby')!)?.textContent).toMatch(/Too many attempts/);
    expect((screen.getByRole('button', { name: 'Send magic link' }) as HTMLButtonElement).disabled).toBe(false);
  });

  it.each([
    ['Apple', 'apple'],
    ['Google', 'google'],
  ] as const)('CORE-01: AC5 %s click calls signInWithOAuth with the callback redirect', async (label, provider) => {
    const { client } = await renderAt('/signin');
    fireEvent.click(screen.getByRole('button', { name: `Sign in with ${label}` }));
    await flush();
    expect(client.oauthCalls).toEqual([{ provider, redirectTo: `${window.location.origin}/auth/callback` }]);
  });

  it('CORE-01: AC7 offline disables the buttons and shows the banner', async () => {
    setOnline(false);
    await renderAt('/signin');
    expect(screen.getByRole('status').textContent).toBe("You're offline. Connect to sign in.");
    for (const name of ['Send magic link', 'Sign in with Apple', 'Sign in with Google']) {
      expect((screen.getByRole('button', { name }) as HTMLButtonElement).disabled).toBe(true);
    }
  });

  it('CORE-01: already signed in on /signin goes to the landing route', async () => {
    const client = new FakeAuthClient({ user: { id: 'u1' } });
    const { router } = await renderAt('/signin?next=%2Fpantry', client);
    expect(router.state.location.pathname).toBe('/auth/callback');
    expect(router.state.location.search).toBe('?next=%2Fpantry');
  });
});
