import { beforeEach, describe, expect, it, vi } from 'vitest';
import { isSignAttachmentRequest } from '@abodenestly/contract';

vi.mock('../_lib/auth.ts', () => ({
  verifyJwt: vi.fn(async () => ({ sub: 'user-1', claims: { sub: 'user-1' }, token: 'tok' })),
}));
vi.mock('../_lib/db.ts', () => ({ withUser: vi.fn() }));

import handler from '../attachments/sign.ts';
import { sanitizeFilename, signAttachment, validateSignRequest } from '../_lib/attachments.ts';
import { withUser } from '../_lib/db.ts';
import { ApiError } from '../_lib/errors.ts';

const HID = '8f6f3b9e-1c1e-4a55-9a52-0d2c5d9f7a11';
const EID = '1b4e28ba-2fa1-41d2-883f-0016d3cca427';

const body = (over: Record<string, unknown> = {}) => ({
  household_id: HID,
  entity: 'pantry_item',
  entity_id: EID,
  filename: 'milk.jpg',
  mime: 'image/jpeg',
  size: 1000,
  ...over,
});

const post = (b: unknown) =>
  new Request('http://x/attachments/sign', {
    method: 'POST',
    headers: { authorization: 'Bearer t', 'content-type': 'application/json' },
    body: JSON.stringify(b),
  });

const errCode = async (res: Response) =>
  ((await res.json()) as { error: { code: string } }).error.code;

// Fake Queryable: answers queued rows in order and records every query.
function fakeClient(...answers: Array<Array<Record<string, unknown>>>) {
  const queries: string[] = [];
  return {
    queries,
    query: vi.fn(async (text: string) => {
      queries.push(text);
      return { rows: answers.shift() ?? [] };
    }),
  };
}

beforeEach(() => vi.mocked(withUser).mockClear());

describe('POST /attachments/sign validation', () => {
  it.each([['image/gif'], ['text/plain']])('AC3: mime %s gives 400 validation and no DB call', async (mime) => {
    const res = await handler(post(body({ mime })));
    expect(res.status).toBe(400);
    expect(await errCode(res)).toBe('validation');
    expect(withUser).not.toHaveBeenCalled();
  });

  it('AC3: a rejected request records zero queries on a fake client', () => {
    const client = fakeClient();
    expect(() => validateSignRequest(body({ mime: 'image/gif' }) as never)).toThrow(ApiError);
    expect(client.queries).toHaveLength(0);
  });

  it.each([
    ['oversize', { size: 25 * 1024 * 1024 + 1 }],
    ['zero size', { size: 0 }],
    ['non-uuid household', { household_id: 'nope' }],
    ['non-uuid entity', { entity_id: '123' }],
    ['empty filename', { filename: '' }],
    ['filename of only dots', { filename: '...' }],
  ])('%s gives 400 validation', async (_name, over) => {
    const res = await handler(post(body(over)));
    expect(res.status).toBe(400);
    expect(await errCode(res)).toBe('validation');
    expect(withUser).not.toHaveBeenCalled();
  });

  it('accepts the size boundaries', () => {
    expect(() => validateSignRequest(body({ size: 1 }) as never)).not.toThrow();
    expect(() => validateSignRequest(body({ size: 25 * 1024 * 1024 }) as never)).not.toThrow();
  });

  it('rejects wrong shapes and unknown entities at the guard', async () => {
    expect(isSignAttachmentRequest(body({ entity: 'household' }))).toBe(false);
    expect(isSignAttachmentRequest(body({ size: 1.5 }))).toBe(false);
    expect((await handler(post(body({ entity: 'household' })))).status).toBe(400);
  });

  it('405 for a non-POST', async () => {
    const res = await handler(new Request('http://x/attachments/sign', { method: 'GET' }));
    expect(res.status).toBe(405);
  });
});

describe('sanitizeFilename', () => {
  it('strips / and .. so the name is one path segment', () => {
    const safe = sanitizeFilename('../../etc/passwd');
    expect(safe).not.toMatch(/[/]|\.\./);
    expect(safe).toMatch(/^[A-Za-z0-9._-]+$/);
  });

  it('replaces unsafe characters and caps the length at 100', () => {
    expect(sanitizeFilename('my photo (1).jpg')).toBe('my_photo__1_.jpg');
    expect(sanitizeFilename('a'.repeat(300))).toHaveLength(100);
  });
});

describe('signAttachment with a fake client', () => {
  const input = validateSignRequest(body() as never);

  it('missing table gives 400 entity_unavailable before looking up the entity', async () => {
    const client = fakeClient([{ reg: null }]);
    await expect(signAttachment(client, 'u', 't', input)).rejects.toMatchObject({
      status: 400,
      code: 'entity_unavailable',
    });
    expect(client.queries).toHaveLength(1);
  });

  it('a household mismatch gives 403 forbidden', async () => {
    const client = fakeClient([{ reg: 'pantry_item' }], [{ household_id: 'other' }]);
    await expect(signAttachment(client, 'u', 't', input)).rejects.toMatchObject({ status: 403, code: 'forbidden' });
  });

  it('a missing entity gives the same 403 forbidden', async () => {
    const client = fakeClient([{ reg: 'pantry_item' }], []);
    await expect(signAttachment(client, 'u', 't', input)).rejects.toMatchObject({ status: 403, code: 'forbidden' });
  });

  it('a module that is off gives 403 module_disabled and inserts nothing', async () => {
    const client = fakeClient([{ reg: 'pantry_item' }], [{ household_id: HID }], [{ enabled: false }]);
    await expect(signAttachment(client, 'u', 't', input)).rejects.toMatchObject({
      status: 403,
      code: 'module_disabled',
    });
    expect(client.queries.some((q) => q.includes('insert into attachment'))).toBe(false);
  });
});
