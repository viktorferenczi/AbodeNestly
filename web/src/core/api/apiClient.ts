import type {
  ApiError,
  CreateHouseholdRequest,
  CreateInviteRequest,
  Household,
  Invite,
  Member,
  Profile,
  Role,
  SignAttachmentRequest,
  SignAttachmentResponse,
  SyncPullResponse,
  SyncPushRequest,
  SyncPushResponse,
  UpdateModulesRequest,
} from '@abodenestly/contract';
import type { SupabaseClient } from '@supabase/supabase-js';

export type ApiFailure = {
  code: 'validation' | 'invalid_code' | 'network' | 'unknown' | 'last_owner' | 'forbidden' | 'not_found' | 'module_disabled' | 'retryable';
};
export type ApiResult<T> = { ok: true; value: T } | { ok: false; error: ApiFailure };

/** An active member with the profile fields the Members screen shows (`null` when RLS hides the profile). */
export type MemberRow = { member: Member; profile: Pick<Profile, 'display_name' | 'avatar_path'> | null };

/** The API calls the app makes; tests use a hand-written fake. */
export interface ApiClient {
  createHousehold(name: string): Promise<ApiResult<Household>>;
  /** CORE-03 endpoint; wired here so the Welcome screen is complete once it exists. */
  acceptInvite(code: string): Promise<ApiResult<{ household_id: string }>>;
  /** Mints an invite with the DB defaults (member / 7 days / 10 uses). */
  createInvite(householdId: string): Promise<ApiResult<Invite>>;
  /** Newest usable invite (not retired, not expired, uses left) or `null`. Read under RLS, no API route. */
  fetchLiveInvite(householdId: string): Promise<ApiResult<Invite | null>>;
  /** Sets `deleted_at`; the only change a client may make to an invite (SPEC §14). */
  retireInvite(id: string): Promise<ApiResult<null>>;
  /** My active membership of the household with the household row, or `null` (left, deleted or not a member). */
  fetchHousehold(householdId: string, userId: string): Promise<ApiResult<{ household: Household; role: Role } | null>>;
  /** Every household I am an active member of (not deleted), for the switcher. Read under RLS. */
  fetchMyHouseholds(userId: string): Promise<ApiResult<Array<{ household: Household; role: Role }>>>;
  /** CORE-05 endpoint, owner only. 403 → `forbidden`. */
  updateModules(householdId: string, body: UpdateModulesRequest): Promise<ApiResult<Household>>;
  /** Plain CRUD straight to Supabase under RLS (SPEC §7); a non-owner gets SQLSTATE 42501 → `forbidden`. */
  updateHousehold(householdId: string, patch: { name?: string; currency?: string }): Promise<ApiResult<Household>>;
  /** Active members of the household with their profiles. Read under RLS, no API route. */
  fetchMembers(householdId: string): Promise<ApiResult<MemberRow[]>>;
  /** DELETE /households/{id}/members/{user_id}: remove someone, or leave when `userId` is the caller. */
  removeMember(householdId: string, userId: string): Promise<ApiResult<null>>;
  /** SHOP-08: POST /sync. 503 `retryable` (deadlock) → `retryable`; the rows stay queued. */
  syncPush(req: SyncPushRequest): Promise<ApiResult<SyncPushResponse>>;
  /** Forces a token refresh; the sync loop calls it once before it gives up on an `rls_denied` rejection (react-web skill). */
  refreshSession(): Promise<void>;
  /** SHOP-08: GET /sync, one page of changes after `since`. */
  syncPull(householdId: string, since: number, limit: number): Promise<ApiResult<SyncPullResponse>>;
  /** Promotes a member to owner. Direct write under RLS (SPEC §7: network-only core settings). */
  makeOwner(householdId: string, userId: string): Promise<ApiResult<null>>;
  /** CORE-11: creates the attachment row and returns the Storage upload URL. 403 `module_disabled` when the entity's module is off. */
  signAttachment(req: SignAttachmentRequest): Promise<ApiResult<SignAttachmentResponse>>;
}

/** PUTs the bytes to the signed `upload_url` (plain fetch, no bearer: the URL carries the token). */
export type UploadFn = (url: string, blob: Blob, mime: string) => Promise<ApiResult<null>>;

export const uploadToSignedUrl: UploadFn = async (url, blob, mime) => {
  try {
    const response = await fetch(url, { method: 'PUT', headers: { 'content-type': mime, 'x-upsert': 'false' }, body: blob });
    return response.ok ? { ok: true, value: null } : { ok: false, error: { code: 'unknown' } };
  } catch {
    return { ok: false, error: { code: 'network' } };
  }
};

/** Maps a sign error response to a failure; exported for the table test. */
export function toSignFailure(status: number, body: ApiError | null): ApiFailure {
  const code = body?.error?.code;
  if (code === 'module_disabled') return { code: 'module_disabled' };
  if (code === 'forbidden') return { code: 'forbidden' };
  if (code === 'validation') return { code: 'validation' };
  if (code) return { code: 'unknown' };
  if (status === 403) return { code: 'forbidden' };
  if (status === 400) return { code: 'validation' };
  return { code: 'unknown' };
}

