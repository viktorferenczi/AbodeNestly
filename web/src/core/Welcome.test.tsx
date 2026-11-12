import type { Household, Profile } from '@abodenestly/contract';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { FakeApiClient } from './api/FakeApiClient.ts';
import { ApiProvider } from './api/ApiProvider.tsx';
import { AuthProvider } from './auth/AuthProvider.tsx';
import { FakeAuthClient } from './auth/FakeAuthClient.ts';
import { readLastHousehold } from './auth/lastHousehold.ts';
import { Welcome } from './Welcome.tsx';

function setOnline(value: boolean) {
  Object.defineProperty(navigator, 'onLine', { value, configurable: true });
}

async function flush() {
  await act(async () => {
    await new Promise((r) => setTimeout(r, 0));
  });
}

const profile = { id: 'u1', display_name: 'Ada', avatar_path: null } as Profile;
const household = { id: 'h-new', name: 'Test Home' } as Household;

async function renderWelcome(
  auth = new FakeAuthClient({ user: { id: 'u1' } }),
  api = new FakeApiClient(),
  entry = '/welcome',
) {
  auth.profile ??= profile;
  const router = createMemoryRouter(
    [
      { path: '/welcome', element: <Welcome /> },
      { path: '/home', element: <p>home</p> },
      { path: '/signin', element: <p>signin</p> },
    ],
    { initialEntries: [entry] },
  );
  render(
    <AuthProvider client={auth}>
      <ApiProvider client={api}>
        <RouterProvider router={router} />
      </ApiProvider>
    </AuthProvider>,
  );
  await flush();
  return { router, auth, api };
}

const createButton = () => screen.getByRole('button', { name: 'Create household' }) as HTMLButtonElement;
const joinButton = () => screen.getByRole('button', { name: 'Join household' }) as HTMLButtonElement;

function submitCreate(name = 'Test Home') {
  fireEvent.change(screen.getByLabelText('Household name'), { target: { value: name } });
  fireEvent.click(createButton());
}

function submitJoin(code = 'ABC123') {
  fireEvent.change(screen.getByLabelText('Invite code'), { target: { value: code } });
  fireEvent.click(joinButton());
}

beforeEach(() => {
  localStorage.clear();
  setOnline(true);
});

afterEach(() => {
  cleanup();
  setOnline(true);
});

