import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { verifyJwt } from '../_lib/auth.ts';
import { closePool, withUser } from '../_lib/db.ts';
import { errorResponse, json } from '../_lib/http.ts';

// Fixture protected handler: not routed, not deployed.
async function handler(req: Request): Promise<Response> {
  try {
    const user = await verifyJwt(req);
    const rows = await withUser(user, async (c) => (await c.query<{ id: string }>('select id from profile')).rows);
    return json({ sub: user.sub, profileIds: rows.map((r) => r.id) });
  } catch (e) {
    return errorResponse(e);
  }
}

const url = process.env.SUPABASE_URL!;
const serviceKey = process.env.SUPABASE_SERVICE_ROLE_KEY!;
const password = `pw-${crypto.randomUUID()}`;
const created: string[] = [];

async function createUser(email: string): Promise<string> {
  const res = await fetch(`${url}/auth/v1/admin/users`, {
    method: 'POST',
    headers: { apikey: serviceKey, authorization: `Bearer ${serviceKey}`, 'content-type': 'application/json' },
    body: JSON.stringify({ email, password, email_confirm: true }),
  });
  if (!res.ok) throw new Error(`create user failed: ${res.status} ${await res.text()}`);
  const id = ((await res.json()) as { id: string }).id;
  created.push(id);
  return id;
}

async function signIn(email: string): Promise<string> {
  const res = await fetch(`${url}/auth/v1/token?grant_type=password`, {
    method: 'POST',
    headers: { apikey: serviceKey, 'content-type': 'application/json' },
    body: JSON.stringify({ email, password }),
  });
  if (!res.ok) throw new Error(`sign in failed: ${res.status} ${await res.text()}`);
  return ((await res.json()) as { access_token: string }).access_token;
}

beforeAll(() => {
  if (!serviceKey) throw new Error('SUPABASE_SERVICE_ROLE_KEY missing: see api/README.md');
});

afterAll(async () => {
  await Promise.all(
    created.map((id) =>
      fetch(`${url}/auth/v1/admin/users/${id}`, {
        method: 'DELETE',
        headers: { apikey: serviceKey, authorization: `Bearer ${serviceKey}` },
      }),
    ),
  );
  await closePool();
});

describe('protected handler', () => {
  it('CORE-01: AC1 missing Authorization header is 401', async () => {
    const res = await handler(new Request('http://localhost/x'));
    expect(res.status).toBe(401);
    expect(await res.json()).toMatchObject({ error: { code: 'unauthorized' } });
  });

  it('CORE-01: AC3 valid Supabase JWT passes RLS as that user', async () => {
    const tag = crypto.randomUUID();
    const id1 = await createUser(`core01-a-${tag}@example.test`);
    await createUser(`core01-b-${tag}@example.test`);
    const token = await signIn(`core01-a-${tag}@example.test`);

    const res = await handler(new Request('http://localhost/x', { headers: { authorization: `Bearer ${token}` } }));
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ sub: id1, profileIds: [id1] });
  });
});
