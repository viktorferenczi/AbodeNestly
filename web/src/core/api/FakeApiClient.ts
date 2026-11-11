import type { Household } from '@abodenestly/contract';
import type { ApiClient, ApiResult } from './apiClient.ts';

// Hand-written fake (TESTING §2 forbids mocking libraries). Records calls, returns what the test sets.
export class FakeApiClient implements ApiClient {
  createCalls: string[] = [];
  acceptCalls: string[] = [];
  createResult: ApiResult<Household> = { ok: false, error: { code: 'unknown' } };
  acceptResult: ApiResult<{ household_id: string }> = { ok: false, error: { code: 'invalid_code' } };
  /** When true, calls return a promise that only settles via `resolvePending`. */
  pending = false;
  private waiting: Array<() => void> = [];

  createHousehold(name: string) {
    this.createCalls.push(name);
    return this.respond(() => this.createResult);
  }

  acceptInvite(code: string) {
    this.acceptCalls.push(code);
    return this.respond(() => this.acceptResult);
  }

  resolvePending() {
    this.waiting.splice(0).forEach((resolve) => resolve());
  }

  private respond<T>(result: () => T): Promise<T> {
    if (!this.pending) return Promise.resolve(result());
    return new Promise((resolve) => this.waiting.push(() => resolve(result())));
  }
}
