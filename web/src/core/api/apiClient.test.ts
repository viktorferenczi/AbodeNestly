import { describe, expect, it } from 'vitest';
import { toAcceptFailure, toRemoveFailure, toSignFailure, toSyncFailure } from './apiClient.ts';

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
