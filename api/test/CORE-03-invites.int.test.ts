import type { AddressInfo } from 'node:net';
import type { Server } from 'node:http';
import pg from 'pg';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { createDevServer } from '../scripts/dev-server.ts';

let server: Server;
let baseUrl: string;
let admin: pg.Pool;
let owner: { token: string; id: string };
let joiner: { token: string; id: string };
let householdId: string;

async function signUp(): Promise<{ token: string; id: string }> {
  const res = await fetch(`${process.env.SUPABASE_URL}/auth/v1/signup`, {
    method: 'POST',
    headers: { apikey: process.env.SUPABASE_ANON_KEY ?? '', 'content-type': 'application/json' },
    body: JSON.stringify({
      email: `core03-${crypto.randomUUID()}@example.com`,
      password: crypto.randomUUID(),
    }),
  });
  if (!res.ok) throw new Error(`signup failed: ${res.status} ${await res.text()}`);
  const body = (await res.json()) as { access_token: string; user: { id: string } };
  return { token: body.access_token, id: body.user.id };
}

const post = (path: string, token: string | null, body?: unknown) =>
  fetch(`${baseUrl}${path}`, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      ...(token ? { authorization: `Bearer ${token}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });

const createInvite = (token: string | null, body: unknown = {}, id = householdId) =>
  post(`/households/${id}/invites`, token, body);
const accept = (token: string | null, code: string) => post(`/invites/${code}/accept`, token);

const errCode = async (res: Response) =>
  ((await res.json()) as { error: { code: string } }).error.code;

const memberCount = async (userId: string) =>
  Number(
    (await admin.query('select count(*) from member where user_id = $1', [userId])).rows[0].count,
  );

beforeAll(async () => {
  server = createDevServer();
  await new Promise<void>((resolve) => server.listen(0, resolve));
  baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  admin = new pg.Pool({ connectionString: process.env.DATABASE_URL, max: 1 });
  owner = await signUp();
  joiner = await signUp();
  const res = await post('/households', owner.token, { name: 'Home' });
  householdId = ((await res.json()) as { id: string }).id;
});

afterAll(async () => {
  await admin.end();
  await new Promise<void>((resolve) => server.close(() => resolve()));
});

describe('POST /households/{id}/invites', () => {
  it('AC1: owner gets a code with the DB defaults, and custom values are honoured', async () => {
    const res = await createInvite(owner.token);
    expect(res.status).toBe(201);
    const inv = (await res.json()) as Record<string, unknown>;
    expect(inv).toMatchObject({ role: 'member', max_uses: 10, uses: 0, household_id: householdId });
    expect(inv.code).toMatch(/^[0-9a-f]{12}$/);
    const days = (Date.parse(inv.expires_at as string) - Date.now()) / 86_400_000;
    expect(days).toBeGreaterThan(6.9);
    expect(days).toBeLessThan(7.1);

    const custom = (await (
      await createInvite(owner.token, { role: 'owner', expires_in_days: 2, max_uses: 3 })
    ).json()) as Record<string, unknown>;
    expect(custom).toMatchObject({ role: 'owner', max_uses: 3 });
    const customDays = (Date.parse(custom.expires_at as string) - Date.now()) / 86_400_000;
    expect(customDays).toBeGreaterThan(1.9);
    expect(customDays).toBeLessThan(2.1);
  });

  it('AC1: a member can create a member invite but not an owner invite', async () => {
    const { code } = (await (await createInvite(owner.token)).json()) as { code: string };
    expect((await accept(joiner.token, code)).status).toBe(200);

    expect((await createInvite(joiner.token)).status).toBe(201);
    const res = await createInvite(joiner.token, { role: 'owner' });
    expect(res.status).toBe(403);
    expect(await errCode(res)).toBe('forbidden');
  });

  it('non-members get 403', async () => {
    const outsider = await signUp();
    const res = await createInvite(outsider.token);
    expect(res.status).toBe(403);
    expect(await errCode(res)).toBe('forbidden');
  });

  it('AC2: no or garbage token gives 401 unauthorized', async () => {
    for (const auth of [null, 'garbage']) {
      const res = await createInvite(auth);
      expect(res.status).toBe(401);
      expect(await errCode(res)).toBe('unauthorized');
    }
  });
});

describe('POST /invites/{code}/accept', () => {
  it('AC2: no or garbage token gives 401 unauthorized', async () => {
    for (const auth of [null, 'garbage']) {
      const res = await accept(auth, '0123456789ab');
      expect(res.status).toBe(401);
      expect(await errCode(res)).toBe('unauthorized');
    }
  });

  it('AC3: unknown or expired code gives 400 invalid_invite and no membership', async () => {
    const user = await signUp();
    const unknown = await accept(user.token, '0123456789ab');
    expect(unknown.status).toBe(400);
    expect(await errCode(unknown)).toBe('invalid_invite');

    const { code } = (await (await createInvite(owner.token)).json()) as { code: string };
    await admin.query("update invite set expires_at = now() - interval '1 day' where code = $1", [code]);
    const expired = await accept(user.token, code);
    expect(expired.status).toBe(400);
    expect(await errCode(expired)).toBe('invalid_invite');
    expect(await memberCount(user.id)).toBe(0);
  });

  it('AC4: a live code joins the caller as a member and counts a use', async () => {
    const user = await signUp();
    const { code } = (await (await createInvite(owner.token)).json()) as { code: string };
    const res = await accept(user.token, code);
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ household_id: householdId });
    const m = await admin.query(
      'select role, left_at from member where household_id = $1 and user_id = $2',
      [householdId, user.id],
    );
    expect(m.rows).toEqual([{ role: 'member', left_at: null }]);
    const inv = await admin.query('select uses from invite where code = $1', [code]);
    expect(inv.rows[0].uses).toBe(1);
  });
});
