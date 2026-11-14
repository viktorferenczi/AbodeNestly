import type { Role } from '@abodenestly/contract';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { MemberRow } from './api/apiClient.ts';
import {
  canRemove,
  createUndoScheduler,
  initialMembersState,
  initials,
  isLastOwner,
  leaveDecision,
  membersReducer,
  sortMembers,
  visibleRows,
} from './Members.ts';
import { parseMembersCache } from './membersCache.ts';

function row(userId: string, role: Role, displayName: string | null): MemberRow {
  return {
    member: { household_id: 'h1', user_id: userId, role, joined_at: '2026-01-01T00:00:00Z', left_at: null } as MemberRow['member'],
    profile: displayName === null ? null : { display_name: displayName, avatar_path: null },
  };
}

const ids = (rows: MemberRow[]) => rows.map((r) => r.member.user_id);

describe('CORE-04: sortMembers', () => {
  it('owners first, then by display name', () => {
    const rows = [row('c', 'member', 'Zoe'), row('b', 'owner', 'Mia'), row('a', 'member', 'Anna'), row('d', 'owner', 'Bea')];
    expect(ids(sortMembers(rows))).toEqual(['d', 'b', 'a', 'c']);
  });
  it('a hidden profile sorts under the "Member" fallback and the input is not mutated', () => {
    const rows = [row('x', 'member', null), row('y', 'member', 'Anna')];
    expect(ids(sortMembers(rows))).toEqual(['y', 'x']);
    expect(ids(rows)).toEqual(['x', 'y']);
  });
});

describe('CORE-04: initials', () => {
  it.each([
    ['Anna Kovacs', 'AK'],
    ['anna', 'A'],
    ['  Mary  Jane  Watson ', 'MW'],
    ['', '?'],
    [undefined, '?'],
  ])('%j -> %s', (name, expected) => {
    expect(initials(name)).toBe(expected);
  });
});

describe('CORE-04: canRemove', () => {
  const target = row('t', 'member', 'T');
  it.each([
    ['owner', target, 'me', true],
    ['member', target, 'me', false],
    [null, target, 'me', false],
    ['owner', row('me', 'owner', 'Me'), 'me', false],
  ] as const)('viewer %s -> %s', (viewer, t, meId, expected) => {
    expect(canRemove(viewer, t, meId)).toBe(expected);
  });
});

describe('CORE-04: AC4 leaveDecision', () => {
  const me = row('me', 'owner', 'Me');
  it('blocks the only owner before any call', () => {
    const rows = [me, row('b', 'member', 'B')];
    expect(isLastOwner(rows, 'me')).toBe(true);
    expect(leaveDecision(rows, 'me')).toEqual({ ok: false, reason: 'last_owner' });
  });
  it('blocks a sole owner with no one else in the household', () => {
    expect(leaveDecision([me], 'me')).toEqual({ ok: false, reason: 'last_owner' });
  });
  it('allows an owner when a second owner exists', () => {
    expect(leaveDecision([me, row('b', 'owner', 'B')], 'me')).toEqual({ ok: true });
  });
  it('allows a plain member', () => {
    expect(leaveDecision([row('o', 'owner', 'O'), row('me', 'member', 'Me')], 'me')).toEqual({ ok: true });
  });
});

describe('CORE-04: undo scheduler (AC3)', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('undo within 5 s means the DELETE never runs', () => {
    const run = vi.fn();
    const handle = createUndoScheduler().schedule('removal', run);
    vi.advanceTimersByTime(4999);
    handle.undo();
    vi.advanceTimersByTime(10_000);
    expect(run).not.toHaveBeenCalled();
  });

  it('without undo it runs once after 5000 ms', () => {
    const run = vi.fn();
    createUndoScheduler().schedule('removal', run);
    vi.advanceTimersByTime(4999);
    expect(run).not.toHaveBeenCalled();
    vi.advanceTimersByTime(1);
    expect(run).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(10_000);
    expect(run).toHaveBeenCalledTimes(1);
  });

  it('scheduling the same key again replaces the first; cancelAll drops the rest', () => {
    const first = vi.fn();
    const second = vi.fn();
    const scheduler = createUndoScheduler();
    scheduler.schedule('k', first);
    scheduler.schedule('k', second);
    vi.advanceTimersByTime(5000);
    expect(first).not.toHaveBeenCalled();
    expect(second).toHaveBeenCalledTimes(1);
    const third = vi.fn();
    scheduler.schedule('k', third);
    scheduler.cancelAll();
    vi.advanceTimersByTime(5000);
    expect(third).not.toHaveBeenCalled();
  });

  it('honours injected timers and delay', () => {
    const calls: number[] = [];
    const scheduler = createUndoScheduler({
      delayMs: 123,
      setTimeout: (_fn, ms) => (calls.push(ms), 1),
      clearTimeout: () => {},
    });
    scheduler.schedule('k', () => {});
    expect(calls).toEqual([123]);
  });
});

describe('CORE-04: membersReducer', () => {
  const rows = [row('me', 'owner', 'Me'), row('b', 'member', 'B')];
  const ready = membersReducer(initialMembersState, { type: 'loaded', rows, source: 'live' });

  it('hides the pending row, restores it on undo', () => {
    const pending = membersReducer(ready, { type: 'removeStarted', userId: 'b' });
    expect(ids(visibleRows(pending))).toEqual(['me']);
    expect(ids(visibleRows(membersReducer(pending, { type: 'removeUndone' })))).toEqual(['me', 'b']);
  });
  it('a failed DELETE restores the row with a message', () => {
    const pending = membersReducer(ready, { type: 'removeStarted', userId: 'b' });
    const failed = membersReducer(pending, { type: 'removeFailed', message: 'nope' });
    expect(ids(visibleRows(failed))).toEqual(['me', 'b']);
    expect(failed.message).toBe('nope');
  });
  it('a finished DELETE drops the row for good', () => {
    const pending = membersReducer(ready, { type: 'removeStarted', userId: 'b' });
    const done = membersReducer(pending, { type: 'removeDone', userId: 'b' });
    expect(ids(visibleRows(done))).toEqual(['me']);
    expect(done.pendingRemoval).toBeNull();
  });
  it('a failed refetch keeps the list on screen; a first failed load is an error', () => {
    expect(membersReducer(ready, { type: 'failed' })).toBe(ready);
    expect(membersReducer(initialMembersState, { type: 'failed' }).view).toEqual({ kind: 'error' });
  });
});

describe('CORE-04: parseMembersCache', () => {
  const good = [row('a', 'owner', 'A'), row('b', 'member', null)];
  it('round-trips a valid snapshot', () => {
    expect(parseMembersCache(JSON.stringify(good))).toEqual(good);
  });
  it.each([
    ['null input', null],
    ['bad JSON', '{nope'],
    ['not an array', '{"a":1}'],
    ['a non-object row', '[1]'],
    ['an unknown role', JSON.stringify([row('a', 'admin' as Role, 'A')])],
    ['a missing user_id', JSON.stringify([{ member: { household_id: 'h', role: 'owner' }, profile: null }])],
    ['a missing profile', JSON.stringify([{ member: good[0]!.member }])],
    ['a profile without a name', JSON.stringify([{ member: good[0]!.member, profile: {} }])],
  ])('%s -> null', (_label, raw) => {
    expect(parseMembersCache(raw)).toBeNull();
  });
});
