import type { AddressInfo } from 'node:net';
import type { Server } from 'node:http';
import pg from 'pg';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import type { SyncPullResponse } from '@abodenestly/contract';
import { createDevServer } from '../scripts/dev-server.ts';

// #514: set_sync_fields() holds a per-household advisory lock until commit, so sync_seq follows
// commit order within a household. Two open transactions are needed, hence separate connections.
let server: Server;
let baseUrl: string;
let admin: pg.Client;
let a: pg.Client;
let b: pg.Client;
let token: string;
let home: string;
let other: string;
let listId: string;
let otherListId: string;

const connect = async () => {
  const c = new pg.Client({ connectionString: process.env.DATABASE_URL });
  await c.connect();
  return c;
};

const insertItem = (c: pg.Client, household: string, list: string, name: string) =>
  c.query(
    `insert into list_item (id, household_id, list_id, name) values ($1, $2, $3, $4) returning sync_seq::bigint as seq`,
    [crypto.randomUUID(), household, list, name],
  );

const pull = async (since: number) => {
  const res = await fetch(`${baseUrl}/sync?household_id=${home}&since=${since}`, { headers: { authorization: `Bearer ${token}` } });
  expect(res.status).toBe(200);
  return (await res.json()) as SyncPullResponse;
};

const names = (r: SyncPullResponse) => ((r.changes.list_item ?? []) as Array<{ name: string }>).map((i) => i.name);

beforeAll(async () => {
  server = createDevServer();
  await new Promise<void>((resolve) => server.listen(0, resolve));
  baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  const res = await fetch(`${process.env.SUPABASE_URL}/auth/v1/signup`, {
    method: 'POST',
    headers: { apikey: process.env.SUPABASE_ANON_KEY ?? '', 'content-type': 'application/json' },
    body: JSON.stringify({ email: `shop08-order-${crypto.randomUUID()}@example.com`, password: crypto.randomUUID() }),
  });
  if (!res.ok) throw new Error(`signup failed: ${res.status} ${await res.text()}`);
  token = ((await res.json()) as { access_token: string }).access_token;
  const create = async () =>
    ((await (await fetch(`${baseUrl}/households`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', authorization: `Bearer ${token}` },
      body: JSON.stringify({ name: 'Home' }),
    })).json()) as { id: string }).id;
  home = await create();
  other = await create();
  admin = await connect();
  a = await connect();
  b = await connect();
  const lists = async (h: string) =>
    (await admin.query<{ id: string }>('select id from shopping_list where household_id = $1 limit 1', [h])).rows[0]!.id;
  listId = await lists(home);
  otherListId = await lists(other);
});

afterAll(async () => {
  await a.query('rollback').catch(() => undefined);
  await b.query('rollback').catch(() => undefined);
  await Promise.all([admin.end(), a.end(), b.end()]);
  await new Promise<void>((resolve) => server.close(() => resolve()));
});

describe('SHOP-08 sync commit order (#514)', () => {
  it('AC1: a second writer in the same household waits for the first to commit', async () => {
    await a.query('begin');
    const first = await insertItem(a, home, listId, 'first');
    await b.query('begin');
    await b.query(`set local lock_timeout = '300ms'`);
    await expect(insertItem(b, home, listId, 'second')).rejects.toMatchObject({ code: '55P03' });
    await b.query('rollback');
    await a.query('commit');

    await b.query('begin');
    const second = await insertItem(b, home, listId, 'second');
    await b.query('commit');
    expect(BigInt(second.rows[0].seq)).toBeGreaterThan(BigInt(first.rows[0].seq));
  });

  it('AC2: a writer in another household is not blocked', async () => {
    await a.query('begin');
    await insertItem(a, home, listId, 'held');
    await b.query('begin');
    await b.query(`set local lock_timeout = '300ms'`);
    await expect(insertItem(b, other, otherListId, 'elsewhere')).resolves.toBeDefined();
    await b.query('commit');
    await a.query('commit');
  });

  it('AC3: a pull never skips a row that commits after a higher sequence was served', async () => {
    const before = (await pull(0)).cursor;
    await a.query('begin');
    await insertItem(a, home, listId, 'order-a');
    // B starts second and is blocked until A commits, so it cannot take a lower-than-served sequence.
    await b.query('begin');
    const blocked = insertItem(b, home, listId, 'order-b');

    // Neither row is visible while A is open, so the cursor cannot move past A's sequence.
    const during = await pull(before);
    expect(names(during)).not.toContain('order-a');
    expect(names(during)).not.toContain('order-b');
    expect(during.cursor).toBe(before);

    await a.query('commit');
    await blocked;
    await b.query('commit');

    const after = await pull(before);
    const got = names(after).filter((n) => n.startsWith('order-'));
    expect(got).toEqual(['order-a', 'order-b']);
    const full = await pull(0);
    expect(names(full)).toEqual(expect.arrayContaining(['order-a', 'order-b']));
  });
});
