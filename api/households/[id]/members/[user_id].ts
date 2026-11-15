import { verifyJwt } from '../../../_lib/auth.ts';
import { withUser } from '../../../_lib/db.ts';
import { ApiError } from '../../../_lib/errors.ts';
import { errorResponse, pathSegment } from '../../../_lib/http.ts';
import { isUuid } from '../../../_lib/invites.ts';
import { removeMember } from '../../../_lib/members.ts';

export const config = { runtime: 'nodejs' };

// DELETE /households/{id}/members/{user_id} — remove a member, or leave when it is yourself
// (SPEC §7, CORE-04). 204: nothing to return.
export default async function handler(req: Request): Promise<Response> {
  try {
    if (req.method !== 'DELETE') throw new ApiError(405, 'method_not_allowed', 'Use DELETE');
    const { claims } = await verifyJwt(req);
    const householdId = pathSegment(req, 1);
    const userId = pathSegment(req, 3);
    if (!isUuid(householdId)) throw new ApiError(400, 'validation', 'Household id must be a UUID');
    if (!isUuid(userId)) throw new ApiError(400, 'validation', 'User id must be a UUID');
    await withUser(claims, (client) => removeMember(client, householdId, userId));
    return new Response(null, { status: 204 });
  } catch (e) {
    return errorResponse(e);
  }
}
