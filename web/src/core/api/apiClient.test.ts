import { afterEach, describe, expect, it, vi } from 'vitest';
import { createHttpApiClient, toShareFailure, toAcceptFailure, toRemoveFailure, toProductFailure, toSignFailure, toSyncFailure } from './apiClient.ts';

const body = (code: string) => ({ error: { code, message: 'x' } });

describe('CORE-02: toAcceptFailure', () => {
  it.each([
    [400, body('invalid_invite'), 'invalid_code'],
    [404, body('not_found'), 'invalid_code'],
    [410, body('gone'), 'invalid_code'],
    [400, body('validation'), 'unknown'],
    [500, body('internal'), 'unknown'],
    [404, null, 'invalid_code'],
    [410, null, 'invalid_code'],
    [400, null, 'unknown'],
    [502, null, 'unknown'],
  ])('%s %j -> %s', (status, json, code) => {
    expect(toAcceptFailure(status, json)).toEqual({ code });
  });
});

describe('CORE-04: toRemoveFailure', () => {
  it.each([
    [409, body('last_owner'), 'last_owner'],
    [403, body('forbidden'), 'forbidden'],
    [404, body('not_found'), 'not_found'],
    [400, body('validation'), 'unknown'],
    [500, body('internal'), 'unknown'],
    [409, null, 'last_owner'],
    [403, null, 'forbidden'],
    [404, null, 'not_found'],
    [502, null, 'unknown'],
  ])('%s %j -> %s', (status, json, code) => {
    expect(toRemoveFailure(status, json)).toEqual({ code });
  });
});

describe('CORE-11: toSignFailure', () => {
  it.each([
    [403, 'module_disabled', 'module_disabled'],
    [403, 'forbidden', 'forbidden'],
    [400, 'validation', 'validation'],
    [500, 'internal', 'unknown'],
  ])('%i %s → %s', (status, code, expected) => {
    expect(toSignFailure(status, { error: { code, message: '' } } as never)).toEqual({ code: expected });
  });

  it('falls back to the status when there is no error body', () => {
    expect(toSignFailure(403, null)).toEqual({ code: 'forbidden' });
    expect(toSignFailure(400, null)).toEqual({ code: 'validation' });
    expect(toSignFailure(502, null)).toEqual({ code: 'unknown' });
  });
});

describe('SHOP-08: toSyncFailure', () => {
  it.each([
    [503, body('retryable'), 'retryable'],
    [503, null, 'retryable'],
    [403, body('forbidden'), 'forbidden'],
    [400, body('validation'), 'validation'],
    [500, body('internal'), 'unknown'],
    [502, null, 'unknown'],
  ])('%s %j -> %s', (status, json, code) => {
    expect(toSyncFailure(status, json)).toEqual({ code });
  });
});

describe('SHOP-10: toProductFailure', () => {
  it.each([
    [404, body('not_found'), 'not_found'],
    [400, body('invalid_barcode'), 'not_found'],
    [502, body('upstream_unavailable'), 'retryable'],
    [502, null, 'retryable'],
    [404, null, 'not_found'],
    [401, body('unauthorized'), 'unknown'],
    [500, body('internal'), 'unknown'],
  ])('%i %j → %s', (status, json, code) => {
    expect(toProductFailure(status, json)).toEqual({ code });
  });
});

describe('SHOP-13: toShareFailure', () => {
  it.each([
    [403, body('module_disabled'), 'module_disabled'],
    [404, body('not_found'), 'not_found'],
    [404, null, 'not_found'],
    [500, body('internal'), 'unknown'],
    [502, null, 'unknown'],
  ])('%s %j -> %s', (status, json, code) => {
    expect(toShareFailure(status, json)).toEqual({ code });
  });
});

describe('SHOP-13: getSharedList', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('sends no Authorization header and never reads the session', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ list: { name: 'G' }, items: [] }), { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);
    const supabase = { auth: { getSession: vi.fn() } } as never;
    const result = await createHttpApiClient('https://api.test', supabase).getSharedList('tok');
    expect(result).toEqual({ ok: true, value: { list: { name: 'G' }, items: [] } });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock.mock.calls[0]![0]).toBe('https://api.test/shared/tok');
    expect(fetchMock.mock.calls[0]![1]).toBeUndefined();
    expect((supabase as { auth: { getSession: unknown } }).auth.getSession).not.toHaveBeenCalled();
  });

  it('maps 404 to not_found, other statuses to unknown and a thrown fetch to network', async () => {
    const client = createHttpApiClient('https://api.test', {} as never);
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{}', { status: 404 })));
    expect(await client.getSharedList('t')).toEqual({ ok: false, error: { code: 'not_found' } });
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{}', { status: 500 })));
    expect(await client.getSharedList('t')).toEqual({ ok: false, error: { code: 'unknown' } });
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('x')));
    expect(await client.getSharedList('t')).toEqual({ ok: false, error: { code: 'network' } });
  });
});
