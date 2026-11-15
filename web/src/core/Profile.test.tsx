import type { Profile as ProfileRow } from '@abodenestly/contract';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { AuthProvider } from './auth/AuthProvider.tsx';
import { FakeAuthClient } from './auth/FakeAuthClient.ts';
import { RequireAuth } from './auth/RequireAuth.tsx';
import { SignIn } from './auth/SignIn.tsx';
import { Profile } from './Profile.tsx';
import { readCachedProfile, readOutbox } from './Profile.ts';

function setOnline(value: boolean) {
  Object.defineProperty(navigator, 'onLine', { value, configurable: true });
}

async function flush() {
  await act(async () => {
    await new Promise((r) => setTimeout(r, 0));
  });
}

async function goOnline() {
  setOnline(true);
  await act(async () => {
    window.dispatchEvent(new Event('online'));
  });
  await flush();
}

function makeClient() {
  const auth = new FakeAuthClient({ user: { id: 'u1', email: 'ada@example.com' } });
  auth.profile = { id: 'u1', display_name: 'Ada', avatar_path: null } as ProfileRow;
  return auth;
}

async function renderProfile(auth = makeClient()) {
  const router = createMemoryRouter(
    [
      { path: '/signin', element: <SignIn /> },
      { path: '/auth/callback', element: <p>callback</p> },
      {
        element: <RequireAuth />,
        children: [
          { path: '/profile', element: <Profile /> },
          { path: '/profile/notifications', element: <p>prefs</p> },
        ],
      },
    ],
    { initialEntries: ['/profile'] },
  );
  render(
    <AuthProvider client={auth}>
      <RouterProvider router={router} />
    </AuthProvider>,
  );
  await flush();
  return { auth, router };
}

const nameInput = () => screen.getByLabelText('Display name') as HTMLInputElement;
const png = () => new File(['x'], 'me.png', { type: 'image/png' });

function pick(file: File) {
  fireEvent.change(screen.getByTestId('avatar-file'), { target: { files: [file] } });
}

beforeEach(() => {
  setOnline(true);
  localStorage.clear();
});
afterEach(() => {
  cleanup();
  setOnline(true);
});

