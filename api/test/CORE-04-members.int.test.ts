import type { AddressInfo } from 'node:net';
import type { Server } from 'node:http';
import pg from 'pg';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { createDevServer } from '../scripts/dev-server.ts';

type User = { token: string; id: string };

let server: Server;
let baseUrl: string;
let admin: pg.Pool;

async function signUp(): Promise<User> {
  const res = await fetch(`${process.env.SUPABASE_URL}/auth/v1/signup`, {
    method: 'POST',
    headers: { apikey: process.env.SUPABASE_ANON_KEY ?? '', 'content-type': 'application/json' },
    body: JSON.stringify({
      email: `core04-${crypto.randomUUID()}@example.com`,
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

const errCode = async (res: Response) =>
  ((await res.json()) as { error: { code: string } }).error.code;

// A fresh household: owner creates it, each joiner enters through a real invite.
async function setup(joiners: number) {
  const owner = await signUp();
  const res = await call('POST', '/households', owner.token, { name: 'Home' });
  const householdId = ((await res.json()) as { id: string }).id;
  const members: User[] = [];
  for (let i = 0; i < joiners; i++) {
    const user = await signUp();
    const inv = (await (
      await call('POST', `/households/${householdId}/invites`, owner.token, {})
    ).json()) as { code: string };
    expect((await call('POST', `/invites/${inv.code}/accept`, user.token)).status).toBe(200);
    members.push(user);
  }
  return { owner, members, householdId };
}

const remove = (hid: string, uid: string, token: string | null) =>
  call('DELETE', `/households/${hid}/members/${uid}`, token);

const memberRow = async (hid: string, uid: string) =>
  (
    await admin.query('select role, left_at from member where household_id = $1 and user_id = $2', [
      hid,
      uid,
    ])
  ).rows[0];

beforeAll(async () => {
  server = createDevServer();
  await new Promise<void>((resolve) => server.listen(0, resolve));
  baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  admin = new pg.Pool({ connectionString: process.env.DATABASE_URL, max: 1 });
});

afterAll(async () => {
  await admin.end();
  await new Promise<void>((resolve) => server.close(() => resolve()));
});

describe('DELETE /households/{id}/members/{user_id}', () => {
  it('AC1: an owner removes a member: 204 and left_at is set', async () => {
    const { owner, members, householdId } = await setup(1);
    const res = await remove(householdId, members[0]!.id, owner.token);
    expect(res.status).toBe(204);
    expect((await memberRow(householdId, members[0]!.id)).left_at).not.toBeNull();
  });

  it('AC2: no or garbage token gives 401 unauthorized', async () => {
    for (const auth of [null, 'garbage']) {
      const res = await remove(crypto.randomUUID(), crypto.randomUUID(), auth);
      expect(res.status).toBe(401);
      expect(await errCode(res)).toBe('unauthorized');
    }
  });

  it('AC3: an unknown user id gives 404 not_found', async () => {
    const { owner, householdId } = await setup(0);
    const res = await remove(householdId, crypto.randomUUID(), owner.token);
    expect(res.status).toBe(404);
    expect(await errCode(res)).toBe('not_found');
  });

  it('AC3: a household the caller is not in gives 404 not_found', async () => {
    const { members, householdId } = await setup(1);
    const outsider = await signUp();
    const res = await remove(householdId, members[0]!.id, outsider.token);
    expect(res.status).toBe(404);
    expect((await memberRow(householdId, members[0]!.id)).left_at).toBeNull();
  });

  it('AC3: removing an already-removed member gives 404', async () => {
    const { owner, members, householdId } = await setup(1);
    expect((await remove(householdId, members[0]!.id, owner.token)).status).toBe(204);
    expect((await remove(householdId, members[0]!.id, owner.token)).status).toBe(404);
  });

  it('AC4: the sole owner cannot leave: 409 last_owner and the row is unchanged', async () => {
    const { owner, householdId } = await setup(0);
    const res = await remove(householdId, owner.id, owner.token);
    expect(res.status).toBe(409);
    expect(await errCode(res)).toBe('last_owner');
    expect(await memberRow(householdId, owner.id)).toEqual({ role: 'owner', left_at: null });
  });

  it('a member can leave themselves: 204', async () => {
    const { members, householdId } = await setup(1);
    const res = await remove(householdId, members[0]!.id, members[0]!.token);
    expect(res.status).toBe(204);
    expect((await memberRow(householdId, members[0]!.id)).left_at).not.toBeNull();
  });

  it('a member cannot remove another member: 403 and nothing changes', async () => {
    const { members, householdId } = await setup(2);
    const res = await remove(householdId, members[1]!.id, members[0]!.token);
    expect(res.status).toBe(403);
    expect(await errCode(res)).toBe('forbidden');
    expect((await memberRow(householdId, members[1]!.id)).left_at).toBeNull();
  });
});
