import type { Household, Profile, Role } from '@abodenestly/contract';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createMemoryRouter, RouterProvider, useLocation } from 'react-router';
import { Shell } from '../app/Shell.tsx';
import { openHouseholdDb as realOpen } from '../sync/db.ts';
import { ApiProvider } from './api/ApiProvider.tsx';
import { FakeApiClient } from './api/FakeApiClient.ts';
import { AuthProvider } from './auth/AuthProvider.tsx';
import { FakeAuthClient } from './auth/FakeAuthClient.ts';
import { readLastHousehold, writeLastHousehold } from './auth/lastHousehold.ts';
import { readHouseholds, writeHouseholds } from './householdsCache.ts';

// Lets a test hold the target database "opening" to observe the switching state (AC3).
const gate = vi.hoisted(() => ({ hold: null as Promise<void> | null }));
vi.mock('../sync/db.ts', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../sync/db.ts')>();
  return {
    ...actual,
    openHouseholdDb: async (id: string) => {
      const db = await actual.openHouseholdDb(id);
      if (gate.hold && id === 'h2') await gate.hold;
      return db;
    },
  };
});
function setOnline(value: boolean) {
  Object.defineProperty(navigator, 'onLine', { value, configurable: true });
}
const house = (id: string, name: string, over: Partial<Household> = {}) =>
  ({ id, name, currency: 'EUR', module_shopping: true, module_pantry: true, module_maintenance: true, ...over }) as Household;
const H1 = house('h1', 'Home One');
const H2 = house('h2', 'Home Two', { module_pantry: false });
const entries = (): Array<{ household: Household; role: Role }> => [
  { household: H1, role: 'owner' },
  { household: H2, role: 'member' },
];

function Where() {
  const l = useLocation();
  return <p data-testid="where">{l.pathname}</p>;
}

