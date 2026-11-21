import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../_lib/auth.ts', async () => {
  const { ApiError } = await import('../_lib/errors.ts');
  return {
    verifyJwt: vi.fn(async (req: Request) => {
      if (!req.headers.get('authorization')) throw new ApiError(401, 'unauthorized', 'no token');
      return { sub: 'user-1', claims: { sub: 'user-1' }, token: 'tok' };
    }),
  };
});
vi.mock('../_lib/db.ts', () => ({ withUser: vi.fn() }));
const connect = vi.fn();
vi.mock('pg', () => ({ default: { Pool: vi.fn(() => ({ connect })) } }));

import shareHandler from '../lists/[id]/share.ts';
import sharedHandler from '../shared/[token].ts';
import { withUser } from '../_lib/db.ts';
import { createShare, loadShared, readShared, revokeShare, toPublicList } from '../_lib/shares.ts';

const LIST = '8f6f3b9e-1c1e-4a55-9a52-0d2c5d9f7a11';
const TOKEN = 'A'.repeat(43);

const errCode = async (res: Response) => ((await res.json()) as { error: { code: string } }).error.code;

function fakeClient(...answers: Array<Array<Record<string, unknown>>>) {
  const queries: Array<{ text: string; values?: unknown[] }> = [];
  return {
    queries,
    query: vi.fn(async (text: string, values?: unknown[]) => {
      queries.push({ text, values });
      return { rows: answers.shift() ?? [] };
    }),
  };
}

beforeEach(() => {
  vi.mocked(withUser).mockClear();
  connect.mockClear();
  process.env.API_BASE_URL = 'https://app.example.com/';
});

describe('toPublicList', () => {
  it('AC4: drops checked items and every field but name and quantity', () => {
    const out = toPublicList('Groceries', [
      { name: 'Milk', quantity: '2', checked: false, household_id: 'h1', id: 'i1' } as never,
      { name: 'Bread', quantity: null, checked: false },
      { name: 'Eggs', quantity: 12, checked: true },
    ]);
    expect(out).toEqual({
      list: { name: 'Groceries' },
      items: [
        { name: 'Milk', quantity: 2 },
        { name: 'Bread', quantity: null },
      ],
    });
  });
});

describe('share routes', () => {
  it.each(['POST', 'DELETE'])('AC2: %s without a JWT gives 401 and no DB call', async (method) => {
    const res = await shareHandler(new Request(`http://x/lists/${LIST}/share`, { method }));
    expect(res.status).toBe(401);
    expect(await errCode(res)).toBe('unauthorized');
    expect(withUser).not.toHaveBeenCalled();
  });

  it('a non-UUID list id gives 400 before any DB call', async () => {
    const res = await shareHandler(
      new Request('http://x/lists/nope/share', { method: 'POST', headers: { authorization: 'Bearer t' } }),
    );
    expect(res.status).toBe(400);
    expect(withUser).not.toHaveBeenCalled();
  });

  it('GET /shared/{token} needs no JWT; a malformed token is 404 and takes no connection', async () => {
    const res = await sharedHandler(new Request('http://x/shared/short'));
    expect(res.status).toBe(404);
    expect(await errCode(res)).toBe('not_found');
    expect(connect).not.toHaveBeenCalled();
    await expect(readShared('x'.repeat(44))).rejects.toMatchObject({ status: 404 });
    expect(connect).not.toHaveBeenCalled();
  });
});

describe('share logic', () => {
  it('AC1: createShare writes a new token and returns the public URL', async () => {
    const client = fakeClient([{ enabled: true }], [{ id: LIST }]);
    const { url } = await createShare(client, LIST);
    expect(url).toMatch(/^https:\/\/app\.example\.com\/shared\/[A-Za-z0-9_-]{43}$/);
    expect(client.queries[1]?.values?.[0]).toBe(url.split('/').pop());
  });

  it('two creates give different tokens (rotation)', async () => {
    const a = await createShare(fakeClient([{ enabled: true }], [{ id: LIST }]), LIST);
    const b = await createShare(fakeClient([{ enabled: true }], [{ id: LIST }]), LIST);
    expect(a.url).not.toBe(b.url);
  });

  it('an invisible list is 404 and shopping off is module_disabled, with no update', async () => {
    const missing = fakeClient([]);
    await expect(createShare(missing, LIST)).rejects.toMatchObject({ status: 404 });
    const off = fakeClient([{ enabled: false }]);
    await expect(revokeShare(off, LIST)).rejects.toMatchObject({ status: 403, code: 'module_disabled' });
    expect(off.queries).toHaveLength(1);
  });

  it('revokeShare clears the token', async () => {
    const client = fakeClient([{ enabled: true }], []);
    await revokeShare(client, LIST);
    expect(client.queries[1]?.text).toContain('share_token = null');
  });

  it('AC3: an unknown token is 404', async () => {
    await expect(loadShared(fakeClient([]), TOKEN)).rejects.toMatchObject({ status: 404 });
  });

  it('orders by manual position, then learned aisle order once store_item_order exists', async () => {
    const withTable = fakeClient([{ id: 'l1', name: 'G' }], [{ reg: 'store_item_order' }], []);
    await loadShared(withTable, TOKEN);
    expect(withTable.queries[2]?.text).toMatch(/order by i\.manual_position nulls last, o\.mean_position nulls last/);
    const without = fakeClient([{ id: 'l1', name: 'G' }], [{ reg: null }], []);
    await loadShared(without, TOKEN);
    expect(without.queries[2]?.text).not.toMatch(/store_item_order/);
  });

  it('AC4: a live token returns name and unchecked items', async () => {
    const client = fakeClient([{ id: 'l1', name: 'Groceries' }], [{ reg: null }], [{ name: 'Milk', quantity: '1.5', checked: false }]);
    expect(await loadShared(client, TOKEN)).toEqual({
      list: { name: 'Groceries' },
      items: [{ name: 'Milk', quantity: 1.5 }],
    });
  });
});
