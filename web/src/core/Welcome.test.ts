import type { Household } from '@abodenestly/contract';
import { describe, expect, it } from 'vitest';
import { householdHomePath, welcomeReducer, type WelcomeState } from './Welcome.ts';

const household = { id: 'h-new' } as Household;

describe('CORE-02: Welcome reducer', () => {
  it('CORE-02: AC2 after a fake success the landing route is the new household home', () => {
    const creating = welcomeReducer({ kind: 'idle' }, { type: 'create' });
    expect(creating).toEqual({ kind: 'creating' });
    expect(householdHomePath(household)).toBe('/home');
  });

  it.each<[string, WelcomeState, Parameters<typeof welcomeReducer>[1], WelcomeState]>([
    ['join', { kind: 'idle' }, { type: 'join' }, { kind: 'joining' }],
    ['create fails', { kind: 'creating' }, { type: 'create-failed', code: 'network' }, { kind: 'create-error', code: 'network' }],
    ['invalid code', { kind: 'joining' }, { type: 'join-failed', code: 'invalid_code' }, { kind: 'error-invalid-code' }],
    ['other join error', { kind: 'joining' }, { type: 'join-failed', code: 'network' }, { kind: 'join-error', code: 'network' }],
    ['reset', { kind: 'error-invalid-code' }, { type: 'reset' }, { kind: 'idle' }],
  ])('%s', (_name, from, action, to) => {
    expect(welcomeReducer(from, action)).toEqual(to);
  });
});
