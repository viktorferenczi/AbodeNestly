import type { ApiError as ApiErrorBody } from '@abodenestly/contract';
import { ApiError } from './errors.ts';

export function json<T>(body: T, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json; charset=utf-8' },
  });
}

// Unknown errors become an opaque 500 so internals never reach the client.
export function errorResponse(e: unknown): Response {
  if (e instanceof ApiError) {
    return json<ApiErrorBody>({ error: { code: e.code, message: e.message } }, e.status);
  }
  console.error(e);
  return json<ApiErrorBody>({ error: { code: 'internal', message: 'Internal error' } }, 500);
}
