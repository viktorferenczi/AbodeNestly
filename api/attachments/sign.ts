import { isSignAttachmentRequest } from '@abodenestly/contract';
import { signAttachment, validateSignRequest } from '../_lib/attachments.ts';
import { verifyJwt } from '../_lib/auth.ts';
import { withUser } from '../_lib/db.ts';
import { ApiError } from '../_lib/errors.ts';
import { errorResponse, json, parseJson } from '../_lib/http.ts';

export const config = { runtime: 'nodejs' };

// POST /attachments/sign — inserts the attachment row and returns a signed upload URL (SPEC §7, CORE-11).
export default async function handler(req: Request): Promise<Response> {
  try {
    if (req.method !== 'POST') throw new ApiError(405, 'method_not_allowed', 'Use POST');
    const { sub, claims, token } = await verifyJwt(req);
    const input = validateSignRequest(await parseJson(req, isSignAttachmentRequest));
    const result = await withUser(claims, (client) => signAttachment(client, sub, token, input));
    return json(result, 201);
  } catch (e) {
    return errorResponse(e);
  }
}
