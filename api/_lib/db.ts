import pg from 'pg';
import type { JWTPayload } from 'jose';

let pool: pg.Pool | undefined;

function getPool(): pg.Pool {
  pool ??= new pg.Pool({ connectionString: process.env.DATABASE_URL, max: 3 });
  return pool;
}

// Runs fn as the `authenticated` role with the caller's claims so RLS applies exactly as
// it does for the Supabase SDK. Never the service role for user requests.
export async function withUser<T>(
  claims: JWTPayload,
  fn: (client: pg.PoolClient) => Promise<T>,
): Promise<T> {
  const client = await getPool().connect();
  try {
    await client.query('begin');
    await client.query('set local role authenticated');
    await client.query("select set_config('request.jwt.claims', $1, true)", [JSON.stringify(claims)]);
    const result = await fn(client);
    await client.query('commit');
    return result;
  } catch (e) {
    await client.query('rollback').catch(() => undefined);
    throw e;
  } finally {
    client.release();
  }
}
