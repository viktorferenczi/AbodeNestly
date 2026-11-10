import { beforeEach, describe, expect, it, vi } from 'vitest';
import { isCreateHouseholdRequest } from '@abodenestly/contract';

vi.mock('../_lib/auth.ts', () => ({
  verifyJwt: vi.fn(async () => ({ sub: 'user-1', claims: { sub: 'user-1' } })),
}));
vi.mock('../_lib/db.ts', () => ({ withUser: vi.fn() }));

import handler from '../households/index.ts';
import { withUser } from '../_lib/db.ts';

const post = (body: unknown) =>
  new Request('http://x/households', {
    method: 'POST',
    headers: { authorization: 'Bearer t', 'content-type': 'application/json' },
    body: JSON.stringify(body),
  });

beforeEach(() => vi.mocked(withUser).mockClear());

describe('POST /households validation (CORE-02 AC3)', () => {
  it.each(['', '   '])('rejects name %j with 400 validation and never touches the DB', async (name) => {
    const res = await handler(post({ name }));
    expect(res.status).toBe(400);
    expect(((await res.json()) as { error: { code: string } }).error.code).toBe('validation');
    expect(withUser).not.toHaveBeenCalled();
  });

  it('rejects non-JSON and wrong-shape bodies with 400', async () => {
    const bad = new Request('http://x/households', {
      method: 'POST',
      headers: { authorization: 'Bearer t' },
      body: 'nope',
    });
    expect((await handler(bad)).status).toBe(400);
    expect((await handler(post({ name: 5 }))).status).toBe(400);
    expect(withUser).not.toHaveBeenCalled();
  });

  it('rejects non-POST with 405', async () => {
    expect((await handler(new Request('http://x/households'))).status).toBe(405);
  });
});

describe('isCreateHouseholdRequest', () => {
  it('accepts a string name', () => expect(isCreateHouseholdRequest({ name: 'Home' })).toBe(true));
  it.each([null, [], 'x', {}, { name: 1 }, { name: null }])('rejects %j', (v) => {
    expect(isCreateHouseholdRequest(v)).toBe(false);
  });
});
