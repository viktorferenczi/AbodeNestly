import { ApiError } from '../_lib/errors.ts';
import { errorResponse, json, pathSegment } from '../_lib/http.ts';
import { readShared } from '../_lib/shares.ts';

export const config = { runtime: 'nodejs' };

// GET /shared/{token} is public: the token is the only credential (SPEC §7, §11, SHOP-13).
export default async function handler(req: Request): Promise<Response> {
  try {
    if (req.method !== 'GET') throw new ApiError(405, 'method_not_allowed', 'Use GET');
    const token = pathSegment(req, 1);
    return json(await readShared(token));
  } catch (e) {
    return errorResponse(e);
  }
}
