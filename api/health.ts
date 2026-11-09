import type { HealthResponse } from '@abodenestly/contract';
import { json } from './_lib/http.ts';

export const config = { runtime: 'nodejs' };

// GET /health — public liveness check (SPEC §7). No auth, no database, so the
// Request Vercel passes is not read.
export default async function handler(): Promise<Response> {
  return json<HealthResponse>({ ok: true });
}
