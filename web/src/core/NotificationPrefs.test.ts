import Dexie from 'dexie';
import { beforeEach, describe, expect, it } from 'vitest';
import { openHouseholdDb } from '../sync/db.ts';
import { enabled, prefRowId, setPref, TYPES, visibleTypes } from './NotificationPrefs.ts';

// Golden values: identical in the iOS and Android suites.
const USER = '11111111-1111-4111-8111-111111111111';
const HOUSE = '22222222-2222-4222-8222-222222222222';
const GOLDEN_EAT_SOON = '38c53767-3538-5d08-9384-d9843b1761ad';
const GOLDEN_ACTIVITY = '4aa3e84e-d57f-53f9-8472-e9fcf82e8393';
const deps = { uuid: () => 'mutation-1', now: () => '2026-11-20T10:00:00.000Z' };

beforeEach(async () => {
  await Dexie.delete(`abodenestly-${HOUSE}`);
});

describe('CORE-12: prefRowId', () => {
  it('matches the cross-platform golden UUID v5', async () => {
    expect(await prefRowId(USER, HOUSE, 'eat_soon')).toBe(GOLDEN_EAT_SOON);
    expect(await prefRowId(USER, HOUSE, 'activity')).toBe(GOLDEN_ACTIVITY);
  });

  it('is case-insensitive in its inputs', async () => {
    expect(await prefRowId(USER.toUpperCase(), HOUSE.toUpperCase(), 'eat_soon')).toBe(GOLDEN_EAT_SOON);
  });
});

describe('CORE-12: visibleTypes', () => {
  const keys = (m: { pantry: boolean; maintenance: boolean }) => visibleTypes({ shopping: true, ...m }).map((t) => t.type);
  it.each([
    ['AC1 all modules on', true, true, ['eat_soon', 'low_stock', 'task_due', 'warranty', 'activity']],
    ['AC4 pantry off', false, true, ['task_due', 'warranty', 'activity']],
    ['AC5 maintenance off', true, false, ['eat_soon', 'low_stock', 'activity']],
    ['both off', false, false, ['activity']],
  ])('%s', (_n, pantry, maintenance, expected) => {
    expect(keys({ pantry, maintenance })).toEqual(expected);
  });

  it('keeps DESIGN labels in order', () => {
    expect(TYPES.map((t) => t.label)).toEqual(['Eat soon', 'Low stock', 'Task due', 'Warranty', 'Activity']);
  });
});

describe('CORE-12: enabled', () => {
  it('a missing row means enabled', () => {
    expect(enabled([], 'eat_soon')).toBe(true);
    expect(enabled([{ type: 'eat_soon', enabled: false }], 'eat_soon')).toBe(false);
    expect(enabled([{ type: 'eat_soon', enabled: false }], 'low_stock')).toBe(true);
  });
});

describe('CORE-12: setPref', () => {
  it('AC2 writes the local row and queues one upsert with the v5 row_id', async () => {
    const db = await openHouseholdDb(HOUSE);
    await setPref(db, { userId: USER, householdId: HOUSE, type: 'eat_soon' }, false, deps);
    expect(await db.notification_pref.toArray()).toEqual([{ user_id: USER, household_id: HOUSE, type: 'eat_soon', enabled: false }]);
    const out = await db.outbox.toArray();
    expect(out).toHaveLength(1);
    expect(out[0]).toMatchObject({
      table: 'notification_pref',
      op: 'upsert',
      payload: {
        id: 'mutation-1',
        table: 'notification_pref',
        op: 'upsert',
        row_id: GOLDEN_EAT_SOON,
        data: { user_id: USER, household_id: HOUSE, type: 'eat_soon', enabled: false },
        client_ts: '2026-11-20T10:00:00.000Z',
      },
    });
    await db.close();
  });

  it('toggling again overwrites the one local row and queues a second upsert', async () => {
    const db = await openHouseholdDb(HOUSE);
    const key = { userId: USER, householdId: HOUSE, type: 'eat_soon' } as const;
    await setPref(db, key, false, deps);
    await setPref(db, key, true, deps);
    expect(await db.notification_pref.count()).toBe(1);
    expect((await db.notification_pref.toArray())[0]!.enabled).toBe(true);
    expect(await db.outbox.count()).toBe(2);
    await db.close();
  });

  it('mixed-case ids are stored and sent lowercase, matching the row_id hash', async () => {
    const db = await openHouseholdDb(HOUSE);
    await setPref(db, { userId: USER.toUpperCase(), householdId: HOUSE.toUpperCase(), type: 'eat_soon' }, false, deps);
    expect(await db.notification_pref.toArray()).toEqual([{ user_id: USER, household_id: HOUSE, type: 'eat_soon', enabled: false }]);
    expect((await db.outbox.toArray())[0]!.payload).toMatchObject({ row_id: GOLDEN_EAT_SOON, data: { user_id: USER, household_id: HOUSE } });
    await db.close();
  });
});
