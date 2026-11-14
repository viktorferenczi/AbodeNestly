import type { Role } from '@abodenestly/contract';
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { ApiProvider } from './api/ApiProvider.tsx';
import type { MemberRow } from './api/apiClient.ts';
import { FakeApiClient } from './api/FakeApiClient.ts';
import { AuthProvider } from './auth/AuthProvider.tsx';
import { FakeAuthClient } from './auth/FakeAuthClient.ts';
import { readLastHousehold } from './auth/lastHousehold.ts';
import { Members } from './Members.tsx';
import { readMembersCache, writeMembersCache } from './membersCache.ts';

function setOnline(value: boolean) {
  Object.defineProperty(navigator, 'onLine', { value, configurable: true });
}

async function flush() {
  await act(async () => {
    await new Promise((r) => setTimeout(r, 0));
  });
}

function row(userId: string, role: Role, displayName: string): MemberRow {
  return {
    member: { household_id: 'h1', user_id: userId, role, joined_at: '2026-01-01T00:00:00Z', left_at: null } as MemberRow['member'],
    profile: { display_name: displayName, avatar_path: null },
  };
}

const ME_OWNER = row('me', 'owner', 'Ada Lovelace');
const ANNA = row('anna', 'member', 'Anna Kovacs');
const BEN = row('ben', 'owner', 'Ben');
const ROW_OWNER_O = row('o', 'owner', 'Olga');

async function renderMembers(api: FakeApiClient, meId = 'me') {
  const router = createMemoryRouter(
    [
      { path: '/household/members', element: <Members /> },
      { path: '/', element: <p>root</p> },
    ],
    { initialEntries: ['/household/members'] },
  );
  render(
    <AuthProvider client={new FakeAuthClient({ user: { id: meId } })}>
      <ApiProvider client={api}>
        <RouterProvider router={router} />
      </ApiProvider>
    </AuthProvider>,
  );
  await flush();
  return router;
}

function apiWith(rows: MemberRow[]) {
  const api = new FakeApiClient();
  api.fetchMembersResult = { ok: true, value: rows };
  return api;
}

const moreButton = (name: string) => screen.queryByRole('button', { name: `More actions for ${name}` });
const leaveButton = () => screen.getByRole('button', { name: 'Leave household' }) as HTMLButtonElement;

async function advance(ms: number) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
}

beforeEach(() => {
  setOnline(true);
  localStorage.clear();
  localStorage.setItem('abodenestly:lastHousehold', 'h1');
});

afterEach(() => {
  vi.useRealTimers();
  cleanup();
  setOnline(true);
});

