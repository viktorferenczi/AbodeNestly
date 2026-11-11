import { describe, expect, it } from 'vitest';
import { toAcceptFailure } from './apiClient.ts';

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