// CORE-03-api has not fixed its error code yet; the SQL raises `invalid_invite`. `not_found` and
// `gone` are accepted so the pre-CORE-03 missing route (dev server: 404 `not_found`) reads as an
// invalid code. Any other code, `validation` included, stays `unknown`.
const INVALID_CODE_CODES = new Set(['invalid_invite', 'not_found', 'gone']);

/** Maps an accept-invite error response to a failure; exported for the table test. */
export function toAcceptFailure(status: number, body: ApiError | null): ApiFailure {
  const code = body?.error?.code;
  if (code) return { code: INVALID_CODE_CODES.has(code) ? 'invalid_code' : 'unknown' };
  // No parseable error body (e.g. a bare proxy 404): fall back to the status.
  return { code: status === 404 || status === 410 ? 'invalid_code' : 'unknown' };
}

const REMOVE_CODES = new Set(['last_owner', 'forbidden', 'not_found']);

/** Maps a remove/leave error response to a failure; exported for the table test. */
export function toRemoveFailure(status: number, body: ApiError | null): ApiFailure {
  const code = body?.error?.code;
  if (code && REMOVE_CODES.has(code)) return { code: code as 'last_owner' | 'forbidden' | 'not_found' };
  if (code) return { code: 'unknown' };
  // No parseable error body: fall back to the status.
  if (status === 403) return { code: 'forbidden' };
  if (status === 404) return { code: 'not_found' };
  if (status === 409) return { code: 'last_owner' };
  return { code: 'unknown' };
}

/** Maps a /sync error response to a failure; exported for the table test. */
export function toSyncFailure(status: number, body: ApiError | null): ApiFailure {
  const code = body?.error?.code;
  if (status === 503 || code === 'retryable') return { code: 'retryable' };
  if (code === 'forbidden' || status === 403) return { code: 'forbidden' };
  if (code === 'validation' || status === 400) return { code: 'validation' };
  return { code: 'unknown' };
}

async function readJson(response: Response): Promise<unknown> {
  try {
    return await response.json();
  } catch {
    return null;
  }
}