async function renderApp(opts: { list?: Array<{ household: Household; role: Role }>; path?: string; fetchFails?: boolean } = {}) {
  const list = opts.list ?? entries();
  const api = new FakeApiClient();
  // The household read answers for whichever id is asked, like the real table does.
  api.fetchHousehold = async (id) => ({ ok: true, value: list.find((e) => e.household.id === id) ?? null });
  api.fetchMyHouseholdsResult = opts.fetchFails ? { ok: false, error: { code: 'network' } } : { ok: true, value: list };
  writeLastHousehold('h1');
  const auth = new FakeAuthClient({ user: { id: 'u1' } });
  auth.profile = { id: 'u1', display_name: 'Ada' } as Profile;
  const router = createMemoryRouter(
    [
      {
        element: (
          <>
            <Shell />
            <Where />
          </>
        ),
        children: ['/shopping', '/pantry', '/home', '/household', '/welcome'].map((path) => ({ path, element: <p>{path}</p> })),
      },
    ],
    { initialEntries: [opts.path ?? '/shopping'] },
  );
  render(
    <AuthProvider client={auth}>
      <ApiProvider client={api}>
        <RouterProvider router={router} />
      </ApiProvider>
    </AuthProvider>,
  );
  // The title shows the household name once it has loaded.
  await waitFor(() => expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Home One ▾'));
  return { api, router };
}

const openSheet = async () => {
  fireEvent.click(screen.getByRole('button', { name: /▾/ }));
  return screen.findByRole('dialog', { name: 'Households' });
};
const where = () => screen.getByTestId('where').textContent;

beforeEach(() => {
  setOnline(true);
  localStorage.clear();
  gate.hold = null;
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe('CORE-07: switcher sheet', () => {
  it('CORE-07: AC1 one household still opens the sheet and offers create or join', async () => {
    await renderApp({ list: [{ household: H1, role: 'owner' }] });
    const sheet = await openSheet();
    expect(within(sheet).getByText('Home One')).toBeTruthy();
    fireEvent.click(within(sheet).getByRole('button', { name: 'Create or join another' }));
    await waitFor(() => expect(where()).toBe('/welcome'));
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('CORE-07: many households list names, roles, the checked one and the pending count', async () => {
    const seeded = await realOpen('h2');
    await seeded.outbox.add({ table: 'shopping_list', op: 'upsert', payload: {} });
    seeded.close();
    await renderApp();
    const sheet = await openSheet();
    await within(sheet).findByText(/1 to sync/);
    const rows = within(sheet).getAllByRole('button').filter((b) => b.textContent !== 'Close');
    expect(rows).toHaveLength(3);
    expect(rows[0]!.getAttribute('aria-current')).toBe('true');
    expect(rows[0]!.textContent).toContain('Owner');
    expect(rows[0]!.textContent).not.toContain('to sync');
    expect(rows[1]!.getAttribute('aria-current')).toBeNull();
    expect(rows[1]!.textContent).toContain('Member');
    expect(rows[1]!.textContent).toContain('1 to sync');
  });

  it('CORE-07: AC2 tapping another household switches, keeps the tab and renames the title', async () => {
    await renderApp({ path: '/shopping' });
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Home One ▾');
    const sheet = await openSheet();
    fireEvent.click(await within(sheet).findByRole('button', { name: /Home Two/ }));
    await waitFor(() => expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Home Two ▾'));
    expect(readLastHousehold()).toBe('h2');
    expect(where()).toBe('/shopping');
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('CORE-07: AC2 a tab whose module is off in the target falls back to its start tab', async () => {
    await renderApp({ path: '/pantry' });
    fireEvent.click(await within(await openSheet()).findByRole('button', { name: /Home Two/ }));
    // Pantry is off in Home Two, so the switch lands on the start tab.
    await waitFor(() => expect(readLastHousehold()).toBe('h2'));
    await waitFor(() => expect(where()).toBe('/shopping'));
  });

  it('CORE-07: AC3 shows the switching state until the target database is open', async () => {
    let release!: () => void;
    gate.hold = new Promise<void>((r) => (release = r));
    await renderApp();
    const sheet = await openSheet();
    fireEvent.click(await within(sheet).findByRole('button', { name: /Home Two/ }));
    const status = await screen.findByRole('status');
    expect(status.textContent).toBe('Switching to Home Two…');
    expect(status.getAttribute('aria-busy')).toBe('true');
    expect(screen.getByRole('dialog')).toBeTruthy();
    release();
    await waitFor(() => expect(screen.queryByRole('status')).toBeNull());
    expect(where()).toBe('/shopping');
  });

  it('CORE-07: long-press opens household settings after switching to that household', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    await renderApp();
    const sheet = await openSheet();
    const row = await within(sheet).findByRole('button', { name: /Home Two/ });
    fireEvent.pointerDown(row);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(600);
    });
    await waitFor(() => expect(where()).toBe('/household'));
    expect(readLastHousehold()).toBe('h2');
  });

  it('CORE-07: a pointer move cancels the long-press and the tap still switches', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    await renderApp();
    const row = await within(await openSheet()).findByRole('button', { name: /Home Two/ });
    fireEvent.pointerDown(row, { clientX: 10, clientY: 10 });
    fireEvent.pointerMove(row, { clientX: 10, clientY: 40 });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(600);
    });
    expect(where()).toBe('/shopping');
    fireEvent.click(row);
    // fake-indexeddb schedules with setImmediate, which the fake timers hold back.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(100);
    });
    expect(readLastHousehold()).toBe('h2');
    expect(where()).toBe('/shopping');
  });

  it('CORE-07: a pixel of drift during the hold still counts as a long-press', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    await renderApp();
    const row = await within(await openSheet()).findByRole('button', { name: /Home Two/ });
    fireEvent.pointerDown(row, { clientX: 10, clientY: 10 });
    fireEvent.pointerMove(row, { clientX: 12, clientY: 11 });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(600);
    });
    expect(where()).toBe('/household');
  });

  it('CORE-07: a touch hold that raises both the timer and contextmenu switches once', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const { router } = await renderApp();
    const visited: string[] = [];
    router.subscribe((st) => visited.push(st.location.pathname));
    const row = await within(await openSheet()).findByRole('button', { name: /Home Two/ });
    fireEvent.pointerDown(row);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(600);
    });
    fireEvent.contextMenu(row);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(100);
    });
    expect(visited.filter((p) => p === '/shopping')).toHaveLength(1);
    expect(where()).toBe('/household');
  });

  it('CORE-07: the title button announces the dialog and its open state', async () => {
    await renderApp();
    const button = screen.getByRole('button', { name: /▾/ });
    expect(button.getAttribute('aria-haspopup')).toBe('dialog');
    expect(button.getAttribute('aria-expanded')).toBe('false');
    await openSheet();
    expect(button.getAttribute('aria-expanded')).toBe('true');
  });

  it('CORE-07: a household that was loaded stays in the offline list after the sheet was never online', async () => {
    await renderApp({ list: [{ household: H1, role: 'owner' }], fetchFails: true });
    expect(readHouseholds('u1').map((e) => e.household.id)).toEqual(['h1']);
  });

  it('CORE-07: the context-menu key does the same as a long-press', async () => {
    await renderApp();
    fireEvent.contextMenu(await within(await openSheet()).findByRole('button', { name: /Home Two/ }));
    await waitFor(() => expect(where()).toBe('/household'));
    expect(readLastHousehold()).toBe('h2');
  });

  it('CORE-07: AC5 offline with a failing API the switch completes from the cache', async () => {
    writeHouseholds('u1', entries());
    setOnline(false);
    const { api } = await renderApp({ fetchFails: true });
    api.fetchHouseholdResult = { ok: false, error: { code: 'network' } };
    const sheet = await openSheet();
    expect(api.fetchMyHouseholdsCalls).toEqual([]);
    fireEvent.click(await within(sheet).findByRole('button', { name: /Home Two/ }));
    await waitFor(() => expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Home Two ▾'));
    expect(readLastHousehold()).toBe('h2');
    expect(where()).toBe('/shopping');
  });
});
