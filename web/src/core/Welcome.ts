import type { Household } from '@abodenestly/contract';
import type { ApiFailure } from './api/apiClient.ts';

export type WelcomeState =
  | { kind: 'idle' }
  | { kind: 'creating' }
  | { kind: 'joining' }
  | { kind: 'create-error'; code: ApiFailure['code'] }
  | { kind: 'error-invalid-code' }
  | { kind: 'join-error'; code: ApiFailure['code'] };

export type WelcomeAction =
  | { type: 'create' }
  | { type: 'join' }
  | { type: 'create-failed'; code: ApiFailure['code'] }
  | { type: 'join-failed'; code: ApiFailure['code'] }
  | { type: 'reset' };

export function welcomeReducer(_state: WelcomeState, action: WelcomeAction): WelcomeState {
  switch (action.type) {
    case 'create':
      return { kind: 'creating' };
    case 'join':
      return { kind: 'joining' };
    case 'create-failed':
      return { kind: 'create-error', code: action.code };
    case 'join-failed':
      return action.code === 'invalid_code' ? { kind: 'error-invalid-code' } : { kind: 'join-error', code: action.code };
    case 'reset':
      return { kind: 'idle' };
  }
}

/** DESIGN B4. Every household opens on Home; the argument is for the later per-household route. */
// eslint-disable-next-line @typescript-eslint/no-unused-vars -- signature fixed for the per-household route
export function householdHomePath(_household: Pick<Household, 'id'>): string {
  return '/home';
}
