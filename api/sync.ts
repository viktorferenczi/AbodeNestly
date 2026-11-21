import { isSyncPushRequest } from '@abodenestly/contract';
import { verifyJwt } from './_lib/auth.ts';
import { withUser } from './_lib/db.ts';
import { ApiError } from './_lib/errors.ts';
import { errorResponse, json, parseJson } from './_lib/http.ts';
import { isUuid } from './_lib/invites.ts';
import type { Queryable } from './_lib/queryable.ts';
import { DEFAULT_PULL_LIMIT, pullChanges } from './_lib/sync/pull.ts';
import { pushMutations } from './_lib/sync/push.ts';

export const config = { runtime: 'nodejs' };

const MAX_LIMIT = 500;

// A non-member gets 403, not an empty page: an empty page would look like a household with no data.
async function requireMember(client: Queryable, householdId: string): Promise<void> {
  const { rows } = await client.query('select is_member($1) as ok', [householdId]);
  if (!rows[0]?.ok) throw new ApiError(403, 'forbidden', 'Not a member of this household');
}

export function parsePullQuery(url: URL): { householdId: string; since: number; limit: number } {
  const householdId = url.searchParams.get('household_id') ?? '';
  if (!isUuid(householdId)) throw new ApiError(400, 'validation', 'household_id must be a UUID');
  const intParam = (name: string, fallback: number, min: number, max: number): number => {
    const raw = url.searchParams.get(name);
    if (raw === null) return fallback;
    const n = Number(raw);
    if (!/^\d+$/.test(raw) || !Number.isSafeInteger(n) || n < min || n > max) {
      throw new ApiError(400, 'validation', `${name} must be an integer from ${min} to ${max}`);
    }
    return n;
  };
  return {
    householdId: householdId.toLowerCase(),
    since: intParam('since', 0, 0, Number.MAX_SAFE_INTEGER),
    limit: intParam('limit', DEFAULT_PULL_LIMIT, 1, MAX_LIMIT),
  };
}

// GET /sync pulls changes after a cursor; POST /sync pushes outbox mutations (SPEC §6.3).
export default async function handler(req: Request): Promise<Response> {
  try {
    if (req.method !== 'GET' && req.method !== 'POST') {
      throw new ApiError(405, 'method_not_allowed', 'Use GET or POST');
    }
    const { claims } = await verifyJwt(req);
    if (req.method === 'GET') {
      const { householdId, since, limit } = parsePullQuery(new URL(req.url));
      return json(
        await withUser(claims, async (c) => {
          await requireMember(c, householdId);
          return pullChanges(c, householdId, since, limit);
        }),
      );
    }
    const body = await parseJson(req, isSyncPushRequest);
    const householdId = body.household_id.toLowerCase();
    return json(
      await withUser(claims, async (c) => {
        await requireMember(c, householdId);
        return pushMutations(c, householdId, body.mutations);
      }),
    );
  } catch (e) {
    return errorResponse(e);
  }
}
