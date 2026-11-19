import type { AddressInfo } from 'node:net';
import type { Server } from 'node:http';
import pg from 'pg';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { createDevServer } from '../scripts/dev-server.ts';

type User = { token: string; id: string };
type Signed = { attachment_id: string; upload_url: string; storage_path: string };

let server: Server;
let baseUrl: string;
let admin: pg.Pool;
let createdStub = false;
let alice: User;
let bob: User;
let aliceHousehold: string;
let bobHousehold: string;
let alicePantryItem: string;
let bobPantryItem: string;

async function signUp(): Promise<User> {
  const res = await fetch(`${process.env.SUPABASE_URL}/auth/v1/signup`, {
    method: 'POST',
    headers: { apikey: process.env.SUPABASE_ANON_KEY ?? '', 'content-type': 'application/json' },
    body: JSON.stringify({
      email: `core11-${crypto.randomUUID()}@example.com`,
      password: crypto.randomUUID(),
    }),
  });
  if (!res.ok) throw new Error(`signup failed: ${res.status} ${await res.text()}`);
  const body = (await res.json()) as { access_token: string; user: { id: string } };
  return { token: body.access_token, id: body.user.id };
}

const call = (method: string, path: string, token: string | null, body?: unknown) =>
  fetch(`${baseUrl}${path}`, {
    method,
    headers: {
      'content-type': 'application/json',
      ...(token ? { authorization: `Bearer ${token}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });

const sign = (token: string | null, over: Record<string, unknown> = {}) =>
  call('POST', '/attachments/sign', token, {
    household_id: aliceHousehold,
    entity: 'pantry_item',
    entity_id: alicePantryItem,
    filename: 'milk.jpg',
    mime: 'image/jpeg',
    size: 1234,
    ...over,
  });

const errCode = async (res: Response) =>
  ((await res.json()) as { error: { code: string } }).error.code;

const attachmentCount = async () =>
  Number((await admin.query('select count(*) from attachment')).rows[0].count);

async function createHousehold(user: User): Promise<string> {
  const res = await call('POST', '/households', user.token, { name: 'Home' });
  return ((await res.json()) as { id: string }).id;
}

async function seedPantryItem(householdId: string): Promise<string> {
  const id = crypto.randomUUID();
  await admin.query('insert into pantry_item (id, household_id, name) values ($1, $2, $3)', [
    id,
    householdId,
    'Milk',
  ]);
  return id;
}

beforeAll(async () => {
  server = createDevServer();
  await new Promise<void>((resolve) => server.listen(0, resolve));
  baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  admin = new pg.Pool({ connectionString: process.env.DATABASE_URL, max: 1 });

  // pantry_item has no migration yet (PANT stories); stub just enough for AC1 and AC4.
  const exists = (await admin.query("select to_regclass('public.pantry_item') as reg")).rows[0].reg;
  if (!exists) {
    await admin.query(`create table pantry_item (
      id uuid primary key default gen_random_uuid(),
      household_id uuid not null references household(id) on delete cascade,
      name text not null,
      created_at timestamptz not null default now(),
      updated_at timestamptz not null default now(),
      deleted_at timestamptz,
      sync_seq bigint not null default nextval('sync_seq_global'))`);
    await admin.query("call setup_scoped('pantry_item')");
    createdStub = true;
  }

  alice = await signUp();
  bob = await signUp();
  aliceHousehold = await createHousehold(alice);
  bobHousehold = await createHousehold(bob);
  alicePantryItem = await seedPantryItem(aliceHousehold);
  bobPantryItem = await seedPantryItem(bobHousehold);
});

afterAll(async () => {
  if (createdStub) await admin.query('drop table pantry_item cascade');
  await admin.end();
  await new Promise<void>((resolve) => server.close(() => resolve()));
});

describe('POST /attachments/sign', () => {
  it('AC2: no or garbage JWT gives 401', async () => {
    for (const token of [null, 'garbage']) {
      const res = await sign(token);
      expect(res.status).toBe(401);
      expect(await errCode(res)).toBe('unauthorized');
    }
  });

  it('AC3: a bad mime gives 400 and the attachment count is unchanged', async () => {
    const before = await attachmentCount();
    const res = await sign(alice.token, { mime: 'image/gif' });
    expect(res.status).toBe(400);
    expect(await errCode(res)).toBe('validation');
    expect(await attachmentCount()).toBe(before);
  });

  it("AC4: another household's pantry item gives 403 and no row", async () => {
    const before = await attachmentCount();
    const res = await sign(alice.token, { household_id: bobHousehold, entity_id: bobPantryItem });
    expect(res.status).toBe(403);
    expect(await errCode(res)).toBe('forbidden');
    // Right household id claimed for someone else's item is the same 403.
    const mixed = await sign(alice.token, { entity_id: bobPantryItem });
    expect(mixed.status).toBe(403);
    expect(await attachmentCount()).toBe(before);
  });

  it('AC1: a member signs a JPEG, gets a row, and can PUT bytes to upload_url', async () => {
    const res = await sign(alice.token);
    expect(res.status).toBe(201);
    const out = (await res.json()) as Signed;
    expect(out.storage_path.startsWith(`households/${aliceHousehold}/pantry_item/${alicePantryItem}/`)).toBe(true);

    const row = (await admin.query('select * from attachment where id = $1', [out.attachment_id])).rows[0];
    expect(row).toMatchObject({
      household_id: aliceHousehold,
      entity: 'pantry_item',
      entity_id: alicePantryItem,
      storage_path: out.storage_path,
      mime: 'image/jpeg',
      created_by: alice.id,
    });
    expect(Number(row.size_bytes)).toBe(1234);

    const put = await fetch(out.upload_url, {
      method: 'PUT',
      headers: { 'content-type': 'image/jpeg' },
      body: new Uint8Array([0xff, 0xd8, 0xff, 0xd9]),
    });
    expect(put.ok).toBe(true);
  });

  it('module off: pantry disabled gives 403 module_disabled and no row', async () => {
    const off = await call('PATCH', `/households/${aliceHousehold}/modules`, alice.token, { pantry: false });
    expect(off.status).toBe(200);
    const before = await attachmentCount();
    const res = await sign(alice.token);
    expect(res.status).toBe(403);
    expect(await errCode(res)).toBe('module_disabled');
    expect(await attachmentCount()).toBe(before);
  });
});