describe('CORE-02: Welcome', () => {
  it('CORE-02: AC1 idle shows the greeting, both cards and enabled buttons', async () => {
    await renderWelcome();
    expect(screen.getByRole('heading', { level: 1 }).textContent).toContain('Ada');
    expect(screen.getByRole('heading', { name: 'Create a household' })).toBeTruthy();
    expect(screen.getByRole('heading', { name: 'Join with a code' })).toBeTruthy();
    expect(createButton().disabled).toBe(false);
    expect(joinButton().disabled).toBe(false);
    expect(screen.getByRole('button', { name: 'Sign out' })).toBeTruthy();
  });

  it('CORE-02: AC2 creating disables the button and marks it busy while pending', async () => {
    const api = new FakeApiClient();
    api.pending = true;
    await renderWelcome(undefined, api);
    submitCreate();
    await flush();
    expect(createButton().disabled).toBe(true);
    expect(createButton().getAttribute('aria-busy')).toBe('true');
    expect(joinButton().disabled).toBe(true);
  });

  it('CORE-02: AC2 create success calls the API, remembers the household and lands on /home', async () => {
    const api = new FakeApiClient();
    api.createResult = { ok: true, value: household };
    const { router } = await renderWelcome(undefined, api);
    submitCreate();
    await flush();
    expect(api.createCalls).toEqual(['Test Home']);
    expect(router.state.location.pathname).toBe('/home');
    expect(readLastHousehold()).toBe('h-new');
  });

  it('CORE-02: an empty or whitespace name shows an inline error and sends nothing', async () => {
    const { api } = await renderWelcome();
    submitCreate('   ');
    await flush();
    expect(api.createCalls).toEqual([]);
    expect(screen.getByRole('alert').textContent).toBe('Enter a name for your household.');
    expect(screen.getByLabelText('Household name').getAttribute('aria-invalid')).toBe('true');
  });

  it('CORE-02: an empty code shows an inline error and sends nothing', async () => {
    const { api } = await renderWelcome();
    fireEvent.click(joinButton());
    await flush();
    expect(api.acceptCalls).toEqual([]);
    expect(screen.getByRole('alert').textContent).toBe('Enter your invite code.');
  });

  it('CORE-02: AC3 a failure shows an alert and keeps the typed name', async () => {
    const api = new FakeApiClient();
    api.createResult = { ok: false, error: { code: 'network' } };
    await renderWelcome(undefined, api);
    submitCreate('Test Home');
    await flush();
    expect(screen.getByRole('alert').textContent).toContain("Couldn't reach the server");
    expect((screen.getByLabelText('Household name') as HTMLInputElement).value).toBe('Test Home');
    expect(createButton().disabled).toBe(false);
  });

  it('CORE-02: AC4 offline shows the banner and disables both buttons', async () => {
    setOnline(false);
    await renderWelcome();
    expect(screen.getByRole('status').textContent).toBe(
      "You're offline. Connect to create or join a household.",
    );
    expect(createButton().disabled).toBe(true);
    expect(joinButton().disabled).toBe(true);
  });

  it('CORE-02: AC4 a failing profile fetch falls back to the cached name', async () => {
    localStorage.setItem('abodenestly:profile:u1', JSON.stringify(profile));
    const auth = new FakeAuthClient({ user: { id: 'u1' } });
    auth.fetchProfile = () => Promise.resolve({ ok: false, error: { code: 'network' } });
    await renderWelcome(auth);
    expect(screen.getByRole('heading', { level: 1 }).textContent).toContain('Ada');
  });

  it('CORE-02: a good profile fetch is cached for offline use', async () => {
    await renderWelcome();
    expect(JSON.parse(localStorage.getItem('abodenestly:profile:u1')!).display_name).toBe('Ada');
  });

  it('CORE-02: joining disables the button while pending', async () => {
    const api = new FakeApiClient();
    api.pending = true;
    await renderWelcome(undefined, api);
    submitJoin();
    await flush();
    expect(api.acceptCalls).toEqual(['abc123']);
    expect(joinButton().disabled).toBe(true);
    expect(joinButton().getAttribute('aria-busy')).toBe('true');
  });

  it('CORE-02: AC5 an invalid code shows the error and keeps the typed code', async () => {
    const { api } = await renderWelcome();
    api.acceptResult = { ok: false, error: { code: 'invalid_code' } };
    submitJoin('NOPE');
    await flush();
    expect(screen.getByRole('alert').textContent).toContain("isn't valid");
    expect(screen.getByLabelText('Invite code').getAttribute('aria-invalid')).toBe('true');
    expect((screen.getByLabelText('Invite code') as HTMLInputElement).value).toBe('NOPE');
  });

  it('CORE-02: join success lands on /home and remembers the household', async () => {
    const api = new FakeApiClient();
    api.acceptResult = { ok: true, value: { household_id: 'h-joined' } };
    const { router } = await renderWelcome(undefined, api);
    submitJoin();
    await flush();
    expect(router.state.location.pathname).toBe('/home');
    expect(readLastHousehold()).toBe('h-joined');
  });

  it('CORE-03: ?code= prefills the join card and does not join by itself', async () => {
    const { api } = await renderWelcome(undefined, undefined, '/welcome?code=0123456789ab');
    expect((screen.getByLabelText('Invite code') as HTMLInputElement).value).toBe('0123456789ab');
    expect(api.acceptCalls).toEqual([]);
  });

  it('CORE-03: a prefilled code is joined on tap', async () => {
    const api = new FakeApiClient();
    api.acceptResult = { ok: true, value: { household_id: 'h-joined' } };
    const { router } = await renderWelcome(undefined, api, '/welcome?code=0123456789ab');
    fireEvent.click(joinButton());
    await flush();
    expect(api.acceptCalls).toEqual(['0123456789ab']);
    expect(router.state.location.pathname).toBe('/home');
  });

  it('CORE-03: a typed code is lowercased on submit', async () => {
    const { api } = await renderWelcome();
    submitJoin('  0123ABCDEF45 ');
    await flush();
    expect(api.acceptCalls).toEqual(['0123abcdef45']);
  });
});
