import {
  createRemoteJWKSet,
  errors,
  jwtVerify,
  type JWTPayload,
  type JWTVerifyGetKey,
} from 'jose';
import { ApiError } from './errors.ts';

export interface VerifiedUser {
  sub: string;
  claims: JWTPayload;
}

const unauthorized = () => new ApiError(401, 'unauthorized', 'Missing or invalid credentials');

// Asymmetric only: a token signed with the legacy HS256 secret is rejected on purpose.
export function createVerifier({ jwks, issuer }: { jwks: JWTVerifyGetKey; issuer: string }) {
  return async (req: Request): Promise<VerifiedUser> => {
    const match = /^Bearer (\S+)$/.exec(req.headers.get('authorization') ?? '');
    if (!match) throw unauthorized();
    try {
      const { payload } = await jwtVerify(match[1]!, jwks, {
        issuer,
        audience: 'authenticated',
        algorithms: ['ES256', 'RS256'],
      });
      if (typeof payload.sub !== 'string' || payload.sub === '') throw unauthorized();
      return { sub: payload.sub, claims: payload };
    } catch (e) {
      if (e instanceof ApiError) throw e;
      // jose errors (bad signature, expired, wrong iss…) carry detail we do not leak.
      if (e instanceof errors.JOSEError) throw unauthorized();
      throw e;
    }
  };
}

let defaultVerifier: ReturnType<typeof createVerifier> | undefined;

function requireEnv(name: string): string {
  const v = process.env[name];
  if (!v) throw new Error(`${name} is not set`);
  return v;
}

// Built lazily so importing this module never needs env; the JWKS cache lives in module scope.
export function verifyJwt(req: Request): Promise<VerifiedUser> {
  defaultVerifier ??= createVerifier({
    jwks: createRemoteJWKSet(new URL(requireEnv('SUPABASE_JWKS_URL'))),
    issuer: `${requireEnv('SUPABASE_URL')}/auth/v1`,
  });
  return defaultVerifier(req);
}
