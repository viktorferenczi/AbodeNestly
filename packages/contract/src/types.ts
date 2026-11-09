// SPEC §8. Hand-written, zero dependencies. Feature stories add the rest.

/** `GET /health` (public). */
export type HealthResponse = { ok: true };

export type UUID = string; // RFC 4122 v4
export type ISODateTime = string; // "2026-11-08T10:12:00.000Z"

/** Error body for every non-2xx response (SPEC §7). */
export interface ApiError {
  error: { code: string; message: string };
}

export interface Profile {
  id: UUID;
  display_name: string;
  avatar_path: string | null;
  readonly created_at: ISODateTime;
  readonly updated_at: ISODateTime;
}
