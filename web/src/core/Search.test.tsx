import type { Household, Profile } from '@abodenestly/contract';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { Shell } from '../app/Shell.tsx';
import { openHouseholdDb } from '../sync/db.ts';
import { ApiProvider } from './api/ApiProvider.tsx';
import { FakeApiClient } from './api/FakeApiClient.ts';
import { AuthProvider } from './auth/AuthProvider.tsx';
import { FakeAuthClient } from './auth/FakeAuthClient.ts';
import { writeLastHousehold } from './auth/lastHousehold.ts';
import { pushRecentSearch, readRecentSearches } from './Search.ts';
import { Search } from './Search.tsx';

function setOnline(value: boolean) {
  Object.defineProperty(navigator, 'onLine', { value, configurable: true });
}
// fake-indexeddb settles on macrotasks that are not faked here; yield to them a few times.
async function settle() {
  for (let i = 0; i < 10; i++) {
    await act(async () => {
      await new Promise((r) => setImmediate(r));
    });
  }
}
async function advance(ms: number) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
}
// Once the debounce has fired, Dexie's live query still defers its read with short timers on the fake clock
// (and fake-indexeddb with real macrotasks), so alternate the two until the rows are rendered.
async function afterDebounce() {
  for (let i = 0; i < 12; i++) {
    await act(async () => {
      await new Promise((r) => setImmediate(r));
      await vi.advanceTimersByTimeAsync(1);
    });
  }
}

const house = (over: Partial<Household> = {}) =>
  ({ id: 'h1', name: 'Home One', currency: 'EUR', module_shopping: true, module_pantry: true, module_maintenance: true, ...over }) as Household;

async function seed() {
  const db = await openHouseholdDb('h1');
  const base = { household_id: 'h1', deleted_at: null };
  await db.list_item.put({ id: 'li1', list_id: 'L1', name: 'Milk', ...base });
  await db.pantry_item.put({ id: 'pi1', name: 'Milk powder', ...base });
  await db.close();
}

async function renderSearch(household = house()) {
  await seed();
  const api = new FakeApiClient();
  api.fetchHousehold = async () => ({ ok: true, value: { household, role: 'owner' } });
  api.fetchMyHouseholdsResult = { ok: true, value: [{ household, role: 'owner' }] };
  writeLastHousehold('h1');
  const auth = new FakeAuthClient({ user: { id: 'u1' } });
  auth.profile = { id: 'u1', display_name: 'Ada' } as Profile;
  const router = createMemoryRouter(
    [
      {
        element: <Shell />,
        children: [
          { path: '/search', element: <Search /> },
          { path: '/shopping/:listId', element: <p>list screen</p> },
        ],
      },
    ],
    { initialEntries: ['/search'] },
  );
  render(
    <AuthProvider client={auth}>
      <ApiProvider client={api}>
        <RouterProvider router={router} />
      </ApiProvider>
    </AuthProvider>,
  );
  await waitFor(() => expect(screen.getByRole('searchbox', { name: 'Search' })).toBeTruthy());
  return router;
}

const type = (q: string) => fireEvent.change(screen.getByRole('searchbox', { name: 'Search' }), { target: { value: q } });

beforeEach(async () => {
  setOnline(true);
  localStorage.clear();
  const { default: Dexie } = await import('dexie');
  await Dexie.delete('abodenestly-h1');
});
afterEach(() => {
  vi.useRealTimers();
  cleanup();
});

describe('CORE-09: Search screen', () => {
  it('CORE-09: AC1 empty query shows the recent searches', async () => {
    pushRecentSearch('h1', 'eggs');
    pushRecentSearch('h1', 'milk');
    await renderSearch();
    const empty = screen.getByTestId('search.empty');
    expect(within(empty).getAllByRole('button').map((b) => b.textContent)).toEqual(['milk', 'eggs']);
  });

  it('CORE-09: tapping a recent search fills the field and searches', async () => {
    pushRecentSearch('h1', 'milk');
    await renderSearch();
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    fireEvent.click(screen.getByRole('button', { name: 'milk' }));
    await advance(150);
    await afterDebounce();
    expect((screen.getByRole('searchbox', { name: 'Search' }) as HTMLInputElement).value).toBe('milk');
    expect(screen.getByTestId('search.section.shopping')).toBeTruthy();
  });

  it('CORE-09: AC2 no-results appears at 150 ms, not at 149', async () => {
    await renderSearch();
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    type('zzz');
    await advance(149);
    expect(screen.queryByTestId('search.noResults')).toBeNull();
    expect(screen.getByTestId('search.empty')).toBeTruthy();
    await advance(1);
    await afterDebounce();
    expect(screen.getByTestId('search.noResults')).toBeTruthy();
  });

  it('CORE-09: AC3 shopping and pantry sections both appear', async () => {
    await renderSearch();
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    type('milk');
    await advance(150);
    await afterDebounce();
    expect(screen.queryByTestId('search.noResults')).toBeNull();
    expect(screen.getByTestId('search.section.shopping')).toBeTruthy();
    expect(screen.getByTestId('search.section.pantry')).toBeTruthy();
    expect(screen.queryByTestId('search.section.maintenance')).toBeNull();
  });

  it('CORE-09: AC4 no Pantry section when pantry is off, though pantry rows exist locally', async () => {
    await renderSearch(house({ module_pantry: false }));
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    type('milk');
    await advance(150);
    await afterDebounce();
    expect(screen.getByTestId('search.section.shopping')).toBeTruthy();
    expect(screen.queryByTestId('search.section.pantry')).toBeNull();
  });

  it('CORE-09: AC5 offline still returns results from the local database', async () => {
    await renderSearch();
    setOnline(false);
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    type('milk');
    await advance(150);
    await afterDebounce();
    expect(screen.getAllByTestId('search.row')).toHaveLength(2);
  });

  it('CORE-09: tapping a row navigates to its path and records the recent search', async () => {
    const router = await renderSearch();
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    type('milk');
    await advance(150);
    await afterDebounce();
    const link = within(screen.getByTestId('search.section.shopping')).getByTestId('search.row');
    expect(link.textContent).toContain('List item');
    fireEvent.click(link);
    await settle();
    expect(router.state.location.pathname).toBe('/shopping/L1');
    expect(router.state.location.search).toBe('?item=li1');
    expect(readRecentSearches('h1')).toEqual(['milk']);
  });

  it('CORE-09: a term that matches never flashes no-results while typing a new one', async () => {
    await renderSearch();
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    type('milk');
    await advance(150);
    await afterDebounce();
    type('milk p');
    await advance(150);
    expect(screen.queryByTestId('search.noResults')).toBeNull();
    expect(screen.getAllByTestId('search.row')).toHaveLength(1);
  });

  it('CORE-09: the TopBar magnifier links to /search', async () => {
    await renderSearch();
    expect(screen.getAllByRole('link', { name: 'Search' })[0]!.getAttribute('href')).toBe('/search');
  });
});
