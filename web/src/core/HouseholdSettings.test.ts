import type { Household } from '@abodenestly/contract';
import { describe, expect, it } from 'vitest';
import { visibleTabs } from '../app/tabs.ts';
import { modulesOf } from './household/HouseholdProvider.tsx';
import { CURRENCIES, currencyOptions, settingsMode, settingsReducer, withModule } from './HouseholdSettings.ts';

const household = {
  id: 'h1',
  name: 'Test Home',
  currency: 'EUR',
  module_shopping: true,
  module_pantry: true,
  module_maintenance: true,
} as Household;

describe('CORE-05: settingsMode', () => {
  it.each([
    ['owner', true, 'owner'],
    ['member', true, 'member'],
    ['owner', false, 'offline'],
    ['member', false, 'offline'],
    [null, true, 'member'],
    [null, false, 'offline'],
  ] as const)('role %s online %s -> %s', (role, online, mode) => {
    expect(settingsMode(role, online)).toBe(mode);
  });
});

describe('CORE-05: withModule', () => {
  it.each([
    ['shopping', false, { shopping: false }, 'module_shopping'],
    ['pantry', false, { pantry: false }, 'module_pantry'],
    ['maintenance', false, { maintenance: false }, 'module_maintenance'],
    ['pantry', true, { pantry: true }, 'module_pantry'],
  ] as const)('%s -> %s', (key, on, body, column) => {
    const result = withModule(household, key, on);
    expect(result.body).toEqual(body);
    expect(result.display[column]).toBe(on);
    expect(result.display.name).toBe('Test Home');
  });

  it('does not mutate the input', () => {
    withModule(household, 'pantry', false);
    expect(household.module_pantry).toBe(true);
  });
});

describe('CORE-05: currencyOptions', () => {
  it('returns the list unchanged for a listed currency', () => {
    expect(currencyOptions('EUR')).toEqual(CURRENCIES);
  });
  it('adds a missing current value', () => {
    expect(currencyOptions('BRL')).toContain('BRL');
    expect(currencyOptions('BRL')).toHaveLength(CURRENCIES.length + 1);
  });
});

describe('CORE-05: settingsReducer', () => {
  it('walks idle → saving → error → idle and leaving', () => {
    const saving = settingsReducer({ kind: 'idle' }, { type: 'save' });
    expect(saving).toEqual({ kind: 'saving' });
    expect(settingsReducer(saving, { type: 'failed', code: 'forbidden' })).toEqual({ kind: 'error', code: 'forbidden' });
    expect(settingsReducer(saving, { type: 'saved' })).toEqual({ kind: 'idle' });
    const leaving = settingsReducer({ kind: 'idle' }, { type: 'leave' });
    expect(leaving).toEqual({ kind: 'leaving' });
    expect(settingsReducer(leaving, { type: 'leave-failed', code: 'last_owner' })).toEqual({ kind: 'error', code: 'last_owner' });
  });
});

describe('CORE-05: visibleTabs', () => {
  it('AC3: after a successful pantry-off response the pantry tab is gone', () => {
    const response = { ...household, module_pantry: false };
    const keys = visibleTabs(modulesOf(response)).map((t) => t.key);
    expect(keys).not.toContain('pantry');
    expect(keys).toEqual(['shopping', 'maintenance', 'home', 'profile']);
  });
  it('all modules off leaves Home and Profile', () => {
    expect(visibleTabs({ shopping: false, pantry: false, maintenance: false }).map((t) => t.key)).toEqual(['home', 'profile']);
  });
  it('all modules on shows the DESIGN A6 order', () => {
    expect(visibleTabs(modulesOf(household)).map((t) => t.key)).toEqual(['shopping', 'pantry', 'maintenance', 'home', 'profile']);
  });
});
