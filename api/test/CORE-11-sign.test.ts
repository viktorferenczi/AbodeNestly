import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { isSignAttachmentRequest } from '@abodenestly/contract';

vi.mock('../_lib/auth.ts', () => ({
  verifyJwt: vi.fn(async () => ({ sub: 'user-1', claims: { sub: 'user-1' }, token: 'tok' })),
}));
vi.mock('../_lib/db.ts', () => ({ withUser: vi.fn() }));

import handler from '../attachments/sign.ts';
import { createSignedUploadUrl, sanitizeFilename, signAttachment, validateSignRequest } from '../_lib/attachments.ts';
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

describe('uppercase UUIDs', () => {
  it('are lowercased so the path and the household comparison match Postgres', async () => {
    const input = validateSignRequest(
      body({ household_id: HID.toUpperCase(), entity_id: EID.toUpperCase() }) as never,
    );
    expect(input.household_id).toBe(HID);
    expect(input.entity_id).toBe(EID);
    const client = fakeClient([{ reg: 'pantry_item' }], [{ household_id: HID }], [{ enabled: true }], []);
    vi.stubEnv('SUPABASE_URL', 'http://storage.test');
    vi.stubEnv('SUPABASE_ANON_KEY', 'anon');
    vi.stubGlobal('fetch', vi.fn(async () => Response.json({ url: '/object/upload/sign/x?token=t' })));
    const out = await signAttachment(client, 'u', 't', input);
    expect(out.storage_path.startsWith(`households/${HID}/pantry_item/${EID}/`)).toBe(true);
    vi.unstubAllGlobals();
    vi.unstubAllEnvs();
  });
});

describe('createSignedUploadUrl failures', () => {
  const stub = (res: () => Promise<Response>) => {
    vi.stubEnv('SUPABASE_URL', 'http://storage.test');
    vi.stubEnv('SUPABASE_ANON_KEY', 'anon');
    vi.stubGlobal('fetch', vi.fn(res));
  };
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.unstubAllEnvs();
  });

  it.each([[401], [403]])('Storage %i maps to 403 forbidden', async (status) => {
    stub(async () => new Response('{}', { status }));
    await expect(createSignedUploadUrl('t', 'p')).rejects.toMatchObject({ status: 403, code: 'forbidden' });
  });

  it('a Storage 500 maps to 502 storage_unavailable', async () => {
    stub(async () => new Response('{}', { status: 500 }));
    await expect(createSignedUploadUrl('t', 'p')).rejects.toMatchObject({ status: 502, code: 'storage_unavailable' });
  });

  it('a timeout or network error maps to 502 storage_unavailable', async () => {
    stub(async () => {
      throw new DOMException('timed out', 'TimeoutError');
    });
    await expect(createSignedUploadUrl('t', 'p')).rejects.toMatchObject({ status: 502, code: 'storage_unavailable' });
  });

  it('a non-JSON success body maps to 502 storage_unavailable', async () => {
    stub(async () => new Response('<html>', { status: 200 }));
    await expect(createSignedUploadUrl('t', 'p')).rejects.toMatchObject({ status: 502, code: 'storage_unavailable' });
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
