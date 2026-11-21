import type { AddressInfo } from 'node:net';
import type { Server } from 'node:http';
import pg from 'pg';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import type { SyncPullResponse, SyncPushResponse } from '@abodenestly/contract';
import { createDevServer } from '../scripts/dev-server.ts';

type User = { token: string; id: string };

let server: Server;
let baseUrl: string;
let admin: pg.Pool;
let alice: User;
let bob: User;
let aliceHome: string;
let bobHome: string;

async function signUp(): Promise<User> {
  const res = await fetch(`${process.env.SUPABASE_URL}/auth/v1/signup`, {
    method: 'POST',
    headers: { apikey: process.env.SUPABASE_ANON_KEY ?? '', 'content-type': 'application/json' },
    body: JSON.stringify({ email: `shop08-${crypto.randomUUID()}@example.com`, password: crypto.randomUUID() }),
  });
  if (!res.ok) throw new Error(`signup failed: ${res.status} ${await res.text()}`);
  const body = (await res.json()) as { access_token: string; user: { id: string } };
  return { token: body.access_token, id: body.user.id };
}

const call = (method: string, path: string, token: string | null, body?: unknown) =>
  fetch(`${baseUrl}${path}`, {
    method,
    headers: { 'content-type': 'application/json', ...(token ? { authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });

const errCode = async (res: Response) => ((await res.json()) as { error: { code: string } }).error.code;

const upsert = (table: string, rowId: string, data: Record<string, unknown>, mutationId = crypto.randomUUID()) => ({
  id: mutationId,
  table,
  op: 'upsert' as const,
  row_id: rowId,
  data,
  client_ts: new Date().toISOString(),
});

const push = async (token: string, household: string, mutations: unknown[]) => {
  const res = await call('POST', '/sync', token, { household_id: household, mutations });
  expect(res.status).toBe(200);
  return (await res.json()) as SyncPushResponse;
};

const pull = async (token: string, household: string, query = '') => {
  const res = await call('GET', `/sync?household_id=${household}${query}`, token);
  expect(res.status).toBe(200);
  return (await res.json()) as SyncPullResponse;
};

const createHousehold = async (user: User) =>
  ((await (await call('POST', '/households', user.token, { name: 'Home' })).json()) as { id: string }).id;

beforeAll(async () => {
  server = createDevServer();
  await new Promise<void>((resolve) => server.listen(0, resolve));
  baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  admin = new pg.Pool({ connectionString: process.env.DATABASE_URL, max: 1 });
  alice = await signUp();
  bob = await signUp();
  aliceHome = await createHousehold(alice);
  bobHome = await createHousehold(bob);
});

afterAll(async () => {
  await admin.end();
  await new Promise<void>((resolve) => server.close(() => resolve()));
});

describe('SHOP-08 sync', () => {
  it('AC2: GET and POST without a JWT give 401', async () => {
    expect((await call('GET', `/sync?household_id=${aliceHome}`, null)).status).toBe(401);
    expect((await call('POST', '/sync', null, { household_id: aliceHome, mutations: [] })).status).toBe(401);
  });

  it('AC1: pages by sync_seq with has_more and returns soft-deleted rows', async () => {
    const listId = crypto.randomUUID();
    const items = [crypto.randomUUID(), crypto.randomUUID(), crypto.randomUUID()];
    const res = await push(alice.token, aliceHome, [
      upsert('shopping_list', listId, { name: 'Weekly' }),
      ...items.map((id, i) => upsert('list_item', id, { list_id: listId, name: `Item ${i}` })),
    ]);
    expect(res.rejected).toEqual([]);
    expect(res.applied).toHaveLength(4);

    // The household was created through the API, so it has a few rows of its own before ours.
    const all = await pull(alice.token, aliceHome);
    const rows = Object.values(all.changes).flat() as Array<{ sync_seq: number }>;
    expect(all.has_more).toBe(false);
    expect(all.cursor).toBe(Math.max(...rows.map((r) => r.sync_seq)));

    const first = await pull(alice.token, aliceHome, '&since=0&limit=2');
    expect(first.has_more).toBe(true);
    const firstRows = Object.values(first.changes).flat() as Array<{ sync_seq: number }>;
    expect(firstRows).toHaveLength(2);
    expect(first.cursor).toBe(firstRows[1]!.sync_seq);

    let cursor = first.cursor;
    let seen = firstRows.length;
    for (let more = true; more; ) {
      const page = await pull(alice.token, aliceHome, `&since=${cursor}&limit=2`);
      seen += (Object.values(page.changes).flat() as unknown[]).length;
      cursor = page.cursor;
      more = page.has_more;
    }
    expect(seen).toBe(rows.length);

    await push(alice.token, aliceHome, [{ id: crypto.randomUUID(), table: 'list_item', op: 'delete', row_id: items[0], client_ts: 'x' }]);
    const after = await pull(alice.token, aliceHome, `&since=${all.cursor}`);
    const deleted = (after.changes.list_item as Array<{ id: string; deleted_at: string | null }>).find((r) => r.id === items[0]);
    expect(deleted?.deleted_at).not.toBeNull();
  });

  it('AC3: one bad mutation does not stop the others', async () => {
    const good1 = crypto.randomUUID();
    const good2 = crypto.randomUUID();
    const bad = upsert('nope', crypto.randomUUID(), { name: 'x' });
    const res = await push(alice.token, aliceHome, [upsert('store', good1, { name: 'A' }), bad, upsert('store', good2, { name: 'B' })]);
    expect(res.applied).toHaveLength(2);
    expect(res.rejected).toMatchObject([{ id: bad.id, reason: 'unknown_table' }]);
    const stores = (await pull(alice.token, aliceHome)).changes.store as Array<{ id: string }>;
    expect(stores.map((s) => s.id)).toEqual(expect.arrayContaining([good1, good2]));
  });

  it('AC4: replaying a mutation id is applied again without changing the row or sync_seq', async () => {
    const rowId = crypto.randomUUID();
    const mutationId = crypto.randomUUID();
    await push(alice.token, aliceHome, [upsert('store', rowId, { name: 'First' }, mutationId)]);
    const seq = (await admin.query('select sync_seq from store where id = $1', [rowId])).rows[0].sync_seq;
    const replay = await push(alice.token, aliceHome, [upsert('store', rowId, { name: 'Changed' }, mutationId)]);
    expect(replay).toEqual({ applied: [mutationId], rejected: [] });
    const row = (await admin.query('select name, sync_seq from store where id = $1', [rowId])).rows[0];
    expect(row).toEqual({ name: 'First', sync_seq: seq });
  });

  it('drops server-owned columns and rejects unknown ones', async () => {
    const listId = crypto.randomUUID();
    const ok = upsert('shopping_list', listId, { name: 'L', share_token: 'forged', sync_seq: 1, household_id: bobHome });
    const bad = upsert('shopping_list', crypto.randomUUID(), { name: 'L', bogus: true });
    const res = await push(alice.token, aliceHome, [ok, bad]);
    expect(res.applied).toEqual([ok.id]);
    expect(res.rejected).toMatchObject([{ id: bad.id, reason: 'validation' }]);
    const row = (await admin.query('select household_id, share_token from shopping_list where id = $1', [listId])).rows[0];
    expect(row).toEqual({ household_id: aliceHome, share_token: null });
  });

  it('rejects a push to a pull-only table as validation', async () => {
    const m = upsert('invite', crypto.randomUUID(), { code: 'x' });
    expect((await push(alice.token, aliceHome, [m])).rejected).toMatchObject([{ id: m.id, reason: 'validation' }]);
  });

  it('RLS: another household cannot upsert or read Alice rows', async () => {
    const rowId = crypto.randomUUID();
    await push(alice.token, aliceHome, [upsert('store', rowId, { name: 'Mine' })]);
    const m = upsert('store', rowId, { name: 'Stolen' });
    expect((await push(bob.token, bobHome, [m])).rejected).toMatchObject([{ id: m.id, reason: 'rls_denied' }]);
    expect((await admin.query('select name from store where id = $1', [rowId])).rows[0].name).toBe('Mine');

    const forbidden = await call('GET', `/sync?household_id=${aliceHome}`, bob.token);
    expect(forbidden.status).toBe(403);
    expect(await errCode(forbidden)).toBe('forbidden');
    expect((await call('POST', '/sync', bob.token, { household_id: aliceHome, mutations: [] })).status).toBe(403);
  });

  it('validation: bad since, limit and body give 400', async () => {
    for (const q of ['&since=-1', '&since=abc', '&limit=0', '&limit=501']) {
      const res = await call('GET', `/sync?household_id=${aliceHome}${q}`, alice.token);
      expect(res.status).toBe(400);
      expect(await errCode(res)).toBe('validation');
    }
    expect((await call('POST', '/sync', alice.token, { household_id: aliceHome })).status).toBe(400);
  });
});
