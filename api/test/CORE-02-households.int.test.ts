import type { AddressInfo } from 'node:net';
import type { Server } from 'node:http';
import pg from 'pg';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { createDevServer } from '../scripts/dev-server.ts';

let server: Server;
let baseUrl: string;
let admin: pg.Pool;
let token: string;
let userId: string;

async function signUp(): Promise<{ token: string; id: string }> {
  const res = await fetch(`${process.env.SUPABASE_URL}/auth/v1/signup`, {
    method: 'POST',
    headers: { apikey: process.env.SUPABASE_ANON_KEY ?? '', 'content-type': 'application/json' },
    body: JSON.stringify({
      email: `core02-${crypto.randomUUID()}@example.com`,
      password: crypto.randomUUID(),
    }),
  });
  if (!res.ok) throw new Error(`signup failed: ${res.status} ${await res.text()}`);
  const body = (await res.json()) as { access_token: string; user: { id: string } };
  return { token: body.access_token, id: body.user.id };
}

const create = (name: unknown, auth: string | null = `Bearer ${token}`) =>
  fetch(`${baseUrl}/households`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', ...(auth ? { authorization: auth } : {}) },
    body: JSON.stringify({ name }),
  });

const count = async () =>
  Number(
    (await admin.query('select count(*) from member where user_id = $1', [userId])).rows[0].count,
  );

beforeAll(async () => {
  server = createDevServer();
  await new Promise<void>((resolve) => server.listen(0, resolve));
  baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  admin = new pg.Pool({ connectionString: process.env.DATABASE_URL, max: 1 });
  ({ token, id: userId } = await signUp());
});

afterAll(async () => {
  await admin.end();
  await new Promise<void>((resolve) => server.close(() => resolve()));
});

describe('POST /households', () => {
  it('AC1: creates the household and makes the caller owner', async () => {
    const res = await create('Home');
    expect(res.status).toBe(201);
    const h = (await res.json()) as Record<string, unknown>;
    expect(h).toMatchObject({
      name: 'Home',
      created_by: userId,
      module_shopping: true,
      module_pantry: true,
      module_maintenance: true,
    });
    expect(h.id).toEqual(expect.any(String));
    expect(h).not.toHaveProperty('household_id');
    const m = await admin.query('select role from member where household_id = $1 and user_id = $2', [h.id, userId]);
    expect(m.rows).toEqual([{ role: 'owner' }]);

    // AC4: defaults seeded
    const lists = await admin.query('select name from shopping_list where household_id = $1', [h.id]);
    expect(lists.rows.map((r) => r.name)).toEqual(['Groceries']);
    const locs = await admin.query('select name from location where household_id = $1 order by name', [h.id]);
    expect(locs.rows.map((r) => r.name)).toEqual(['Freezer', 'Fridge', 'Pantry']);
  });

  it('AC2: missing or garbage token gives 401 unauthorized', async () => {
    for (const auth of [null, 'Bearer garbage', 'Basic abc']) {
      const res = await create('Home', auth);
      expect(res.status).toBe(401);
      expect(((await res.json()) as { error: { code: string } }).error.code).toBe('unauthorized');
    }
  });

  it('AC3: empty name gives 400 and creates nothing', async () => {
    const before = await count();
    const res = await create('   ');
    expect(res.status).toBe(400);
    expect(((await res.json()) as { error: { code: string } }).error.code).toBe('validation');
    expect(await count()).toBe(before);
  });
});
