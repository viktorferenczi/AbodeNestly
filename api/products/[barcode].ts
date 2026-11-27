import { verifyJwt } from '../_lib/auth.ts';
import { ApiError } from '../_lib/errors.ts';
import { errorResponse, json, pathSegment } from '../_lib/http.ts';
import { defaultDeps, lookupProduct } from '../_lib/products.ts';

export const config = { runtime: 'nodejs' };

// GET /products/{barcode} (SPEC §7, SHOP-10). Not gated by a module flag: pantry uses it too.
export default async function handler(req: Request): Promise<Response> {
  try {
    if (req.method !== 'GET') throw new ApiError(405, 'method_not_allowed', 'Use GET');
    const { claims } = await verifyJwt(req);
    const product = await lookupProduct(pathSegment(req, 1), defaultDeps(claims));
    const res = json(product);
    // Product data rarely changes; let the client keep it for a day.
    res.headers.set('cache-control', 'private, max-age=86400');
    return res;
  } catch (e) {
    return errorResponse(e);
  }
}
