import type {
  ApiError,
  CreateHouseholdRequest,
  CreateInviteRequest,
  Household,
  Invite,
  Role,
  UpdateModulesRequest,
} from '@abodenestly/contract';
import type { SupabaseClient } from '@supabase/supabase-js';

export type ApiFailure = {
  code: 'validation' | 'invalid_code' | 'forbidden' | 'last_owner' | 'network' | 'unknown';
};
export type ApiResult<T> = { ok: true; value: T } | { ok: false; error: ApiFailure };

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
  /** CORE-05 endpoint, owner only. 403 → `forbidden`. */
  updateModules(householdId: string, body: UpdateModulesRequest): Promise<ApiResult<Household>>;
  /** Plain CRUD straight to Supabase under RLS (SPEC §7); a non-owner gets SQLSTATE 42501 → `forbidden`. */
  updateHousehold(householdId: string, patch: { name?: string; currency?: string }): Promise<ApiResult<Household>>;
  /** Sets my own `member.left_at`. The last owner is refused by a DB trigger → `last_owner`. */
  leaveHousehold(householdId: string, userId: string): Promise<ApiResult<null>>;
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

  async function send(path: string, body: unknown, method: 'POST' | 'PATCH' = 'POST'): Promise<Response> {
    const request = async (token: string | null) =>
      fetch(`${baseUrl}${path}`, {
        method,
        headers: {
          'content-type': 'application/json',
          ...(token ? { authorization: `Bearer ${token}` } : {}),
        },
        body: JSON.stringify(body),
      });
    const first = await request(await accessToken());
    if (first.status !== 401) return first;
    // Refresh once through the SDK, then retry (react-web skill, Auth).
    const { data } = await supabase.auth.refreshSession();
    return request(data.session?.access_token ?? null);
  }

  async function call<T>(
    path: string,
    body: unknown,
    okStatus: (status: number) => boolean,
    mapError: (status: number, body: ApiError | null) => ApiFailure,
    method: 'POST' | 'PATCH' = 'POST',
  ): Promise<ApiResult<T>> {
    let response: Response;
    try {
      response = await send(path, body, method);
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
    updateModules(householdId, body) {
      return call<Household>(
        `/households/${encodeURIComponent(householdId)}/modules`,
        body,
        (s) => s === 200,
        (status) => ({ code: status === 403 ? 'forbidden' : status === 400 ? 'validation' : 'unknown' }),
        'PATCH',
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
    async leaveHousehold(householdId, userId) {
      try {
        const { data, error } = await supabase
          .from('member')
          .update({ left_at: new Date().toISOString() })
          .eq('household_id', householdId)
          .eq('user_id', userId)
          .select('id');
        if (error) {
          const lastOwner = error.code === 'P0001' || /last_owner/.test(error.message ?? '');
          return { ok: false, error: { code: lastOwner ? 'last_owner' : 'unknown' } };
        }
        if (!data || data.length === 0) return { ok: false, error: { code: 'unknown' } };
        return { ok: true, value: null };
      } catch {
        return { ok: false, error: { code: 'network' } };
      }
    },
    acceptInvite(code) {
      return call<{ household_id: string }>(
        `/invites/${encodeURIComponent(code)}/accept`,
        {},
        (s) => s >= 200 && s < 300,
        toAcceptFailure,
      );
    },
  };
}
