import type { AddressInfo } from 'node:net';
import type { Server } from 'node:http';
import pg from 'pg';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { createDevServer } from '../scripts/dev-server.ts';

let server: Server;
let baseUrl: string;
let admin: pg.Pool;
let owner: { token: string; id: string };
let member: { token: string; id: string };
let householdId: string;

async function signUp(): Promise<{ token: string; id: string }> {
  const res = await fetch(`${process.env.SUPABASE_URL}/auth/v1/signup`, {
    method: 'POST',
    headers: { apikey: process.env.SUPABASE_ANON_KEY ?? '', 'content-type': 'application/json' },
    body: JSON.stringify({
      email: `core05-${crypto.randomUUID()}@example.com`,
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

const patchModules = (token: string | null, body: unknown, id = householdId) =>
  call('PATCH', `/households/${id}/modules`, token, body);

const errCode = async (res: Response) =>
  ((await res.json()) as { error: { code: string } }).error.code;

const flags = async () =>
  (
    await admin.query(
      'select module_shopping, module_pantry, module_maintenance from household where id = $1',
      [householdId],
    )
  ).rows[0];

beforeAll(async () => {
  server = createDevServer();
  await new Promise<void>((resolve) => server.listen(0, resolve));
  baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  admin = new pg.Pool({ connectionString: process.env.DATABASE_URL, max: 1 });
  owner = await signUp();
  member = await signUp();
  const created = await call('POST', '/households', owner.token, { name: 'Home' });
  householdId = ((await created.json()) as { id: string }).id;
  const invite = await call('POST', `/households/${householdId}/invites`, owner.token, {});
  const { code } = (await invite.json()) as { code: string };
  const joined = await call('POST', `/invites/${code}/accept`, member.token);
  if (joined.status !== 200) throw new Error(`accept failed: ${joined.status}`);
});

afterAll(async () => {
  await admin.end();
  await new Promise<void>((resolve) => server.close(() => resolve()));
});

describe('PATCH /households/{id}/modules', () => {
  it('AC2: a non-owner member gets 403 forbidden and nothing changes', async () => {
    const before = await flags();
    const res = await patchModules(member.token, { pantry: false });
    expect(res.status).toBe(403);
    expect(await errCode(res)).toBe('forbidden');
    expect(await flags()).toEqual(before);
  });

  it('AC1: owner switches pantry off; other flags are unchanged', async () => {
    const res = await patchModules(owner.token, { pantry: false });
    expect(res.status).toBe(200);
    const h = (await res.json()) as Record<string, unknown>;
    expect(h).toMatchObject({
      id: householdId,
      module_pantry: false,
      module_shopping: true,
      module_maintenance: true,
    });
    expect(await flags()).toEqual({
      module_shopping: true,
      module_pantry: false,
      module_maintenance: true,
    });
  });

  it('AC3: no or garbage token gives 401 unauthorized', async () => {
    for (const auth of [null, 'garbage']) {
      const res = await patchModules(auth, { pantry: true });
      expect(res.status).toBe(401);
      expect(await errCode(res)).toBe('unauthorized');
    }
  });

  it('AC4: a body with no flags gives 400 and nothing changes', async () => {
    const before = await flags();
    const res = await patchModules(owner.token, {});
    expect(res.status).toBe(400);
    expect(await errCode(res)).toBe('validation');
    expect(await flags()).toEqual(before);
  });

  it('an outsider gets 404 not_found (RLS hides the household)', async () => {
    const outsider = await signUp();
    const res = await patchModules(outsider.token, { pantry: true });
    expect(res.status).toBe(404);
    expect(await errCode(res)).toBe('not_found');
  });
});
