import { isCreateHouseholdRequest } from '@abodenestly/contract';
import { verifyJwt } from '../_lib/auth.ts';
import { withUser } from '../_lib/db.ts';
import { ApiError } from '../_lib/errors.ts';
import { createHousehold, validateHouseholdName } from '../_lib/households.ts';
import { errorResponse, json, parseJson } from '../_lib/http.ts';

export const config = { runtime: 'nodejs' };

// POST /households — create a household with its defaults (SPEC §7, §9 X8). 201: new resource.
export default async function handler(req: Request): Promise<Response> {
  try {
    if (req.method !== 'POST') throw new ApiError(405, 'method_not_allowed', 'Use POST');
    const { claims } = await verifyJwt(req);
    const body = await parseJson(req, isCreateHouseholdRequest);
    const name = validateHouseholdName(body);
    const household = await withUser(claims, (client) => createHousehold(client, name));
    return json(household, 201);
  } catch (e) {
    return errorResponse(e);
  }
}
