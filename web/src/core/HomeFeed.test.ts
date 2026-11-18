import type { Activity } from '@abodenestly/contract';
import { describe, expect, it } from 'vitest';
import { homeFeed, homeState } from './HomeFeed.ts';

const NOW = new Date('2026-11-17T12:00:00.000Z');
const DAY = 86_400_000;
const at = (msAgo: number) => new Date(NOW.getTime() - msAgo).toISOString();
const row = (id: string, created_at: string, over: Partial<Activity> = {}): Activity =>
  ({ id, household_id: 'h1', created_at, deleted_at: null, summary: `s-${id}`, module: 'shopping', ...over }) as Activity;

describe('CORE-08 homeFeed', () => {
  it.each([
    ['keeps a row from just inside the window', [row('a', at(30 * DAY - 1000))], ['a']],
    ['keeps a row exactly 30 days old', [row('a', at(30 * DAY))], ['a']],
    ['drops a row older than 30 days', [row('a', at(30 * DAY + 1000))], []],
    ['drops soft-deleted rows', [row('a', at(1000), { deleted_at: at(10) })], []],
    ['orders newest first', [row('a', at(3000)), row('b', at(1000)), row('c', at(2000))], ['b', 'c', 'a']],
    ['breaks ties by id, descending', [row('a', at(1000)), row('c', at(1000)), row('b', at(1000))], ['c', 'b', 'a']],
  ])('%s', (_name, rows, ids) => {
    expect(homeFeed(rows, NOW).map((r) => r.id)).toEqual(ids);
  });

  it('caps at 20 rows and keeps the newest', () => {
    const rows = Array.from({ length: 25 }, (_, i) => row(`r${String(i).padStart(2, '0')}`, at((i + 1) * 1000)));
    const feed = homeFeed(rows, NOW);
    expect(feed).toHaveLength(20);
    expect(feed[0]!.id).toBe('r00');
    expect(feed[19]!.id).toBe('r19');
  });

  it('does not change the input order', () => {
    const rows = [row('a', at(3000)), row('b', at(1000))];
    homeFeed(rows, NOW);
    expect(rows.map((r) => r.id)).toEqual(['a', 'b']);
  });
});

describe('CORE-08 homeState', () => {
  const on = { shopping: true, pantry: false, maintenance: false };
  const off = { shopping: false, pantry: false, maintenance: false };
  const one = [row('a', at(1000))];
  it.each([
    ['no rows, a module on', [], on, 'empty'],
    ['rows, a module on', one, on, 'populated'],
    ['no rows, all off', [], off, 'allModulesOff'],
    ['rows, all off (feed still shows)', one, off, 'allModulesOff'],
  ] as const)('%s', (_n, feed, modules, expected) => {
    expect(homeState(feed, modules)).toBe(expected);
  });
});
