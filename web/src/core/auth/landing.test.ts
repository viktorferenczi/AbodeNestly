import { describe, expect, it } from 'vitest';
import { landingPath, type Membership } from './landing.ts';

function membership(id: string, joined: string, modules: [boolean, boolean, boolean] = [true, true, true]): Membership {
  const [shopping, pantry, maintenance] = modules;
  return {
    household_id: id,
    joined_at: joined,
    household: { id, module_shopping: shopping, module_pantry: pantry, module_maintenance: maintenance },
  };
}

const A = membership('a', '2026-01-01T00:00:00Z');
const B = membership('b', '2026-02-01T00:00:00Z', [false, true, true]);

describe('CORE-01: landingPath', () => {
  const cases: Array<[string, Parameters<typeof landingPath>[0], string]> = [
    ['no household → /welcome', { memberships: [], lastHouseholdId: null, next: null }, '/welcome'],
    ['one household, all modules on → /shopping', { memberships: [A], lastHouseholdId: null, next: null }, '/shopping'],
    [
      'only pantry on → /pantry',
      { memberships: [membership('p', '2026-01-01T00:00:00Z', [false, true, false])], lastHouseholdId: null, next: null },
      '/pantry',
    ],
    [
      'only maintenance on → /maintenance',
      { memberships: [membership('m', '2026-01-01T00:00:00Z', [false, false, true])], lastHouseholdId: null, next: null },
      '/maintenance',
    ],
    [
      'all modules off → /home',
      { memberships: [membership('h', '2026-01-01T00:00:00Z', [false, false, false])], lastHouseholdId: null, next: null },
      '/home',
    ],
    ['last used household is preferred', { memberships: [A, B], lastHouseholdId: 'b', next: null }, '/pantry'],
    ['stale last-used id falls back to earliest joined_at', { memberships: [B, A], lastHouseholdId: 'gone', next: null }, '/shopping'],
    ['a safe next is kept', { memberships: [A], lastHouseholdId: null, next: '/pantry?x=1' }, '/pantry?x=1'],
    ['protocol-relative next is ignored', { memberships: [A], lastHouseholdId: null, next: '//evil.example' }, '/shopping'],
    ['absolute URL next is ignored', { memberships: [A], lastHouseholdId: null, next: 'https://evil.example' }, '/shopping'],
    ['/signin next is ignored', { memberships: [A], lastHouseholdId: null, next: '/signin?next=/x' }, '/shopping'],
    ['/auth/callback next is ignored', { memberships: [A], lastHouseholdId: null, next: '/auth/callback' }, '/shopping'],
    ['no household plus next → /welcome', { memberships: [], lastHouseholdId: null, next: '/pantry' }, '/welcome'],
    [
      'CORE-03: no household plus an invite link keeps the link',
      { memberships: [], lastHouseholdId: null, next: '/join/0123456789ab' },
      '/join/0123456789ab',
    ],
    [
      'CORE-03: no household plus a look-alike path → /welcome',
      { memberships: [], lastHouseholdId: null, next: '/joinery' },
      '/welcome',
    ],
    [
      'CORE-03: no household plus //join is not an invite link',
      { memberships: [], lastHouseholdId: null, next: '//join/x' },
      '/welcome',
    ],
  ];

  it.each(cases)('CORE-01: %s', (_name, input, expected) => {
    expect(landingPath(input)).toBe(expected);
  });
});