export function createHttpApiClient(baseUrl: string, supabase: SupabaseClient): ApiClient {
  async function accessToken(): Promise<string | null> {
    const { data } = await supabase.auth.getSession();
    return data.session?.access_token ?? null;
  }

  async function send(method: 'GET' | 'POST' | 'PATCH' | 'DELETE', path: string, body?: unknown): Promise<Response> {
    const request = async (token: string | null) =>
      fetch(`${baseUrl}${path}`, {
        method,
        headers: {
          ...(body === undefined ? {} : { 'content-type': 'application/json' }),
          ...(token ? { authorization: `Bearer ${token}` } : {}),
        },
        ...(body === undefined ? {} : { body: JSON.stringify(body) }),
      });
    const first = await request(await accessToken());
    if (first.status !== 401) return first;
    // Refresh once through the SDK, then retry (react-web skill, Auth).
    const { data } = await supabase.auth.refreshSession();
    return request(data.session?.access_token ?? null);
  }

  async function call<T>(
    method: 'GET' | 'POST' | 'PATCH' | 'DELETE',
    path: string,
    body: unknown,
    okStatus: (status: number) => boolean,
    mapError: (status: number, body: ApiError | null) => ApiFailure,
  ): Promise<ApiResult<T>> {
    let response: Response;
    try {
      response = await send(method, path, body);
    } catch {
      return { ok: false, error: { code: 'network' } };
    }
    const json = await readJson(response);
    if (okStatus(response.status)) return { ok: true, value: json as T };
    return { ok: false, error: mapError(response.status, json as ApiError | null) };
  }

  return {
    createHousehold(name) {
      const body: CreateHouseholdRequest = { name };
      return call<Household>(
        'POST',
        '/households',
        body,
        (s) => s === 201,
        (status, json) => {
          if (json?.error?.code === 'validation' || status === 400) return { code: 'validation' };
          return { code: 'unknown' };
        },
      );
    },
    createInvite(householdId) {
      const body: CreateInviteRequest = {};
      return call<Invite>(
        'POST',
        `/households/${encodeURIComponent(householdId)}/invites`,
        body,
        (s) => s === 201,
        () => ({ code: 'unknown' }),
      );
    },
    async fetchLiveInvite(householdId) {
      try {
        const { data, error } = await supabase
          .from('invite')
          .select('*')
          .eq('household_id', householdId)
          .is('deleted_at', null)
          .gt('expires_at', new Date().toISOString())
          .order('created_at', { ascending: false });
        if (error) return { ok: false, error: { code: 'unknown' } };
        // PostgREST cannot compare two columns, so uses < max_uses is filtered here.
        const live = ((data ?? []) as Invite[]).find((i) => i.uses < i.max_uses) ?? null;
        return { ok: true, value: live };
      } catch {
        return { ok: false, error: { code: 'network' } };
      }
    },
    async retireInvite(id) {
      try {
        const { data, error } = await supabase
          .from('invite')
          .update({ deleted_at: new Date().toISOString() })
          .eq('id', id)
          .select('id');
        // RLS hides rows silently, so an empty result means nothing was retired.
        if (error || !data || data.length === 0) return { ok: false, error: { code: 'unknown' } };
        return { ok: true, value: null };
      } catch {
        return { ok: false, error: { code: 'network' } };
      }
    },
    async fetchMembers(householdId) {
      try {
        const { data: members, error } = await supabase
          .from('member')
          .select('*')
          .eq('household_id', householdId)
          .is('left_at', null);
        if (error) return { ok: false, error: { code: 'unknown' } };
        const list = (members ?? []) as Member[];
        if (list.length === 0) return { ok: true, value: [] };
        // No FK embed from member to profile, so the profiles are a second read.
        const { data: profiles, error: profileError } = await supabase
          .from('profile')
          .select('id, display_name, avatar_path')
          .in('id', list.map((m) => m.user_id));
        if (profileError) return { ok: false, error: { code: 'unknown' } };
        const byId = new Map(
          ((profiles ?? []) as Array<Pick<Profile, 'id' | 'display_name' | 'avatar_path'>>).map((p) => [p.id, p]),
        );
        return {
          ok: true,
          value: list.map((member) => {
            const p = byId.get(member.user_id);
            return { member, profile: p ? { display_name: p.display_name, avatar_path: p.avatar_path } : null };
          }),
        };
      } catch {
        return { ok: false, error: { code: 'network' } };
      }
    },
    async removeMember(householdId, userId) {
      const result = await call<null>(
        'DELETE',
        `/households/${encodeURIComponent(householdId)}/members/${encodeURIComponent(userId)}`,
        undefined,
        (s) => s === 204,
        toRemoveFailure,
      );
      return result.ok ? { ok: true, value: null } : result;
    },
    async makeOwner(householdId, userId) {
      try {
        const { data, error } = await supabase
          .from('member')
          .update({ role: 'owner' })
          .eq('household_id', householdId)
          .eq('user_id', userId)
          .select('user_id');
        // RLS hides rows silently, so an empty result means nothing was promoted.
        if (error || !data || data.length === 0) return { ok: false, error: { code: 'unknown' } };
        return { ok: true, value: null };
      } catch {
        return { ok: false, error: { code: 'network' } };
      }
    },
    async fetchHousehold(householdId, userId) {
      try {
        const { data, error } = await supabase
          .from('member')
          .select('role, household:household!inner(*)')
          .eq('household_id', householdId)
          .eq('user_id', userId)
          .is('left_at', null)
          .is('household.deleted_at', null)
          .maybeSingle();
        if (error) return { ok: false, error: { code: 'unknown' } };
        if (!data) return { ok: true, value: null };
        const row = data as unknown as { role: Role; household: Household };
        return { ok: true, value: { household: row.household, role: row.role } };
      } catch {
        return { ok: false, error: { code: 'network' } };
      }
    },
    async fetchMyHouseholds(userId) {
      try {
        const { data, error } = await supabase
          .from('member')
          .select('role, household:household!inner(*)')
          .eq('user_id', userId)
          .is('left_at', null)
          .is('household.deleted_at', null);
        if (error) return { ok: false, error: { code: 'unknown' } };
        const rows = (data ?? []) as unknown as Array<{ role: Role; household: Household }>;
        return { ok: true, value: rows.map((r) => ({ household: r.household, role: r.role })) };
      } catch {
        return { ok: false, error: { code: 'network' } };
      }
    },
    updateModules(householdId, body) {
      return call<Household>(
        'PATCH',
        `/households/${encodeURIComponent(householdId)}/modules`,
        body,
        (s) => s === 200,
        (status) => ({ code: status === 403 ? 'forbidden' : status === 400 ? 'validation' : 'unknown' }),
      );
    },
    async updateHousehold(householdId, patch) {
      try {
        const { data, error } = await supabase.from('household').update(patch).eq('id', householdId).select('*').single();
        if (error) return { ok: false, error: { code: error.code === '42501' ? 'forbidden' : 'unknown' } };
        // RLS hides rows silently, so no row back means nothing changed.
        if (!data) return { ok: false, error: { code: 'unknown' } };
        return { ok: true, value: data as Household };
      } catch {
        return { ok: false, error: { code: 'network' } };
      }
    },
    async refreshSession() {
      await supabase.auth.refreshSession().catch(() => undefined);
    },
    syncPush(req) {
      return call<SyncPushResponse>('POST', '/sync', req, (s) => s === 200, toSyncFailure);
    },
    syncPull(householdId, since, limit) {
      const query = `household_id=${encodeURIComponent(householdId)}&since=${since}&limit=${limit}`;
      return call<SyncPullResponse>('GET', `/sync?${query}`, undefined, (s) => s === 200, toSyncFailure);
    },
    signAttachment(req) {
      return call<SignAttachmentResponse>('POST', '/attachments/sign', req, (s) => s === 200 || s === 201, toSignFailure);
    },
    acceptInvite(code) {
      return call<{ household_id: string }>(
        'POST',
        `/invites/${encodeURIComponent(code)}/accept`,
        {},
        (s) => s >= 200 && s < 300,
        toAcceptFailure,
      );
    },
  };
}
