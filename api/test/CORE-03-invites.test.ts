import { beforeEach, describe, expect, it, vi } from 'vitest';
import { isCreateInviteRequest } from '@abodenestly/contract';

vi.mock('../_lib/auth.ts', () => ({
  verifyJwt: vi.fn(async () => ({ sub: 'user-1', claims: { sub: 'user-1' } })),
}));
vi.mock('../_lib/db.ts', () => ({ withUser: vi.fn() }));

import createHandler from '../households/[id]/invites.ts';
import acceptHandler from '../invites/[code]/accept.ts';
import { withUser } from '../_lib/db.ts';

const HID = '8f6f3b9e-1c1e-4a55-9a52-0d2c5d9f7a11';

const create = (body: unknown, id = HID) =>
  new Request(`http://x/households/${id}/invites`, {
    method: 'POST',
    headers: { authorization: 'Bearer t', 'content-type': 'application/json' },
    body: JSON.stringify(body),
  });

const accept = (code: string) =>
  new Request(`http://x/invites/${code}/accept`, {
    method: 'POST',
    headers: { authorization: 'Bearer t' },
  });

const errCode = async (res: Response) =>
  ((await res.json()) as { error: { code: string } }).error.code;

beforeEach(() => vi.mocked(withUser).mockClear());

describe('POST /invites/{code}/accept (CORE-03 AC3)', () => {
  it.each(['abc', 'ABCDEF012345', '0123456789abc', 'zzzzzzzzzzzz'])(
    'rejects malformed code %j with 400 invalid_invite before the DB',
    async (code) => {
      const res = await acceptHandler(accept(code));
      expect(res.status).toBe(400);
      expect(await errCode(res)).toBe('invalid_invite');
      expect(withUser).not.toHaveBeenCalled();
    },
  );

  it('rejects non-POST with 405', async () => {
    expect((await acceptHandler(new Request('http://x/invites/0123456789ab/accept'))).status).toBe(405);
  });
});

describe('POST /households/{id}/invites validation', () => {
  it.each([
    [{ expires_in_days: 0 }],
    [{ expires_in_days: 366 }],
    [{ max_uses: 0 }],
    [{ max_uses: 1001 }],
    [{ max_uses: 1.5 }],
    [{ expires_in_days: '7' }],
    [{ role: 'admin' }],
  ])('rejects %j with 400 validation and never touches the DB', async (body) => {
    const res = await createHandler(create(body));
    expect(res.status).toBe(400);
    expect(await errCode(res)).toBe('validation');
    expect(withUser).not.toHaveBeenCalled();
  });

  it('rejects a non-UUID household id with 400 validation', async () => {
    const res = await createHandler(create({}, 'not-a-uuid'));
    expect(res.status).toBe(400);
    expect(await errCode(res)).toBe('validation');
    expect(withUser).not.toHaveBeenCalled();
  });

  it('rejects non-POST with 405', async () => {
    expect((await createHandler(new Request(`http://x/households/${HID}/invites`))).status).toBe(405);
  });
});

describe('isCreateInviteRequest', () => {
  it.each([{}, { role: 'owner' }, { role: 'member', expires_in_days: 3, max_uses: 5 }])(
    'accepts %j',
    (v) => expect(isCreateInviteRequest(v)).toBe(true),
  );
  it.each([null, [], 'x', { role: 'admin' }, { max_uses: 1.5 }, { expires_in_days: '7' }, { max_uses: null }])(
    'rejects %j',
    (v) => expect(isCreateInviteRequest(v)).toBe(false),
  );
});
