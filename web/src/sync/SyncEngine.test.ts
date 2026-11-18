import type { Activity } from '@abodenestly/contract';
import Dexie from 'dexie';
import { beforeEach, describe, expect, it } from 'vitest';
import { FakeApiClient } from '../core/api/FakeApiClient.ts';
import { openHouseholdDb } from './db.ts';
import { createActivityPull } from './SyncEngine.ts';

const NOW = new Date('2026-11-17T12:00:00.000Z');
const act = (id: string): Activity =>
  ({ id, household_id: 'h1', created_at: '2026-11-17T11:00:00.000Z', deleted_at: null, summary: id }) as Activity;

beforeEach(async () => {
  await Dexie.delete('abodenestly-h1');
});

async function setup(online = true) {
  const api = new FakeApiClient();
  api.fetchActivityResult = { ok: true, value: [act('a'), act('b')] };
  const db = await openHouseholdDb('h1');
  const engine = createActivityPull({ api, householdId: 'h1', getDb: () => db, isOnline: () => online, now: () => NOW });
  return { api, db, engine };
}

describe('CORE-08 ActivityPull', () => {
  it('asks for the last 30 days, 20 rows, and stores what comes back', async () => {
    const { api, db, engine } = await setup();
    await engine.kick();
    expect(api.fetchActivityCalls).toEqual([{ householdId: 'h1', sinceIso: '2026-10-18T12:00:00.000Z', limit: 20 }]);
    expect((await db.activity.toArray()).map((r) => r.id).sort()).toEqual(['a', 'b']);
  });

  it('shares one pull between concurrent kicks', async () => {
    const { api, engine } = await setup();
    api.pending = true;
    const first = engine.kick();
    const second = engine.kick();
    api.pending = false;
    api.resolvePending();
    await Promise.all([first, second]);
    expect(api.fetchActivityCalls).toHaveLength(1);
    await engine.kick();
    expect(api.fetchActivityCalls).toHaveLength(2);
  });

  it('is a no-op offline and keeps the cached rows', async () => {
    const { api, db, engine } = await setup(false);
    await db.activity.put(act('cached'));
    await engine.kick();
    expect(api.fetchActivityCalls).toHaveLength(0);
    expect((await db.activity.toArray()).map((r) => r.id)).toEqual(['cached']);
  });

  it('drops cached rows in the window that the page omits, an empty page included', async () => {
    const { api, db, engine } = await setup();
    const old = { ...act('old'), created_at: '2026-09-01T00:00:00.000Z' };
    await db.activity.bulkPut([act('gone'), act('a'), old]);
    await engine.kick();
    // 'gone' was soft-deleted on the server, so the live page omits it; the row older than the window stays.
    expect((await db.activity.toArray()).map((r) => r.id).sort()).toEqual(['a', 'b', 'old']);
    api.fetchActivityResult = { ok: true, value: [] };
    await engine.kick();
    expect((await db.activity.toArray()).map((r) => r.id)).toEqual(['old']);
  });

  it('keeps the cache when the read fails', async () => {
    const { api, db, engine } = await setup();
    await db.activity.put(act('cached'));
    api.fetchActivityResult = { ok: false, error: { code: 'network' } };
    await engine.kick();
    expect((await db.activity.toArray()).map((r) => r.id)).toEqual(['cached']);
  });
});
