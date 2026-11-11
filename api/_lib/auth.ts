import { createRemoteJWKSet, errors, jwtVerify, type JWTPayload } from 'jose';
import { ApiError } from './errors.ts';

// Cached in module scope so warm function instances reuse fetched keys.
let jwks: ReturnType<typeof createRemoteJWKSet> | undefined;

function keys(): ReturnType<typeof createRemoteJWKSet> {
  jwks ??= createRemoteJWKSet(new URL(process.env.SUPABASE_JWKS_URL ?? ''));
  return jwks;
}

// Only token problems are the client's fault. Missing config or an unreachable JWKS must stay
// a 500, or clients would read it as a dead session and sign the user out.
const TOKEN_ERRORS = [
  errors.JWTExpired,
  errors.JWTClaimValidationFailed,
  errors.JWTInvalid,
  errors.JWSInvalid,
  errors.JWSSignatureVerificationFailed,
  errors.JWKSNoMatchingKey,
  errors.JOSEAlgNotAllowed,
];

export async function verifyJwt(req: Request): Promise<{ sub: string; claims: JWTPayload }> {
  const header = req.headers.get('authorization') ?? '';
  const match = /^Bearer (\S+)$/i.exec(header);
  if (!match) throw new ApiError(401, 'unauthorized', 'Missing or malformed bearer token');
  let payload: JWTPayload;
  try {
    ({ payload } = await jwtVerify(match[1]!, keys(), {
      issuer: `${process.env.SUPABASE_URL}/auth/v1`,
    }));
  } catch (e) {
    if (TOKEN_ERRORS.some((E) => e instanceof E)) throw new ApiError(401, 'unauthorized', 'Invalid token');
    throw e;
  }
  if (!payload.sub) throw new ApiError(401, 'unauthorized', 'Token has no subject');
  return { sub: payload.sub, claims: payload };
}
