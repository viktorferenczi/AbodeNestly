import pg from 'pg';
import type { VerifiedUser } from './auth.ts';

let pool: pg.Pool | undefined;

function getPool(): pg.Pool {
  if (!process.env.DATABASE_URL) throw new Error('DATABASE_URL is not set');
  pool ??= new pg.Pool({ connectionString: process.env.DATABASE_URL, max: 3 });
  return pool;
}

// Forwards the verified claims so RLS applies exactly as for the Supabase SDK (SPEC §11).
export async function withUser<T>(
  user: VerifiedUser,
  fn: (client: pg.PoolClient) => Promise<T>,
): Promise<T> {
  const client = await getPool().connect();
  try {
    await client.query('begin');
    await client.query('set local role authenticated');
    await client.query("select set_config('request.jwt.claims', $1, true)", [
      JSON.stringify(user.claims),
    ]);
    const result = await fn(client);
    await client.query('commit');
    return result;
  } catch (e) {
    await client.query('rollback').catch(() => {});
    throw e;
  } finally {
    client.release();
  }
}

export async function closePool(): Promise<void> {
  const p = pool;
  pool = undefined;
  await p?.end();
}
