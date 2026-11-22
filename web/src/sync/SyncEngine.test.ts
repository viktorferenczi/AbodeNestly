import type { ListItem, Mutation, SyncPushResponse, SyncRejection } from '@abodenestly/contract';
import Dexie from 'dexie';
import { beforeEach, describe, expect, it } from 'vitest';
import { FakeApiClient } from '../core/api/FakeApiClient.ts';
import { HouseholdDb, openHouseholdDb, readCursor } from './db.ts';
import { mutate } from './outbox.ts';
import { createSyncEngine } from './SyncEngine.ts';

const NOW = new Date('2026-11-17T12:00:00.000Z');
let n = 0;
const deps = () => ({ uuid: () => `m${++n}`, now: () => NOW.toISOString() });
const item = (id: string, name = id) => ({ id, household_id: 'h1', list_id: 'l1', name, deleted_at: null }) as ListItem;

beforeEach(async () => {
  n = 0;
  await Dexie.delete('abodenestly-h1');
});

async function setup(online = true) {
  const api = new FakeApiClient();
  const db = await openHouseholdDb('h1');
  let isOnline = online;
  const engine = createSyncEngine({
    api,
    householdId: 'h1',
    getDb: () => db,
    isOnline: () => isOnline,
    sleep: () => Promise.resolve(),
    now: () => NOW,
  });
  const write = mutate(db, deps());
  return { api, db, engine, write, setOnline: (v: boolean) => (isOnline = v) };
}

const rejection = (id: string, reason: SyncRejection['reason'] = 'validation'): SyncRejection => ({ id, reason, detail: 'bad' });

describe('SHOP-08: mutate', () => {
  it('AC1: queues one outbox row and the local row in one transaction', async () => {
    const { db, write } = await setup(false);
    await write.upsert('list_item', item('i1'));
    expect(await db.list_item.get('i1')).toMatchObject({ name: 'i1' });
    const out = await db.outbox.toArray();
    expect(out).toHaveLength(1);
    expect(out[0]).toMatchObject({ table: 'list_item', op: 'upsert' });
    const m = out[0]!.payload as Mutation;
    expect(m).toMatchObject({ id: 'm1', row_id: 'i1', client_ts: NOW.toISOString() });
    // Server-owned columns do not travel.
    expect((m as { data: Record<string, unknown> }).data).toEqual({ list_id: 'l1', name: 'i1', deleted_at: null });
  });

  it('SHOP-01: patch updates only the given columns locally and queues only them', async () => {
    const { db, write } = await setup(false);
    await db.list_item.put(item('i1'));
    await write.patch('list_item', 'i1', { name: 'renamed', updated_at: 'ignored' });
    expect(await db.list_item.get('i1')).toMatchObject({ name: 'renamed', list_id: 'l1' });
    const out = await db.outbox.toArray();
    expect(out).toHaveLength(1);
    expect(out[0]!.payload).toMatchObject({ table: 'list_item', op: 'upsert', row_id: 'i1', data: { name: 'renamed' } });
    expect((out[0]!.payload as { data: object }).data).toEqual({ name: 'renamed' });
  });

  it('rolls both back when the transaction throws', async () => {
    const { db, write } = await setup(false);
    const original = db.outbox.add.bind(db.outbox);
    db.outbox.add = () => {
      throw new Error('boom');
    };
    await expect(write.upsert('list_item', item('i1'))).rejects.toThrow('boom');
    db.outbox.add = original;
    expect(await db.list_item.count()).toBe(0);
    expect(await db.outbox.count()).toBe(0);
  });

  it('remove soft-deletes locally and queues a delete', async () => {
    const { db, write } = await setup(false);
    await write.upsert('list_item', item('i1'));
    await write.remove('list_item', 'i1');
    expect((await db.list_item.get('i1'))?.deleted_at).toBe(NOW.toISOString());
    expect((await db.outbox.toArray()).map((r) => r.op)).toEqual(['upsert', 'delete']);
  });

  it('calls onQueued after commit', async () => {
    const { db } = await setup(false);
    let kicked = 0;
    await mutate(db, { ...deps(), onQueued: () => kicked++ }).upsert('list_item', item('i1'));
    expect(kicked).toBe(1);
  });
});

