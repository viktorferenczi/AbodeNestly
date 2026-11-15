import { isUpdateModulesRequest } from '@abodenestly/contract';
import { verifyJwt } from '../../_lib/auth.ts';
import { withUser } from '../../_lib/db.ts';
import { ApiError } from '../../_lib/errors.ts';
import { updateModules, validateUpdateModules } from '../../_lib/households.ts';
import { errorResponse, json, parseJson, pathSegment } from '../../_lib/http.ts';
import { isUuid } from '../../_lib/invites.ts';

export const config = { runtime: 'nodejs' };

// PATCH /households/{id}/modules — owner toggles modules (SPEC §7, CORE-05). Auth before body
// validation so a missing JWT is 401 whatever the body.
export default async function handler(req: Request): Promise<Response> {
  try {
    if (req.method !== 'PATCH') throw new ApiError(405, 'method_not_allowed', 'Use PATCH');
    const { claims } = await verifyJwt(req);
    const householdId = pathSegment(req, 1);
    if (!isUuid(householdId)) throw new ApiError(400, 'validation', 'Household id must be a UUID');
    const input = validateUpdateModules(await parseJson(req, isUpdateModulesRequest));
    const household = await withUser(claims, (client) => updateModules(client, householdId, input));
    return json(household);
  } catch (e) {
    return errorResponse(e);
  }
}
