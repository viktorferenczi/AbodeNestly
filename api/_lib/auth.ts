import { createRemoteJWKSet, jwtVerify, type JWTPayload } from 'jose';
import { ApiError } from './errors.ts';

// Cached in module scope so warm function instances reuse fetched keys.
let jwks: ReturnType<typeof createRemoteJWKSet> | undefined;

function keys(): ReturnType<typeof createRemoteJWKSet> {
  jwks ??= createRemoteJWKSet(new URL(process.env.SUPABASE_JWKS_URL ?? ''));
  return jwks;
}

export async function verifyJwt(req: Request): Promise<{ sub: string; claims: JWTPayload }> {
  const header = req.headers.get('authorization') ?? '';
  const match = /^Bearer (\S+)$/i.exec(header);
  if (!match) throw new ApiError(401, 'unauthorized', 'Missing or malformed bearer token');
  try {
    const { payload } = await jwtVerify(match[1]!, keys(), {
      issuer: `${process.env.SUPABASE_URL}/auth/v1`,
    });
    if (!payload.sub) throw new Error('no sub');
    return { sub: payload.sub, claims: payload };
  } catch {
    throw new ApiError(401, 'unauthorized', 'Invalid token');
  }
}