describe('SHOP-08: sync loop', () => {
  it('AC2: pushes both rows in order in one request, then pulls from 0 and stores rows and cursor', async () => {
    const { api, db, engine, write } = await setup();
    await write.upsert('list_item', item('i1'));
    await write.upsert('list_item', item('i2'));
    api.syncPullResults = [{ ok: true, value: { cursor: 7, has_more: false, changes: { list_item: [item('i9')], activity: [{ id: 'a1', household_id: 'h1' }] } } }];
    await engine.kick();
    expect(api.syncPushCalls).toHaveLength(1);
    expect(api.syncPushCalls[0]!.mutations.map((m) => m.row_id)).toEqual(['i1', 'i2']);
    expect(api.syncPullCalls).toEqual([{ householdId: 'h1', since: 0, limit: 500 }]);
    expect(await db.outbox.count()).toBe(0);
    expect((await db.list_item.toArray()).map((r) => r.id).sort()).toEqual(['i1', 'i2', 'i9']);
    expect(await db.activity.count()).toBe(1);
    expect(await readCursor(db)).toBe(7);
    expect(engine.getPhase()).toBe('idle');
  });

  it('pulls again while has_more', async () => {
    const { api, db, engine } = await setup();
    api.syncPullResults = [
      { ok: true, value: { cursor: 2, has_more: true, changes: { list_item: [item('a')] } } },
      { ok: true, value: { cursor: 3, has_more: false, changes: { list_item: [item('b')] } } },
    ];
    await engine.kick();
    expect(api.syncPullCalls.map((c) => c.since)).toEqual([0, 2]);
    expect(await db.list_item.count()).toBe(2);
    expect(await readCursor(db)).toBe(3);
  });

  it('skips a pulled table the client does not know', async () => {
    const { api, db, engine } = await setup();
    api.syncPullResults = [
      { ok: true, value: { cursor: 4, has_more: false, changes: { unheard_of: [{ id: 'x' }], store: [{ id: 's1', household_id: 'h1', name: 'Lidl', deleted_at: null }] } as never } },
    ];
    await engine.kick();
    expect(await db.store.count()).toBe(1);
    expect(await readCursor(db)).toBe(4);
  });

  it('does not overwrite a row that still has a queued change, but still advances the cursor', async () => {
    const { api, db, engine } = await setup();
    // pantry_item is held back (the server does not accept it yet), so its mutation stays queued through a full push and pull.
    await db.pantry_item.put({ id: 'p1', household_id: 'h1', name: 'mine', deleted_at: null });
    await db.outbox.add({ table: 'pantry_item', op: 'upsert', payload: { id: 'm', table: 'pantry_item', op: 'upsert', row_id: 'p1', data: {}, client_ts: 'x' } });
    api.syncPullResults = [
      { ok: true, value: { cursor: 3, has_more: false, changes: { pantry_item: [{ id: 'p1', household_id: 'h1', name: 'old', deleted_at: null }, { id: 'p2', household_id: 'h1', name: 'new', deleted_at: null }] } } },
    ];
    await engine.kick();
    expect(api.syncPullCalls).toHaveLength(1);
    expect((await db.pantry_item.get('p1'))?.name).toBe('mine');
    expect((await db.pantry_item.get('p2'))?.name).toBe('new');
    expect(await readCursor(db)).toBe(3);
  });

  it('does not overwrite a row whose change is in outbox_failed', async () => {
    const { api, db, engine } = await setup();
    await db.list_item.put(item('i1', 'mine'));
    await db.outbox_failed.put({ seq: 1, table: 'list_item', op: 'upsert', payload: { id: 'm', table: 'list_item', op: 'upsert', row_id: 'i1', data: {}, client_ts: 'x' }, reason: 'validation', detail: 'bad', failed_at: 'now' });
    api.syncPullResults = [{ ok: true, value: { cursor: 2, has_more: false, changes: { list_item: [item('i1', 'old')] } } }];
    await engine.kick();
    expect((await db.list_item.get('i1'))?.name).toBe('mine');
  });

  it('a push that stops on a non-network error skips the pull', async () => {
    const { api, engine, write } = await setup();
    await write.upsert('list_item', item('i1'));
    api.syncPushResults = [{ ok: false, error: { code: 'unknown' } }];
    await engine.kick();
    expect(api.syncPullCalls).toHaveLength(0);
  });

  it('a notification_pref row survives kick(), is not in the POST body, and is not counted', async () => {
    const { api, db, engine } = await setup();
    await db.outbox.add({ table: 'notification_pref', op: 'upsert', payload: { id: 'np', table: 'notification_pref', op: 'upsert', row_id: 'r', data: {}, client_ts: 'x' } });
    await engine.kick();
    expect(api.syncPushCalls).toHaveLength(0);
    expect(await db.outbox.count()).toBe(1);
  });

  it('moves a rejected mutation to outbox_failed with its reason and keeps the rest flowing', async () => {
    const { api, db, engine, write } = await setup();
    await write.upsert('list_item', item('i1'));
    await write.upsert('list_item', item('i2'));
    api.syncPushResults = [(req): { ok: true; value: SyncPushResponse } => ({ ok: true, value: { applied: [req.mutations[1]!.id], rejected: [rejection(req.mutations[0]!.id)] } })];
    await engine.kick();
    expect(await db.outbox.count()).toBe(0);
    const failed = await db.outbox_failed.toArray();
    expect(failed).toHaveLength(1);
    expect(failed[0]).toMatchObject({ table: 'list_item', op: 'upsert', reason: 'validation', detail: 'bad', failed_at: NOW.toISOString() });
  });

  it('retries rls_denied once after a session refresh, then fails it', async () => {
    const { api, db, engine, write } = await setup();
    await write.upsert('list_item', item('i1'));
    api.syncPushResults = [(req) => ({ ok: true, value: { applied: [], rejected: [rejection(req.mutations[0]!.id, 'rls_denied')] } })];
    await engine.kick();
    expect(api.refreshCalls).toBe(1);
    expect(api.syncPushCalls).toHaveLength(2);
    expect(await db.outbox.count()).toBe(0);
    expect((await db.outbox_failed.toArray())[0]!.reason).toBe('rls_denied');
  });

  it('keeps rows and retries once after 503 retryable, then stops until the next trigger', async () => {
    const { api, db, engine, write } = await setup();
    await write.upsert('list_item', item('i1'));
    api.syncPushResults = [{ ok: false, error: { code: 'retryable' } }];
    await engine.kick();
    expect(api.syncPushCalls).toHaveLength(2);
    expect(api.syncPullCalls).toHaveLength(0);
    expect(await db.outbox.count()).toBe(1);
  });

  it('keeps rows, skips the pull and reports offline on a network error', async () => {
    const { api, db, engine, write } = await setup();
    await write.upsert('list_item', item('i1'));
    api.syncPushResults = [{ ok: false, error: { code: 'network' } }];
    await engine.kick();
    expect(api.syncPullCalls).toHaveLength(0);
    expect(await db.outbox.count()).toBe(1);
    expect(engine.getPhase()).toBe('offline');
  });

  it('is a no-op offline: status offline, nothing sent', async () => {
    const { api, engine, write } = await setup(false);
    await write.upsert('list_item', item('i1'));
    await engine.kick();
    expect(api.syncPushCalls).toHaveLength(0);
    expect(api.syncPullCalls).toHaveLength(0);
    expect(engine.getPhase()).toBe('offline');
  });

  it('leaves rows for tables the server does not accept yet queued and never sends them', async () => {
    const { api, db, engine, write } = await setup();
    await db.outbox.add({ table: 'notification_pref', op: 'upsert', payload: { id: 'p1', table: 'notification_pref', op: 'upsert', row_id: 'r', data: {}, client_ts: 'x' } });
    await write.upsert('list_item', item('i1'));
    await engine.kick();
    expect(api.syncPushCalls[0]!.mutations).toHaveLength(1);
    expect((await db.outbox.toArray()).map((r) => r.table)).toEqual(['notification_pref']);
  });

  it('shares one run between concurrent kicks and runs once more for a kick during a run', async () => {
    const { api, engine, write } = await setup();
    await write.upsert('list_item', item('i1'));
    api.pending = true;
    const first = engine.kick();
    const second = engine.kick();
    await new Promise((r) => setTimeout(r, 20));
    api.pending = false;
    api.resolvePending();
    await Promise.all([first, second]);
    // Two kicks: the first run plus one more, not three or four.
    expect(api.syncPullCalls).toHaveLength(2);
    expect(api.syncPushCalls).toHaveLength(1);
  });

  it('writes nothing when the household closes during a pull', async () => {
    const { api, db, engine } = await setup();
    api.pending = true;
    api.syncPullResults = [{ ok: true, value: { cursor: 5, has_more: false, changes: { list_item: [item('i1')] } } }];
    const run = engine.kick();
    await new Promise((r) => setTimeout(r, 20));
    db.close();
    api.pending = false;
    api.resolvePending();
    await run;
    const reopened = await openHouseholdDb('h1');
    expect(await reopened.list_item.count()).toBe(0);
    expect(await readCursor(reopened)).toBe(0);
  });

  it('notifies subscribers of phase changes', async () => {
    const { engine } = await setup();
    const seen: string[] = [];
    engine.subscribe(() => seen.push(engine.getPhase()));
    await engine.kick();
    expect(seen).toEqual(['syncing', 'idle']);
  });
});

