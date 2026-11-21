import type { Activity, Household, Profile } from '@abodenestly/contract';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { Shell } from '../app/Shell.tsx';
import { FakeSync } from '../sync/FakeSync.ts';
import { openHouseholdDb } from '../sync/db.ts';
import { SyncProvider } from '../sync/SyncEngine.ts';
import { ApiProvider } from './api/ApiProvider.tsx';
import { FakeApiClient } from './api/FakeApiClient.ts';
import { AuthProvider } from './auth/AuthProvider.tsx';
import { FakeAuthClient } from './auth/FakeAuthClient.ts';
import { writeLastHousehold } from './auth/lastHousehold.ts';
import { HomeFeed } from './HomeFeed.tsx';

// The household resolves before its database opens; a test can widen that gap to expose ordering assumptions.
let dbOpenDelayMs = 0;
vi.mock('../sync/db.ts', async (importOriginal) => {
  const original = await importOriginal<typeof import('../sync/db.ts')>();
  return {
    ...original,
    openHouseholdDb: async (id: string) => {
      const db = await original.openHouseholdDb(id);
      if (dbOpenDelayMs) await new Promise((r) => setTimeout(r, dbOpenDelayMs));
      return db;
    },
  };
});

function setOnline(value: boolean) {
  Object.defineProperty(navigator, 'onLine', { value, configurable: true });
}
async function flush() {
  await act(async () => {
    await new Promise((r) => setTimeout(r, 20));
  });
}

const DAY = 86_400_000;
const ago = (ms: number) => new Date(Date.now() - ms).toISOString();
let seq = 0;
const row = (summary: string, msAgo: number, over: Partial<Activity> = {}): Activity => ({
  id: `a${String(++seq).padStart(4, '0')}`,
  household_id: 'h1',
  actor_id: null,
  module: 'shopping',
  entity: 'list_item',
  entity_id: 'e1',
  action: 'checked',
  summary,
  created_at: ago(msAgo),
  updated_at: ago(msAgo),
  deleted_at: null,
  sync_seq: seq,
  ...over,
});

const house = (over: Partial<Household> = {}) =>
  ({ id: 'h1', name: 'Home One', currency: 'EUR', module_shopping: true, module_pantry: true, module_maintenance: true, ...over }) as Household;

async function seed(rows: Activity[]) {
  const db = await openHouseholdDb('h1');
  await db.activity.bulkPut(rows);
  db.close();
}

async function renderHome(opts: { household?: Household; rows?: Activity[]; serverRows?: Activity[]; dbOpenDelayMs?: number } = {}) {
  await seed(opts.rows ?? []);
  dbOpenDelayMs = opts.dbOpenDelayMs ?? 0;
  const household = opts.household ?? house();
  const api = new FakeApiClient();
  // Shell kicks a real activity pull that replaces the cached 30-day window with the server page. The fake server
  // must serve the seeded rows, or that pull races the live query and sometimes wipes them (the flaky AC2/AC3).
  api.fetchActivityResult = { ok: true, value: [...(opts.rows ?? []), ...(opts.serverRows ?? [])] };
  api.fetchHousehold = async () => ({ ok: true, value: { household, role: 'owner' } });
  api.fetchMyHouseholdsResult = { ok: true, value: [{ household, role: 'owner' }] };
  writeLastHousehold('h1');
  const auth = new FakeAuthClient({ user: { id: 'u1' } });
  auth.profile = { id: 'u1', display_name: 'Ada' } as Profile;
  const sync = new FakeSync();
  const router = createMemoryRouter(
    [
      {
        element: <Shell />,
        children: [
          {
            path: '/home',
            element: (
              <SyncProvider engine={sync}>
                <HomeFeed />
              </SyncProvider>
            ),
          },
          { path: '/household', element: <p>household</p> },
          { path: '/household/members', element: <p>members</p> },
          { path: '/pantry', element: <p>pantry screen</p> },
        ],
      },
    ],
    { initialEntries: ['/home'] },
  );
  render(
    <AuthProvider client={auth}>
      <ApiProvider client={api}>
        <RouterProvider router={router} />
      </ApiProvider>
    </AuthProvider>,
  );
  // The household loads, then its database, then the live query: each step needs its own act scope.
  await waitFor(() => expect(screen.getByRole('heading', { name: 'Activity' })).toBeTruthy());
  return { sync, router, api };
}

function pull(distance: number) {
  const area = screen.getByRole('status').parentElement!;
  fireEvent.pointerDown(area, { clientY: 10 });
  fireEvent.pointerMove(area, { clientY: 10 + distance });
  fireEvent.pointerUp(area, { clientY: 10 + distance });
}

beforeEach(async () => {
  setOnline(true);
  localStorage.clear();
  seq = 0;
  const { default: Dexie } = await import('dexie');
  await Dexie.delete('abodenestly-h1');
});
afterEach(() => {
  cleanup();
  dbOpenDelayMs = 0;
});

