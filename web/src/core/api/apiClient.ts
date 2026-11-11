import type { ApiError, CreateHouseholdRequest, Household } from '@abodenestly/contract';
import type { SupabaseClient } from '@supabase/supabase-js';

export type ApiFailure = { code: 'validation' | 'invalid_code' | 'network' | 'unknown' };
export type ApiResult<T> = { ok: true; value: T } | { ok: false; error: ApiFailure };

/** The API calls the app makes; tests use a hand-written fake. */
export interface ApiClient {
  createHousehold(name: string): Promise<ApiResult<Household>>;
  /** CORE-03 endpoint; wired here so the Welcome screen is complete once it exists. */
  acceptInvite(code: string): Promise<ApiResult<{ household_id: string }>>;
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

  async function send(path: string, body: unknown): Promise<Response> {
    const request = async (token: string | null) =>
      fetch(`${baseUrl}${path}`, {
        method: 'POST',
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
  ): Promise<ApiResult<T>> {
    let response: Response;
    try {
      response = await send(path, body);
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
