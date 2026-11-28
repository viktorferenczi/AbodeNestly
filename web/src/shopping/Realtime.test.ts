import type { SyncPullResponse } from '@abodenestly/contract';
import Dexie from 'dexie';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { FakeApiClient } from '../core/api/FakeApiClient.ts';
import { openHouseholdDb } from '../sync/db.ts';
import { createSyncEngine } from '../sync/SyncEngine.ts';
import { REALTIME_TABLES, realtimeTables, subscribeHouseholdRealtime, type RealtimeChannelLike, type RealtimeClientLike } from '../sync/realtime.ts';

type Binding = { filter: { event: string; schema: string; table: string; filter: string }; callback: (payload?: unknown) => void };

// Hand-written fake of the Supabase client (TESTING §7): records channels and lets a test emit events and statuses.
class FakeRealtime implements RealtimeClientLike {
  channels: string[] = [];
  removed: RealtimeChannelLike[] = [];
  bindings: Binding[] = [];
  status: ((status: string) => void) | undefined;
  private readonly chan: RealtimeChannelLike = {
    on: (_type, filter, callback) => {
      this.bindings.push({ filter, callback });
      return this.chan;
    },
    subscribe: (callback) => {
      this.status = callback;
      return this.chan;
    },
  };
  channel(name: string) {
    this.channels.push(name);
    return this.chan;
  }
  removeChannel(channel: RealtimeChannelLike) {
    this.removed.push(channel);
  }
  emit(table: string, payload?: unknown) {
    this.bindings.filter((b) => b.filter.table === table).forEach((b) => b.callback(payload));
  }
}

const ON = { shopping: true, pantry: true, maintenance: true };
const SHOPPING_OFF = { shopping: false, pantry: true, maintenance: true };

describe('SHOP-09: subscribeHouseholdRealtime', () => {
  it('AC1: an event calls onChange once with no arguments and never reads the payload', () => {
    const client = new FakeRealtime();
    const onChange = vi.fn();
    subscribeHouseholdRealtime({ client, householdId: 'h1', modules: ON, onChange });
    const payload = new Proxy({}, { get: () => { throw new Error('payload was read'); }, ownKeys: () => { throw new Error('payload was read'); } });
    client.emit('list_item', payload);
    expect(onChange).toHaveBeenCalledTimes(1);
    expect(onChange).toHaveBeenCalledWith();
  });

  it('listens once per published table with the household filter on one channel (SPEC §6.4)', () => {
    const client = new FakeRealtime();
    subscribeHouseholdRealtime({ client, householdId: 'h1', modules: ON, onChange: vi.fn() });
    expect(client.channels).toEqual(['household:h1']);
    expect(client.bindings.map((b) => b.filter)).toEqual(
      realtimeTables(ON).map((table) => ({ event: '*', schema: 'public', table, filter: 'household_id=eq.h1' })),
    );
    // Exactly the tables the migrations publish: binding an unpublished one fails the whole join.
    expect([...realtimeTables(ON)].sort()).toEqual(
      ['activity', 'attachment', 'invite', 'item_price', 'list_item', 'location', 'notification_pref', 'shopping_list', 'store', 'store_item_order'],
    );
  });

  it('a core table such as invite kicks a pull too', () => {
    const client = new FakeRealtime();
    const onChange = vi.fn();
    subscribeHouseholdRealtime({ client, householdId: 'h1', modules: SHOPPING_OFF, onChange });
    client.emit('invite');
    expect(onChange).toHaveBeenCalledTimes(1);
  });

  it('pulls once when the channel becomes SUBSCRIBED, and ignores failures without throwing', () => {
    const client = new FakeRealtime();
    const onChange = vi.fn();
    subscribeHouseholdRealtime({ client, householdId: 'h1', modules: ON, onChange });
    for (const s of ['CHANNEL_ERROR', 'TIMED_OUT', 'CLOSED']) expect(() => client.status!(s)).not.toThrow();
    expect(onChange).not.toHaveBeenCalled();
    client.status!('SUBSCRIBED');
    expect(onChange).toHaveBeenCalledTimes(1);
  });

  it('unsubscribe removes the channel', () => {
    const client = new FakeRealtime();
    const off = subscribeHouseholdRealtime({ client, householdId: 'h1', modules: ON, onChange: vi.fn() });
    off();
    expect(client.removed).toHaveLength(1);
  });

  it('AC4: shopping off listens to no shopping table, while the core tables stay subscribed', () => {
    const client = new FakeRealtime();
    const onChange = vi.fn();
    subscribeHouseholdRealtime({ client, householdId: 'h1', modules: SHOPPING_OFF, onChange });
    const tables = client.bindings.map((b) => b.filter.table);
    for (const t of REALTIME_TABLES.shopping) expect(tables).not.toContain(t);
    expect(tables).toEqual([...REALTIME_TABLES.core]);
    client.emit('list_item');
    expect(onChange).not.toHaveBeenCalled();
  });
});

describe('SHOP-09: bursts of events', () => {
  beforeEach(async () => {
    await Dexie.delete('abodenestly-h1');
  });

  it('five events during a pull start at most one more pull', async () => {
    const api = new FakeApiClient();
    let release!: () => void;
    const gate = new Promise<void>((r) => (release = r));
    const empty = (since: number): SyncPullResponse => ({ cursor: since, has_more: false, changes: {} });
    let calls = 0;
    api.syncPull = async (_id, since) => {
      calls += 1;
      await gate;
      return { ok: true, value: empty(since) };
    };
    const db = await openHouseholdDb('h1');
    const engine = createSyncEngine({ api, householdId: 'h1', getDb: () => db, isOnline: () => true, sleep: () => Promise.resolve() });
    const client = new FakeRealtime();
    subscribeHouseholdRealtime({ client, householdId: 'h1', modules: ON, onChange: () => void engine.kick() });
    const first = engine.kick();
    for (let i = 0; i < 5; i++) client.emit('list_item');
    release();
    await first;
    expect(calls).toBeLessThanOrEqual(2);
    expect(calls).toBeGreaterThanOrEqual(1);
    await db.close();
  });
});
