import type { Household, Role, UpdateModulesRequest } from '@abodenestly/contract';
import type { ApiFailure } from './api/apiClient.ts';

export type SettingsMode = 'owner' | 'member' | 'offline';
export type ModuleKey = keyof Required<UpdateModulesRequest>;

/** Offline wins over role: the whole screen is read-only without a connection (B5). */
export function settingsMode(role: Role | null, online: boolean): SettingsMode {
  if (!online) return 'offline';
  return role === 'owner' ? 'owner' : 'member';
}

export type SettingsState =
  | { kind: 'idle' }
  | { kind: 'saving' }
  | { kind: 'error'; code: ApiFailure['code'] }
  | { kind: 'leaving' };

export type SettingsAction =
  | { type: 'save' }
  | { type: 'saved' }
  | { type: 'failed'; code: ApiFailure['code'] }
  | { type: 'leave' }
  | { type: 'leave-failed'; code: ApiFailure['code'] }
  | { type: 'reset' };

export function settingsReducer(_state: SettingsState, action: SettingsAction): SettingsState {
  switch (action.type) {
    case 'save':
      return { kind: 'saving' };
    case 'leave':
      return { kind: 'leaving' };
    case 'failed':
    case 'leave-failed':
      return { kind: 'error', code: action.code };
    case 'saved':
    case 'reset':
      return { kind: 'idle' };
  }
}

/** The PATCH body for one toggle and the household as it will look once saved (shown while saving). */
export function withModule(
  household: Household,
  key: ModuleKey,
  on: boolean,
): { body: UpdateModulesRequest; display: Household } {
  const column = `module_${key}` as const;
  return { body: { [key]: on }, display: { ...household, [column]: on } };
}

export const CURRENCIES = ['AUD', 'CAD', 'CHF', 'CZK', 'DKK', 'EUR', 'GBP', 'HUF', 'JPY', 'NOK', 'PLN', 'SEK', 'USD'];

/** The short list, plus the household's own value when it is not in it so the select never lies. */
export function currencyOptions(current: string): string[] {
  return CURRENCIES.includes(current) ? CURRENCIES : [...CURRENCIES, current].sort();
}