describe('CORE-06: Profile', () => {
  it('AC1 idle: shows avatar button, name, read-only email and the preferences link', async () => {
    await renderProfile();
    expect(screen.getByRole('button', { name: 'Change photo' })).toBeTruthy();
    expect(nameInput().value).toBe('Ada');
    const email = screen.getByLabelText('Email') as HTMLInputElement;
    expect(email.value).toBe('ada@example.com');
    expect(email.readOnly).toBe(true);
    expect(screen.getByRole('link', { name: 'Notification preferences' }).getAttribute('href')).toBe(
      '/profile/notifications',
    );
    expect(screen.queryByRole('status')).toBeNull();
  });

  it('AC2: a name edit on blur updates the local profile and queues one outbox entry', async () => {
    const { auth } = await renderProfile();
    setOnline(false);
    fireEvent.change(nameInput(), { target: { value: 'Grace' } });
    fireEvent.blur(nameInput());
    await flush();
    expect(readCachedProfile('u1')?.display_name).toBe('Grace');
    expect(readOutbox('u1')).toHaveLength(1);
    expect(auth.updateCalls).toEqual([]);
  });

  it('CORE-06: Enter then blur commits the name once', async () => {
    const { auth } = await renderProfile();
    fireEvent.change(nameInput(), { target: { value: 'Grace' } });
    fireEvent.keyDown(nameInput(), { key: 'Enter' });
    fireEvent.blur(nameInput());
    await flush();
    expect(auth.updateCalls).toEqual([{ userId: 'u1', data: { display_name: 'Grace' } }]);
    expect(readOutbox('u1')).toEqual([]);
  });

  it('AC3: picking a photo shows uploading-avatar while the upload is in flight, then idle with the path saved', async () => {
    const auth = makeClient();
    let release!: () => void;
    auth.uploadGate = new Promise<void>((r) => (release = r));
    await renderProfile(auth);

    fireEvent.click(screen.getByRole('button', { name: 'Change photo' }));
    pick(png());
    await flush();
    expect(screen.getByText('Uploading photo…')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Change photo' }).getAttribute('aria-busy')).toBe('true');
    expect(auth.uploadCalls).toHaveLength(1);
    expect(auth.uploadCalls[0]!.path).toMatch(/^profiles\/u1\/[^/]+\.png$/);

    release();
    await flush();
    expect(screen.queryByText('Uploading photo…')).toBeNull();
    expect(screen.getByRole('button', { name: 'Change photo' }).getAttribute('aria-busy')).toBe('false');
    expect(auth.updateCalls).toEqual([{ userId: 'u1', data: { avatar_path: auth.uploadCalls[0]!.path } }]);
    expect(readOutbox('u1')).toEqual([]);
  });

  it('CORE-06: a file type we do not store is refused without queuing anything', async () => {
    const auth = (await renderProfile()).auth;
    pick(new File(['x'], 'me.heic', { type: 'image/heic' }));
    await flush();
    expect(screen.getByRole('alert').textContent).toContain('JPG, PNG, WebP or GIF');
    expect(auth.uploadCalls).toEqual([]);
  });

  it('AC4 offline: banner, name edit and photo are queued, then both land when back online', async () => {
    const auth = makeClient();
    await renderProfile(auth);
    setOnline(false);
    await act(async () => {
      window.dispatchEvent(new Event('offline'));
    });
    expect(screen.getByRole('status').textContent).toBe("You're offline. Changes will sync when you reconnect.");

    fireEvent.change(nameInput(), { target: { value: 'Grace' } });
    fireEvent.blur(nameInput());
    pick(png());
    await flush();
    expect(screen.getByText("Photo will upload when you're online")).toBeTruthy();
    expect(auth.uploadCalls).toEqual([]);
    expect(auth.updateCalls).toEqual([]);
    expect(screen.queryByRole('alert')).toBeNull();
    expect(readOutbox('u1')).toHaveLength(1);

    await goOnline();
    expect(auth.uploadCalls).toHaveLength(1);
    expect(auth.updateCalls.map((c) => c.data)).toEqual([
      { display_name: 'Grace' },
      { avatar_path: auth.uploadCalls[0]!.path },
    ]);
    expect(readOutbox('u1')).toEqual([]);
    expect(screen.queryByText("Photo will upload when you're online")).toBeNull();
  });

  it('CORE-06: a queued edit is applied on load when the app starts online', async () => {
    const auth = makeClient();
    localStorage.setItem(
      'abodenestly:outbox:profile:u1',
      JSON.stringify([
        { id: 'e1', table: 'profile', op: 'upsert', row_id: 'u1', data: { display_name: 'Grace' }, client_ts: 't' },
      ]),
    );
    await renderProfile(auth);
    expect(nameInput().value).toBe('Grace');
    expect(auth.updateCalls).toEqual([{ userId: 'u1', data: { display_name: 'Grace' } }]);
  });

  it('CORE-06: a rejected edit is dropped and a non-blocking error is shown', async () => {
    const auth = makeClient();
    auth.updateError = { code: 'unknown' };
    await renderProfile(auth);
    fireEvent.change(nameInput(), { target: { value: 'Grace' } });
    fireEvent.blur(nameInput());
    await flush();
    expect(screen.getByRole('alert').textContent).toContain("couldn't be saved");
    expect(readOutbox('u1')).toEqual([]);
    expect(nameInput().value).toBe('Ada');
  });

  it('AC5: Sign out calls the client once and lands on /signin', async () => {
    const { auth, router } = await renderProfile();
    fireEvent.click(screen.getByRole('button', { name: 'Sign out' }));
    await flush();
    expect(auth.signOutCalls).toBe(1);
    expect(router.state.location.pathname).toBe('/signin');
  });
});
