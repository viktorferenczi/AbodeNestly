import type { Household, Invite } from '@abodenestly/contract';
import type { ApiClient, ApiResult, MemberRow } from './apiClient.ts';

// Hand-written fake (TESTING §2 forbids mocking libraries). Records calls, returns what the test sets.
export class FakeApiClient implements ApiClient {
  createCalls: string[] = [];
  acceptCalls: string[] = [];
  createResult: ApiResult<Household> = { ok: false, error: { code: 'unknown' } };
  acceptResult: ApiResult<{ household_id: string }> = { ok: false, error: { code: 'invalid_code' } };
  createInviteCalls: string[] = [];
  fetchInviteCalls: string[] = [];
  retireCalls: string[] = [];
  createInviteResult: ApiResult<Invite> = { ok: false, error: { code: 'unknown' } };
  fetchInviteResult: ApiResult<Invite | null> = { ok: true, value: null };
  retireResult: ApiResult<null> = { ok: true, value: null };
  fetchMembersCalls: string[] = [];
  removeCalls: Array<{ householdId: string; userId: string }> = [];
  makeOwnerCalls: Array<{ householdId: string; userId: string }> = [];
  fetchMembersResult: ApiResult<MemberRow[]> = { ok: true, value: [] };
  removeResult: ApiResult<null> = { ok: true, value: null };
  makeOwnerResult: ApiResult<null> = { ok: true, value: null };
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

  createInvite(householdId: string) {
    this.createInviteCalls.push(householdId);
    return this.respond(() => this.createInviteResult);
  }

  fetchLiveInvite(householdId: string) {
    this.fetchInviteCalls.push(householdId);
    return this.respond(() => this.fetchInviteResult);
  }

  retireInvite(id: string) {
    this.retireCalls.push(id);
    return this.respond(() => this.retireResult);
  }

  fetchMembers(householdId: string) {
    this.fetchMembersCalls.push(householdId);
    return this.respond(() => this.fetchMembersResult);
  }

  removeMember(householdId: string, userId: string) {
    this.removeCalls.push({ householdId, userId });
    return this.respond(() => this.removeResult);
  }

  makeOwner(householdId: string, userId: string) {
    this.makeOwnerCalls.push({ householdId, userId });
    return this.respond(() => this.makeOwnerResult);
  }

  resolvePending() {
    this.waiting.splice(0).forEach((resolve) => resolve());
  }

  private respond<T>(result: () => T): Promise<T> {
    if (!this.pending) return Promise.resolve(result());
    return new Promise((resolve) => this.waiting.push(() => resolve(result())));
  }
}
