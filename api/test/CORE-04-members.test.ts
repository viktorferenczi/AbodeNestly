import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../_lib/auth.ts', () => ({
  verifyJwt: vi.fn(async () => ({ sub: 'user-1', claims: { sub: 'user-1' } })),
}));
vi.mock('../_lib/db.ts', () => ({ withUser: vi.fn() }));

import handler from '../households/[id]/members/[user_id].ts';
import { withUser } from '../_lib/db.ts';
import { removeMember } from '../_lib/members.ts';
import { ApiError } from '../_lib/errors.ts';
import type { Queryable } from '../_lib/queryable.ts';

const HID = '8f6f3b9e-1c1e-4a55-9a52-0d2c5d9f7a11';
const UID = '2b1e7c64-5d3a-4c0e-9a0f-6d8e1f4a7b22';

const del = (hid = HID, uid = UID, method = 'DELETE') =>
  new Request(`http://x/households/${hid}/members/${uid}`, {
    method,
    headers: { authorization: 'Bearer t' },
  });

const errCode = async (res: Response) =>
  ((await res.json()) as { error: { code: string } }).error.code;

// Fake client: `select` answers with selectRows; `update` answers with updateRows or throws.
function fakeClient(selectRows: unknown[], update: unknown[] | Error) {
  const calls: string[] = [];
  const client: Queryable = {
    async query(text) {
      calls.push(text);
      if (text.startsWith('select')) return { rows: selectRows };
      if (update instanceof Error) throw update;
      return { rows: update };
    },
  };
  return { client, calls };
}

const pgError = (code: string, message: string) => Object.assign(new Error(message), { code });

beforeEach(() => vi.mocked(withUser).mockReset());

describe('DELETE /households/{id}/members/{user_id} routing', () => {
  it('maps success to 204 with no body', async () => {
    vi.mocked(withUser).mockResolvedValue(undefined);
    const res = await handler(del());
    expect(res.status).toBe(204);
    expect(await res.text()).toBe('');
  });

  it.each([
    ['household', 'not-a-uuid', UID],
    ['user', HID, 'not-a-uuid'],
  ])('rejects a non-UUID %s id with 400 validation before the DB', async (_n, hid, uid) => {
    const res = await handler(del(hid, uid));
    expect(res.status).toBe(400);
    expect(await errCode(res)).toBe('validation');
    expect(withUser).not.toHaveBeenCalled();
  });

  it.each(['GET', 'POST', 'PATCH'])('rejects %s with 405', async (method) => {
    expect((await handler(del(HID, UID, method))).status).toBe(405);
    expect(withUser).not.toHaveBeenCalled();
  });
});

describe('removeMember', () => {
  it('AC3: no visible row gives 404 not_found and never issues the UPDATE', async () => {
    const { client, calls } = fakeClient([], []);
    await expect(removeMember(client, HID, UID)).rejects.toMatchObject({
      status: 404,
      code: 'not_found',
    });
    expect(calls).toHaveLength(1);
    expect(calls.some((c) => c.startsWith('update'))).toBe(false);
  });

  it('soft-exits the member on success', async () => {
    const { client, calls } = fakeClient([{ role: 'member' }], [{ user_id: UID }]);
    await expect(removeMember(client, HID, UID)).resolves.toBeUndefined();
    expect(calls[1]).toMatch(/^update member set left_at = now\(\)/);
  });

  it('AC4: P0001 last_owner from the UPDATE becomes 409 last_owner', async () => {
    const { client } = fakeClient([{ role: 'owner' }], pgError('P0001', 'last_owner'));
    await expect(removeMember(client, HID, UID)).rejects.toMatchObject({
      status: 409,
      code: 'last_owner',
    });
  });

  it('does not map other P0001 errors to last_owner', async () => {
    const { client } = fakeClient([{ role: 'owner' }], pgError('P0001', 'something_else'));
    await expect(removeMember(client, HID, UID)).rejects.not.toBeInstanceOf(ApiError);
  });

  it('maps 42501 from the guard trigger to 403 forbidden', async () => {
    const { client } = fakeClient([{ role: 'member' }], pgError('42501', 'denied'));
    await expect(removeMember(client, HID, UID)).rejects.toMatchObject({
      status: 403,
      code: 'forbidden',
    });
  });

  it('maps a zero-row UPDATE (RLS filtered) to 403 forbidden', async () => {
    const { client } = fakeClient([{ role: 'member' }], []);
    await expect(removeMember(client, HID, UID)).rejects.toMatchObject({
      status: 403,
      code: 'forbidden',
    });
  });
});
