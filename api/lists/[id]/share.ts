import { verifyJwt } from '../../_lib/auth.ts';
import { withUser } from '../../_lib/db.ts';
import { ApiError } from '../../_lib/errors.ts';
import { errorResponse, json, pathSegment } from '../../_lib/http.ts';
import { isUuid } from '../../_lib/invites.ts';
import { createShare, revokeShare } from '../../_lib/shares.ts';

export const config = { runtime: 'nodejs' };

// POST /lists/{id}/share creates or rotates the link; DELETE revokes it (SPEC §7, SHOP-13).
export default async function handler(req: Request): Promise<Response> {
  try {
    if (req.method !== 'POST' && req.method !== 'DELETE') {
      throw new ApiError(405, 'method_not_allowed', 'Use POST or DELETE');
    }
    const { claims } = await verifyJwt(req);
    const listId = pathSegment(req, 1);
    if (!isUuid(listId)) throw new ApiError(400, 'validation', 'List id must be a UUID');
    if (req.method === 'POST') return json(await withUser(claims, (c) => createShare(c, listId.toLowerCase())));
    await withUser(claims, (c) => revokeShare(c, listId.toLowerCase()));
    return new Response(null, { status: 204 });
  } catch (e) {
    return errorResponse(e);
  }
}
