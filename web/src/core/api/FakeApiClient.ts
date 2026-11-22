import type {
  Household,
  Invite,
  Role,
  SignAttachmentRequest,
  SignAttachmentResponse,
  SyncPullResponse,
  SyncPushRequest,
  SyncPushResponse,
  UpdateModulesRequest,
} from '@abodenestly/contract';
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
  syncPushCalls: SyncPushRequest[] = [];
  syncPullCalls: Array<{ householdId: string; since: number; limit: number }> = [];
  /** Results are consumed in order; the last one repeats, so a test only queues what differs. */
  syncPushResults: Array<ApiResult<SyncPushResponse> | ((req: SyncPushRequest) => ApiResult<SyncPushResponse>)> = [];
  syncPullResults: Array<ApiResult<SyncPullResponse>> = [];
  fetchMembersCalls: string[] = [];
  removeCalls: Array<{ householdId: string; userId: string }> = [];
  makeOwnerCalls: Array<{ householdId: string; userId: string }> = [];
  fetchMembersResult: ApiResult<MemberRow[]> = { ok: true, value: [] };
  removeResult: ApiResult<null> = { ok: true, value: null };
  makeOwnerResult: ApiResult<null> = { ok: true, value: null };
  signAttachmentCalls: SignAttachmentRequest[] = [];
  signAttachmentResult: ApiResult<SignAttachmentResponse> = {
    ok: true,
    value: { attachment_id: 'a1', upload_url: 'https://upload.test/a1', storage_path: 'h1/pantry_item/e1/a1.jpg' },
  };
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

  refreshCalls = 0;

  async refreshSession() {
    this.refreshCalls += 1;
  }

  syncPush(req: SyncPushRequest) {
    this.syncPushCalls.push(req);
    return this.respond(() => {
      const next = this.syncPushResults.length > 1 ? this.syncPushResults.shift()! : this.syncPushResults[0];
      // Default: the server applies everything it is sent.
      if (!next) return { ok: true as const, value: { applied: req.mutations.map((m) => m.id), rejected: [] } };
      return typeof next === 'function' ? next(req) : next;
    });
  }

  syncPull(householdId: string, since: number, limit: number) {
    this.syncPullCalls.push({ householdId, since, limit });
    return this.respond(() => {
      const next = this.syncPullResults.length > 1 ? this.syncPullResults.shift()! : this.syncPullResults[0];
      return next ?? { ok: true as const, value: { cursor: since, has_more: false, changes: {} } };
    });
  }

  removeMember(householdId: string, userId: string) {
    this.removeCalls.push({ householdId, userId });
    return this.respond(() => this.removeResult);
  }

  makeOwner(householdId: string, userId: string) {
    this.makeOwnerCalls.push({ householdId, userId });
    return this.respond(() => this.makeOwnerResult);
  }

  signAttachment(req: SignAttachmentRequest) {
    this.signAttachmentCalls.push(req);
    return this.respond(() => this.signAttachmentResult);
  }

  resolvePending() {
    this.waiting.splice(0).forEach((resolve) => resolve());
  }

  private respond<T>(result: () => T): Promise<T> {
    if (!this.pending) return Promise.resolve(result());
    return new Promise((resolve) => this.waiting.push(() => resolve(result())));
  }
}
