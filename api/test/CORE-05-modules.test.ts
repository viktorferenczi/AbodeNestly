import { beforeEach, describe, expect, it, vi } from 'vitest';
import { isUpdateModulesRequest } from '@abodenestly/contract';

vi.mock('../_lib/auth.ts', () => ({
  verifyJwt: vi.fn(async () => ({ sub: 'user-1', claims: { sub: 'user-1' } })),
}));
vi.mock('../_lib/db.ts', () => ({ withUser: vi.fn() }));

import handler from '../households/[id]/modules.ts';
import { withUser } from '../_lib/db.ts';

const HID = '8f6f3b9e-1c1e-4a55-9a52-0d2c5d9f7a11';

const patch = (body: string, id = HID) =>
  new Request(`http://x/households/${id}/modules`, {
    method: 'PATCH',
    headers: { authorization: 'Bearer t', 'content-type': 'application/json' },
    body,
  });

const errCode = async (res: Response) =>
  ((await res.json()) as { error: { code: string } }).error.code;

beforeEach(() => vi.mocked(withUser).mockClear());

describe('PATCH /households/{id}/modules validation', () => {
  it('AC4: empty body gives 400 validation and never touches the DB', async () => {
    const res = await handler(patch('{}'));
    expect(res.status).toBe(400);
    expect(await errCode(res)).toBe('validation');
    expect(withUser).not.toHaveBeenCalled();
  });

  it.each([['{"pantry":"false"}'], ['{"pantry":null}'], ['{"shopping":1}'], ['[]'], ['not json']])(
    'rejects body %s with 400 and never touches the DB',
    async (body) => {
      const res = await handler(patch(body));
      expect(res.status).toBe(400);
      expect(withUser).not.toHaveBeenCalled();
    },
  );

  it('rejects a non-UUID household id with 400', async () => {
    const res = await handler(patch('{"pantry":false}', 'not-a-uuid'));
    expect(res.status).toBe(400);
    expect(await errCode(res)).toBe('validation');
    expect(withUser).not.toHaveBeenCalled();
  });

  it.each(['GET', 'POST', 'PUT'])('rejects %s with 405', async (method) => {
    const res = await handler(new Request(`http://x/households/${HID}/modules`, { method }));
    expect(res.status).toBe(405);
  });
});

describe('isUpdateModulesRequest', () => {
  it.each([{}, { pantry: false }, { shopping: true, pantry: false, maintenance: true }])(
    'accepts %j',
    (v) => expect(isUpdateModulesRequest(v)).toBe(true),
  );
  it.each([null, [], 'x', { pantry: 'false' }, { pantry: null }, { shopping: 0 }, { maintenance: {} }])(
    'rejects %j',
    (v) => expect(isUpdateModulesRequest(v)).toBe(false),
  );
});
