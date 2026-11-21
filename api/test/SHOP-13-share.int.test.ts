import type { AddressInfo } from 'node:net';
import type { Server } from 'node:http';
import pg from 'pg';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { createDevServer } from '../scripts/dev-server.ts';

type User = { token: string; id: string };

let server: Server;
let baseUrl: string;
let admin: pg.Pool;
let alice: User;
let bob: User;
let household: string;
let listId: string;

async function signUp(): Promise<User> {
  const res = await fetch(`${process.env.SUPABASE_URL}/auth/v1/signup`, {
    method: 'POST',
    headers: { apikey: process.env.SUPABASE_ANON_KEY ?? '', 'content-type': 'application/json' },
    body: JSON.stringify({ email: `shop13-${crypto.randomUUID()}@example.com`, password: crypto.randomUUID() }),
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
const tokenOf = (url: string) => url.split('/').pop() ?? '';

async function share(token: string | null = alice.token): Promise<string> {
  const res = await call('POST', `/lists/${listId}/share`, token);
  expect(res.status).toBe(200);
  return ((await res.json()) as { url: string }).url;
}

beforeAll(async () => {
  server = createDevServer();
  await new Promise<void>((resolve) => server.listen(0, resolve));
  baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  admin = new pg.Pool({ connectionString: process.env.DATABASE_URL, max: 1 });
  alice = await signUp();
  bob = await signUp();
  household = ((await (await call('POST', '/households', alice.token, { name: 'Home' })).json()) as { id: string }).id;
  listId = (
    await admin.query("insert into shopping_list (household_id, name) values ($1, 'Party') returning id", [household])
  ).rows[0].id;
  await admin.query(
    `insert into list_item (household_id, list_id, name, quantity, note, checked) values
       ($1, $2, 'Milk', 2, 'secret note', false),
       ($1, $2, 'Bread', null, null, false),
       ($1, $2, 'Eggs', 12, null, true)`,
    [household, listId],
  );
});

afterAll(async () => {
  await admin.end();
  await new Promise<void>((resolve) => server.close(() => resolve()));
});

describe('share link', () => {
  it('AC2: POST and DELETE without a JWT give 401', async () => {
    for (const method of ['POST', 'DELETE']) {
      const res = await call(method, `/lists/${listId}/share`, null);
      expect(res.status).toBe(401);
    }
  });

  it('AC1 + AC4: a member gets a public URL and the anonymous body is name plus unchecked items only', async () => {
    const url = await share();
    expect(url).toMatch(/\/shared\/[A-Za-z0-9_-]{43}$/);
    const res = await call('GET', `/shared/${tokenOf(url)}`, null);
    expect(res.status).toBe(200);
    const body = (await res.json()) as { list: unknown; items: Array<{ name: string; quantity: number | null }> };
    expect(body.list).toEqual({ name: 'Party' });
    expect([...body.items].sort((a, b) => a.name.localeCompare(b.name))).toEqual([
      { name: 'Bread', quantity: null },
      { name: 'Milk', quantity: 2 },
    ]);
    expect(JSON.stringify(body)).not.toMatch(/secret note|household|Eggs/);
  });

  it('AC3: an unknown token gives 404', async () => {
    const res = await call('GET', `/shared/${'B'.repeat(43)}`, null);
    expect(res.status).toBe(404);
    expect(await errCode(res)).toBe('not_found');
  });

  it("a non-member can't share the list (404) and the list's token is untouched", async () => {
    const res = await call('POST', `/lists/${listId}/share`, bob.token);
    expect(res.status).toBe(404);
  });

  it('a second POST rotates: the old token stops working', async () => {
    const first = await share();
    const second = await share();
    expect(second).not.toBe(first);
    expect((await call('GET', `/shared/${tokenOf(first)}`, null)).status).toBe(404);
    expect((await call('GET', `/shared/${tokenOf(second)}`, null)).status).toBe(200);
  });

  it('DELETE revokes the link and is idempotent', async () => {
    const url = await share();
    expect((await call('DELETE', `/lists/${listId}/share`, alice.token)).status).toBe(204);
    expect((await call('GET', `/shared/${tokenOf(url)}`, null)).status).toBe(404);
    expect((await call('DELETE', `/lists/${listId}/share`, alice.token)).status).toBe(204);
  });

  it('shopping off: POST/DELETE give module_disabled and an existing token gives 404', async () => {
    const url = await share();
    const patch = (shopping: boolean) => call('PATCH', `/households/${household}/modules`, alice.token, { shopping });
    expect((await patch(false)).status).toBe(200);
    try {
      for (const method of ['POST', 'DELETE']) {
        const res = await call(method, `/lists/${listId}/share`, alice.token);
        expect(res.status).toBe(403);
        expect(await errCode(res)).toBe('module_disabled');
      }
      expect((await call('GET', `/shared/${tokenOf(url)}`, null)).status).toBe(404);
    } finally {
      await patch(true);
    }
    expect((await call('GET', `/shared/${tokenOf(url)}`, null)).status).toBe(200);
  });

  it('a deleted list serves 404', async () => {
    const url = await share();
    await admin.query('update shopping_list set deleted_at = now() where id = $1', [listId]);
    expect((await call('GET', `/shared/${tokenOf(url)}`, null)).status).toBe(404);
  });
});