describe('CORE-04: Members screen', () => {
  it('CORE-04: AC1 owner sees rows with initials, names, roles and a You badge', async () => {
    const api = apiWith([ANNA, ME_OWNER, BEN]);
    await renderMembers(api);
    expect(api.fetchMembersCalls).toEqual(['h1']);
    const items = screen.getAllByRole('listitem');
    // Owners first, by name; then members.
    expect(items.map((li) => li.textContent)).toEqual([
      expect.stringContaining('Ada Lovelace'),
      expect.stringContaining('Ben'),
      expect.stringContaining('Anna Kovacs'),
    ]);
    expect(within(items[0]!).getByText('AL')).toBeTruthy();
    expect(within(items[0]!).getByText('Owner')).toBeTruthy();
    expect(within(items[0]!).getByText('You')).toBeTruthy();
    expect(within(items[2]!).getByText('AK')).toBeTruthy();
    expect(within(items[2]!).getByText('Member')).toBeTruthy();
    expect(screen.getAllByText('You')).toHaveLength(1);
    expect(moreButton('Ada Lovelace')).toBeNull();
    expect(moreButton('Anna Kovacs')).toBeTruthy();
    expect(moreButton('Ben')).toBeTruthy();
    // The live list is cached for offline reads.
    expect(readMembersCache('h1')).toHaveLength(3);
  });

  it('CORE-04: a profile that is hidden shows the "Member" fallback name', async () => {
    const hidden: MemberRow = { ...ANNA, profile: null };
    await renderMembers(apiWith([ME_OWNER, hidden]));
    expect(moreButton('Member')).toBeTruthy();
  });

  it('CORE-04: AC2 remove shows the toast, calls DELETE only after 5 s, and the row stays gone', async () => {
    const api = apiWith([ME_OWNER, ANNA]);
    await renderMembers(api);
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    fireEvent.click(moreButton('Anna Kovacs')!);
    fireEvent.click(screen.getByRole('button', { name: 'Remove' }));
    const toast = screen.getByRole('status');
    expect(toast.textContent).toContain('Removed Anna Kovacs');
    expect(screen.getByRole('button', { name: 'Undo' })).toBeTruthy();
    expect(screen.getAllByRole('listitem')).toHaveLength(1);
    await advance(4999);
    expect(api.removeCalls).toEqual([]);
    await advance(1);
    expect(api.removeCalls).toEqual([{ householdId: 'h1', userId: 'anna' }]);
    expect(screen.queryByRole('status')).toBeNull();
    expect(screen.getAllByRole('listitem')).toHaveLength(1);
  });

  it('CORE-04: AC3 Undo within 5 s restores the row and never calls DELETE', async () => {
    const api = apiWith([ME_OWNER, ANNA]);
    await renderMembers(api);
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    fireEvent.click(moreButton('Anna Kovacs')!);
    fireEvent.click(screen.getByRole('button', { name: 'Remove' }));
    await advance(4999);
    fireEvent.click(screen.getByRole('button', { name: 'Undo' }));
    await advance(10_000);
    expect(api.removeCalls).toEqual([]);
    expect(screen.getAllByRole('listitem')).toHaveLength(2);
    expect(screen.queryByRole('status')).toBeNull();
  });

  it('CORE-04: a failed DELETE restores the row with a message', async () => {
    const api = apiWith([ME_OWNER, ANNA]);
    api.removeResult = { ok: false, error: { code: 'network' } };
    await renderMembers(api);
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    fireEvent.click(moreButton('Anna Kovacs')!);
    fireEvent.click(screen.getByRole('button', { name: 'Remove' }));
    await advance(5000);
    expect(screen.getAllByRole('listitem')).toHaveLength(2);
    expect(screen.getByRole('alert').textContent).toBe("Couldn't remove Anna Kovacs. Try again.");
  });

  it('CORE-04: a member who is already gone (404) is dropped without an error', async () => {
    const api = apiWith([ME_OWNER, ANNA]);
    api.removeResult = { ok: false, error: { code: 'not_found' } };
    await renderMembers(api);
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    fireEvent.click(moreButton('Anna Kovacs')!);
    fireEvent.click(screen.getByRole('button', { name: 'Remove' }));
    await advance(5000);
    expect(screen.getAllByRole('listitem')).toHaveLength(1);
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('CORE-04: unmounting during the undo window drops the removal', async () => {
    const api = apiWith([ME_OWNER, ANNA]);
    await renderMembers(api);
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    fireEvent.click(moreButton('Anna Kovacs')!);
    fireEvent.click(screen.getByRole('button', { name: 'Remove' }));
    cleanup();
    await advance(10_000);
    expect(api.removeCalls).toEqual([]);
  });

  it('CORE-04: make owner is immediate, then the list is refetched; an owner target has no such action', async () => {
    const api = apiWith([ME_OWNER, ANNA, BEN]);
    await renderMembers(api);
    fireEvent.click(moreButton('Ben')!);
    expect(screen.queryByRole('button', { name: 'Make owner' })).toBeNull();
    fireEvent.click(moreButton('Anna Kovacs')!);
    fireEvent.click(screen.getByRole('button', { name: 'Make owner' }));
    await flush();
    expect(api.makeOwnerCalls).toEqual([{ householdId: 'h1', userId: 'anna' }]);
    expect(api.fetchMembersCalls).toEqual(['h1', 'h1']);
  });

  it('CORE-04: a failed make owner shows a message', async () => {
    const api = apiWith([ME_OWNER, ANNA]);
    api.makeOwnerResult = { ok: false, error: { code: 'unknown' } };
    await renderMembers(api);
    fireEvent.click(moreButton('Anna Kovacs')!);
    fireEvent.click(screen.getByRole('button', { name: 'Make owner' }));
    await flush();
    expect(screen.getByRole('alert').textContent).toBe("Couldn't make Anna Kovacs an owner. Try again.");
  });

  it('CORE-04: AC5 a member has no overflow actions; Leave shows the toast then DELETEs their own id', async () => {
    const api = apiWith([ROW_OWNER_O, row('me', 'member', 'Mia')]);
    const router = await renderMembers(api);
    expect(screen.queryAllByRole('button', { name: /^More actions/ })).toHaveLength(0);
    expect(leaveButton().disabled).toBe(false);
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    fireEvent.click(leaveButton());
    expect(screen.getByRole('status').textContent).toContain('Leaving household');
    await advance(4999);
    expect(api.removeCalls).toEqual([]);
    await advance(1);
    expect(api.removeCalls).toEqual([{ householdId: 'h1', userId: 'me' }]);
    vi.useRealTimers();
    await flush();
    expect(readLastHousehold()).toBeNull();
    expect(router.state.location.pathname).toBe('/');
  });

  it('CORE-04: Undo on leave cancels it and keeps the household', async () => {
    const api = apiWith([ROW_OWNER_O, row('me', 'member', 'Mia')]);
    await renderMembers(api);
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    fireEvent.click(leaveButton());
    fireEvent.click(screen.getByRole('button', { name: 'Undo' }));
    await advance(10_000);
    expect(api.removeCalls).toEqual([]);
    expect(readLastHousehold()).toBe('h1');
  });

  it('CORE-04: AC4 the last owner is blocked before any call', async () => {
    const api = apiWith([ME_OWNER, ANNA]);
    await renderMembers(api);
    fireEvent.click(leaveButton());
    expect(screen.getByRole('alert').textContent).toBe(
      "You're the last owner. Make someone else an owner before leaving.",
    );
    expect(screen.queryByRole('status')).toBeNull();
    expect(api.removeCalls).toEqual([]);
  });

  it('CORE-04: a server last_owner on leave restores the row and shows the same alert', async () => {
    const api = apiWith([ME_OWNER, BEN]);
    api.removeResult = { ok: false, error: { code: 'last_owner' } };
    await renderMembers(api);
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    fireEvent.click(leaveButton());
    await advance(5000);
    expect(screen.getByRole('alert').textContent).toContain("You're the last owner");
    expect(screen.getAllByRole('listitem')).toHaveLength(2);
    expect(readLastHousehold()).toBe('h1');
  });

  it('CORE-04: AC6 offline shows the cached list, the banner, and disabled actions', async () => {
    writeMembersCache('h1', [ME_OWNER, ANNA]);
    setOnline(false);
    const api = apiWith([]);
    await renderMembers(api);
    await act(async () => {
      window.dispatchEvent(new Event('offline'));
    });
    expect(api.fetchMembersCalls).toEqual([]);
    expect(screen.getByRole('status').textContent).toBe("You're offline. Connect to change household settings.");
    expect(screen.getAllByRole('listitem')).toHaveLength(2);
    expect((moreButton('Anna Kovacs') as HTMLButtonElement).disabled).toBe(true);
    expect(leaveButton().disabled).toBe(true);
    expect((screen.getByRole('button', { name: 'Invite' }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('CORE-04: offline with no cache says to connect', async () => {
    setOnline(false);
    await renderMembers(apiWith([]));
    expect(screen.getByText('Connect to see the members.')).toBeTruthy();
  });

  it('CORE-04: a failed fetch falls back to the cache, then Try again recovers when there is none', async () => {
    writeMembersCache('h1', [ME_OWNER, ANNA]);
    const api = new FakeApiClient();
    api.fetchMembersResult = { ok: false, error: { code: 'network' } };
    await renderMembers(api);
    expect(screen.getAllByRole('listitem')).toHaveLength(2);
    cleanup();

    localStorage.removeItem('abodenestly:members:h1');
    const api2 = new FakeApiClient();
    api2.fetchMembersResult = { ok: false, error: { code: 'network' } };
    await renderMembers(api2);
    expect(screen.getByRole('alert').textContent).toBe("Couldn't load members. Try again.");
    api2.fetchMembersResult = { ok: true, value: [ME_OWNER] };
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    await flush();
    expect(screen.getAllByRole('listitem')).toHaveLength(1);
  });

  it('CORE-04: the Invite button still opens the invite sheet', async () => {
    const api = apiWith([ME_OWNER]);
    await renderMembers(api);
    fireEvent.click(screen.getByRole('button', { name: 'Invite' }));
    await flush();
    expect(screen.getByRole('dialog', { name: 'Invite to household' })).toBeTruthy();
  });
});
