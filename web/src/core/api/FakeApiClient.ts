import type { Activity, Household, Invite, Role, UpdateModulesRequest } from '@abodenestly/contract';
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
  fetchHouseholdCalls: Array<{ householdId: string; userId: string }> = [];
  updateModulesCalls: Array<{ householdId: string; body: UpdateModulesRequest }> = [];
  updateHouseholdCalls: Array<{ householdId: string; patch: { name?: string; currency?: string } }> = [];
  fetchHouseholdResult: ApiResult<{ household: Household; role: Role } | null> = { ok: true, value: null };
  fetchMyHouseholdsCalls: string[] = [];
  fetchMyHouseholdsResult: ApiResult<Array<{ household: Household; role: Role }>> = { ok: true, value: [] };
  updateModulesResult: ApiResult<Household> = { ok: false, error: { code: 'unknown' } };
  updateHouseholdResult: ApiResult<Household> = { ok: false, error: { code: 'unknown' } };
  fetchActivityCalls: Array<{ householdId: string; sinceIso: string; limit: number }> = [];
  fetchActivityResult: ApiResult<Activity[]> = { ok: true, value: [] };
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

  fetchHousehold(householdId: string, userId: string) {
    this.fetchHouseholdCalls.push({ householdId, userId });
    return this.respond(() => this.fetchHouseholdResult);
  }

  fetchMyHouseholds(userId: string) {
    this.fetchMyHouseholdsCalls.push(userId);
    return this.respond(() => this.fetchMyHouseholdsResult);
  }

  updateModules(householdId: string, body: UpdateModulesRequest) {
    this.updateModulesCalls.push({ householdId, body });
    return this.respond(() => this.updateModulesResult);
  }

  updateHousehold(householdId: string, patch: { name?: string; currency?: string }) {
    this.updateHouseholdCalls.push({ householdId, patch });
    return this.respond(() => this.updateHouseholdResult);
  }

  fetchMembers(householdId: string) {
    this.fetchMembersCalls.push(householdId);
    return this.respond(() => this.fetchMembersResult);
  }

  fetchActivity(householdId: string, sinceIso: string, limit: number) {
    this.fetchActivityCalls.push({ householdId, sinceIso, limit });
    return this.respond(() => this.fetchActivityResult);
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
