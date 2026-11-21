import type { ApiError as ApiErrorBody } from '@abodenestly/contract';
import { ApiError } from './errors.ts';

export function json<T>(body: T, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json; charset=utf-8' },
  });
}

export function errorResponse(e: unknown): Response {
  if (e instanceof ApiError) {
    const body: ApiErrorBody = { error: { code: e.code, message: e.message } };
    return json(body, e.status);
  }
  // Deadlock (40P01) and serialization failure (40001) abort one transaction and are safe to retry.
  const pgCode = (e as { code?: unknown } | null)?.code;
  if (pgCode === '40P01' || pgCode === '40001') {
    const body: ApiErrorBody = { error: { code: 'retryable', message: 'Conflicting write, try again' } };
    return json(body, 503);
  }
  console.error(e);
  const body: ApiErrorBody = { error: { code: 'internal', message: 'Internal error' } };
  return json(body, 500);
}

export async function parseJson<T>(req: Request, guard: (v: unknown) => v is T): Promise<T> {
  let value: unknown;
  try {
    value = await req.json();
  } catch {
    throw new ApiError(400, 'validation', 'Body must be valid JSON');
  }
  if (!guard(value)) throw new ApiError(400, 'validation', 'Body has the wrong shape');
  return value;
}

// Path segment by index, read from the URL so it works under Vercel and the dev server alike.
// `/households/<id>/invites` -> index 1 is the id.
export function pathSegment(req: Request, index: number): string {
  return decodeURIComponent(new URL(req.url).pathname.split('/').filter(Boolean)[index] ?? '');
}
