import type { Store } from '@abodenestly/contract';
import Dexie from 'dexie';
import { beforeEach, describe, expect, it } from 'vitest';
import { openHouseholdDb } from '../sync/db.ts';
import { daysLeft, deletedSections, restore, restoreEnvelope, type DeletedRow } from './RecentlyDeleted.ts';

const DAY = 86_400_000;
const NOW = new Date('2026-11-19T12:00:00Z');
const ago = (ms: number) => new Date(NOW.getTime() - ms).toISOString();
const all = { shopping: true, pantry: true, maintenance: true };
const row = (table: DeletedRow['table'], id: string, msAgo: number): DeletedRow => ({ table, id, name: id, deleted_at: ago(msAgo) });

describe('CORE-10: daysLeft', () => {
  it.each([
    ['just now', 0, 30],
    ['2 days', 2 * DAY, 28],
    ['2 days 23 h', 3 * DAY - 1000, 28],
    ['29 days', 29 * DAY, 1],
    ['30 days', 30 * DAY, 0],
    ['future (clock skew)', -DAY, 30],
  ])('%s', (_n, ms, expected) => {
    expect(daysLeft(ago(ms), NOW)).toBe(expected);
  });
});

describe('CORE-10: deletedSections', () => {
  const rows = [row('list_item', 'a', DAY), row('store', 'b', 3 * DAY), row('pantry_item', 'c', DAY), row('asset', 'd', 30 * DAY), row('maint_task', 'e', 2 * DAY)];

  it('groups by module in order, newest deleted first, and hides 30-day-old rows (AC4)', () => {
    const s = deletedSections(rows, all, NOW);
    expect(s.map((x) => x.module)).toEqual(['shopping', 'pantry', 'maintenance']);
    expect(s[0]!.items.map((i) => i.id)).toEqual(['a', 'b']);
    expect(s[2]!.items.map((i) => i.id)).toEqual(['e']);
  });

  it('omits disabled modules (AC5)', () => {
    const s = deletedSections(rows, { ...all, pantry: false }, NOW);
    expect(s.map((x) => x.module)).toEqual(['shopping', 'maintenance']);
  });
});

describe('CORE-10: restore', () => {
  const deps = { uuid: () => 'm1', now: () => NOW.toISOString() };

  it('envelope carries an explicit null', () => {
    const e = restoreEnvelope('store', 's1', deps);
    expect(e).toEqual({ id: 'm1', table: 'store', op: 'upsert', row_id: 's1', data: { deleted_at: null }, client_ts: NOW.toISOString() });
    expect(JSON.stringify(e.data)).toBe('{"deleted_at":null}');
  });

  beforeEach(async () => {
    await Dexie.delete('abodenestly-h1');
  });

  it('clears deleted_at and writes one outbox row', async () => {
    const db = await openHouseholdDb('h1');
    await db.store.put({ id: 's1', household_id: 'h1', name: 'Lidl', deleted_at: ago(DAY) } as Store);
    await restore(db, 'store', 's1', deps);
    expect((await db.store.get('s1'))!.deleted_at).toBeNull();
    const out = await db.outbox.toArray();
    expect(out).toHaveLength(1);
    expect(out[0]).toMatchObject({ table: 'store', op: 'upsert', payload: { row_id: 's1', data: { deleted_at: null } } });
    await db.close();
  });
});