describe('SHOP-08: failed changes', () => {
  async function failOne() {
    const ctx = await setup();
    await ctx.write.upsert('list_item', item('i1'));
    ctx.api.syncPushResults = [(req) => ({ ok: true, value: { applied: [], rejected: [rejection(req.mutations[0]!.id)] } })];
    await ctx.engine.kick();
    const [failed] = await ctx.db.outbox_failed.toArray();
    return { ...ctx, seq: failed!.seq };
  }

  it('Retry puts the mutation back and pushes it', async () => {
    const { api, db, engine, seq } = await failOne();
    api.syncPushResults = [];
    await engine.retry(seq);
    expect(api.syncPushCalls.at(-1)!.mutations.map((m) => m.row_id)).toEqual(['i1']);
    expect(await db.outbox_failed.count()).toBe(0);
    expect(await db.outbox.count()).toBe(0);
  });

  it('Discard deletes the local row, resets the cursor and pulls again from 0', async () => {
    const { api, db, engine, seq } = await failOne();
    await db.meta.put({ key: 'cursor', value: 9 });
    api.syncPullResults = [{ ok: true, value: { cursor: 10, has_more: false, changes: { list_item: [] } } }];
    await engine.discard(seq);
    expect(await db.outbox_failed.count()).toBe(0);
    expect(await db.list_item.get('i1')).toBeUndefined();
    expect(api.syncPullCalls.at(-1)!.since).toBe(0);
  });

  it('Discard keeps the local row when another change targets it', async () => {
    const { db, engine, write, seq } = await failOne();
    await write.upsert('list_item', item('i1', 'again'));
    await engine.discard(seq);
    expect((await db.list_item.get('i1'))?.name).toBe('again');
  });
});

describe('SHOP-08: db version 6', () => {
  it('resets the pull cursor when upgrading from version 5', async () => {
    const old = new Dexie('abodenestly-h1');
    old.version(1).stores({ shopping_list: 'id, household_id', outbox: '++seq', meta: 'key' });
    old.version(5).stores({ activity: 'id' });
    await old.open();
    await old.table('meta').put({ key: 'cursor', value: 42 });
    old.close();
    const upgraded = new HouseholdDb('h1');
    await upgraded.open();
    expect((await upgraded.meta.get('cursor'))?.value).toBe(0);
    upgraded.close();
  });
});