describe('CORE-08: Home activity feed', () => {
  it('CORE-08: AC1 no activity shows the empty-activity state with an invite button', async () => {
    await renderHome();
    const empty = screen.getByTestId('home.empty');
    expect(within(empty).getByText('Things your household does show up here.')).toBeTruthy();
    expect(within(empty).getByRole('link', { name: 'Invite a member' }).getAttribute('href')).toBe('/household/members');
    expect(screen.queryByTestId('home.feed')).toBeNull();
  });

  it('CORE-08: AC2 shows sentences newest first, at most 20, from the last 30 days only', async () => {
    const rows = [
      row('Anna checked off Milk', 5_000),
      row('Ben added Eggs', 60_000),
      row('Too old', 31 * DAY),
      row('Deleted', 1_000, { deleted_at: ago(500) }),
      ...Array.from({ length: 22 }, (_, i) => row(`Filler ${i}`, 120_000 + i * 1000)),
    ];
    await renderHome({ rows });
    const items = within(screen.getByTestId('home.feed')).getAllByRole('listitem');
    expect(items).toHaveLength(20);
    expect(items[0]!.textContent).toContain('Anna checked off Milk');
    expect(items[1]!.textContent).toContain('Ben added Eggs');
    expect(screen.queryByText('Too old')).toBeNull();
    expect(screen.queryByText('Deleted')).toBeNull();
    expect(screen.queryByTestId('home.empty')).toBeNull();
  });

  it('CORE-08: a slow local database shows the cached feed, never the empty state first', async () => {
    await renderHome({ rows: [row('Anna checked off Milk', 5_000)], dbOpenDelayMs: 150 });
    expect(within(screen.getByTestId('home.feed')).getByText('Anna checked off Milk')).toBeTruthy();
    expect(screen.queryByTestId('home.empty')).toBeNull();
  });

  it('CORE-08: AC3 every module off shows the settings prompt and the feed still lists core events', async () => {
    const household = house({ module_shopping: false, module_pantry: false, module_maintenance: false });
    await renderHome({ household, rows: [row('Ben joined the household', 1000, { module: 'core', action: 'joined' })] });
    const prompt = screen.getByTestId('home.allOff');
    expect(within(prompt).getByText('Every module is off. Turn one on in Settings.')).toBeTruthy();
    expect(within(prompt).getByRole('link', { name: 'Settings' }).getAttribute('href')).toBe('/household');
    expect(within(screen.getByTestId('home.feed')).getByText('Ben joined the household')).toBeTruthy();
    expect(screen.queryByTestId('home.today.shopping')).toBeNull();
  });

  it('CORE-08: a module that is off hides its Today card, the others open their module', async () => {
    const { router } = await renderHome({ household: house({ module_shopping: false }) });
    expect(screen.queryByTestId('home.today.shopping')).toBeNull();
    expect(screen.getByTestId('home.today.maintenance')).toBeTruthy();
    fireEvent.click(screen.getByTestId('home.today.pantry'));
    await flush();
    expect(router.state.location.pathname).toBe('/pantry');
  });

  it('CORE-08: footer links go to Members, Settings, Recently deleted and Profile', async () => {
    await renderHome();
    const footer = screen.getByRole('navigation', { name: 'Household' });
    expect(within(footer).getByRole('link', { name: 'Members' }).getAttribute('href')).toBe('/household/members');
    expect(within(footer).getByRole('link', { name: 'Settings' }).getAttribute('href')).toBe('/household');
    expect(within(footer).getByRole('link', { name: 'Recently deleted' }).getAttribute('href')).toBe('/deleted');
    expect(within(footer).getByRole('link', { name: 'Profile' }).getAttribute('href')).toBe('/profile');
  });

  it('CORE-08: AC4 releasing a pull past the threshold calls kick(); a short pull does not', async () => {
    const { sync } = await renderHome();
    pull(10);
    await flush();
    expect(sync.kicks).toBe(0);
    pull(200);
    await flush();
    expect(sync.kicks).toBe(1);
  });

  it('CORE-08: AC5 offline the cached feed is shown and a pull still only asks the engine', async () => {
    setOnline(false);
    const { sync } = await renderHome({ rows: [row('Anna checked off Milk', 1000)] });
    expect(within(screen.getByTestId('home.feed')).getByText('Anna checked off Milk')).toBeTruthy();
    pull(200);
    await flush();
    expect(sync.kicks).toBe(1);
    expect(screen.getByText('Anna checked off Milk')).toBeTruthy();
  });

  it('CORE-08: a row pulled while the screen is open appears without a reload', async () => {
    const cleo = row('Cleo joined the household', 100, { module: 'core' });
    // Also on the fake server, so the mount-time pull cannot wipe it whichever side of the put it lands.
    await renderHome({ serverRows: [cleo] });
    const db = await openHouseholdDb('h1');
    await db.activity.put(cleo);
    db.close();
    await flush();
    expect(screen.getByText('Cleo joined the household')).toBeTruthy();
  });
});
