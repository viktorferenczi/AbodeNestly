import { verifyJwt } from '../../_lib/auth.ts';
import { withUser } from '../../_lib/db.ts';
import { ApiError } from '../../_lib/errors.ts';
import { errorResponse, json, pathSegment } from '../../_lib/http.ts';
import { acceptInvite, isInviteCode } from '../../_lib/invites.ts';

export const config = { runtime: 'nodejs' };

// POST /invites/{code}/accept — join the household behind a live code (SPEC §7, CORE-03).
export default async function handler(req: Request): Promise<Response> {
  try {
    if (req.method !== 'POST') throw new ApiError(405, 'method_not_allowed', 'Use POST');
    const { claims } = await verifyJwt(req);
    const code = pathSegment(req, 1);
    if (!isInviteCode(code)) throw new ApiError(400, 'invalid_invite', 'Invite is invalid');
    return json(await withUser(claims, (client) => acceptInvite(client, code)));
  } catch (e) {
    return errorResponse(e);
  }
}
