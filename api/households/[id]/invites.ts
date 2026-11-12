import { isCreateInviteRequest } from '@abodenestly/contract';
import { verifyJwt } from '../../_lib/auth.ts';
import { withUser } from '../../_lib/db.ts';
import { ApiError } from '../../_lib/errors.ts';
import { errorResponse, json, parseJson, pathSegment } from '../../_lib/http.ts';
import { createInvite, isUuid, validateCreateInvite } from '../../_lib/invites.ts';

export const config = { runtime: 'nodejs' };

// POST /households/{id}/invites — mint an invite code (SPEC §7, CORE-03). 201: new resource.
export default async function handler(req: Request): Promise<Response> {
  try {
    if (req.method !== 'POST') throw new ApiError(405, 'method_not_allowed', 'Use POST');
    const { claims } = await verifyJwt(req);
    const householdId = pathSegment(req, 1);
    if (!isUuid(householdId)) throw new ApiError(400, 'validation', 'Household id must be a UUID');
    const input = validateCreateInvite(await parseJson(req, isCreateInviteRequest));
    const invite = await withUser(claims, (client) => createInvite(client, householdId, input));
    return json(invite, 201);
  } catch (e) {
    return errorResponse(e);
  }
}
