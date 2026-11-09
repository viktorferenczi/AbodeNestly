import { SignJWT, createLocalJWKSet, exportJWK, generateKeyPair, type JWK } from 'jose';
import { beforeAll, describe, expect, it } from 'vitest';
import { createVerifier } from '../_lib/auth.ts';
import { ApiError } from '../_lib/errors.ts';

const ISSUER = 'http://127.0.0.1:54321/auth/v1';
const SUB = '6f1c2c0e-8f5b-4b7e-9a39-0d3a6d2d1a11';

type Key = Awaited<ReturnType<typeof generateKeyPair>>['privateKey'];
let keyA: Key;
let keyB: Key;
let verify: ReturnType<typeof createVerifier>;

async function mint(
  key: Key,
  opts: { sub?: string | null; iss?: string; exp?: string | number } = {},
): Promise<string> {
  const jwt = new SignJWT({ role: 'authenticated' })
    .setProtectedHeader({ alg: 'ES256', kid: 'a' })
    .setIssuer(opts.iss ?? ISSUER)
    .setAudience('authenticated')
    .setIssuedAt()
    .setExpirationTime(opts.exp ?? '1h');
  if (opts.sub !== null) jwt.setSubject(opts.sub ?? SUB);
  return jwt.sign(key);
}

const req = (authorization?: string) =>
  new Request('http://localhost/x', authorization ? { headers: { authorization } } : {});

async function expectUnauthorized(p: Promise<unknown>) {
  const err = await p.then(
    () => undefined,
    (e: unknown) => e,
  );
  expect(err).toBeInstanceOf(ApiError);
  expect(err).toMatchObject({ status: 401, code: 'unauthorized' });
}

beforeAll(async () => {
  const a = await generateKeyPair('ES256');
  const b = await generateKeyPair('ES256');
  keyA = a.privateKey;
  keyB = b.privateKey;
  const jwk: JWK = { ...(await exportJWK(a.publicKey)), kid: 'a', alg: 'ES256', use: 'sig' };
  verify = createVerifier({ jwks: createLocalJWKSet({ keys: [jwk] }), issuer: ISSUER });
});

describe('verifyJwt', () => {
  it('CORE-01: AC2 rejects a token signed by a different key', async () => {
    await expectUnauthorized(verify(req(`Bearer ${await mint(keyB)}`)));
  });

  it('CORE-01: AC3 returns sub from a valid token', async () => {
    const user = await verify(req(`Bearer ${await mint(keyA)}`));
    expect(user.sub).toBe(SUB);
    expect(user.claims.role).toBe('authenticated');
  });

  it.each([
    ['no header', async () => req()],
    ['non-Bearer scheme', async () => req(`Basic ${await mint(keyA)}`)],
    ['expired token', async () => req(`Bearer ${await mint(keyA, { exp: Math.floor(Date.now() / 1000) - 60 })}`)],
    ['wrong iss', async () => req(`Bearer ${await mint(keyA, { iss: 'https://evil.example/auth/v1' })}`)],
    ['missing sub', async () => req(`Bearer ${await mint(keyA, { sub: null })}`)],
  ])('CORE-01: rejects %s', async (_name, build) => {
    await expectUnauthorized(verify(await build()));
  });
});
