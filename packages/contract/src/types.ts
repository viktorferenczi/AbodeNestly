// SPEC §8. Hand-written, zero dependencies. Feature stories add the rest.

/** `GET /health` (public). */
export type HealthResponse = { ok: true };

// ---------- primitives (SPEC §8) ----------
export type UUID = string; // RFC 4122 v4
export type ISODateTime = string; // "2026-11-08T10:12:00.000Z"

export type Role = 'owner' | 'member';
export type Module = 'shopping' | 'pantry' | 'maintenance';

/** Columns present on every household-scoped row. Server-owned fields are readonly. */
export interface Synced {
  id: UUID;
  household_id: UUID;
  readonly created_at: ISODateTime;
  readonly updated_at: ISODateTime;
  deleted_at: ISODateTime | null;
  readonly sync_seq: number;
}

// ---------- core ----------
export interface Profile {
  id: UUID;
  display_name: string;
  avatar_path: string | null;
  readonly created_at: ISODateTime;
  readonly updated_at: ISODateTime;
}

export interface Household extends Omit<Synced, 'household_id'> {
  name: string;
  created_by: UUID;
  module_shopping: boolean;
  module_pantry: boolean;
  module_maintenance: boolean;
  currency: string; // ISO 4217
}

/** `POST /households` body. Trimmed-non-empty is checked by the API (`validation`). */
export type CreateHouseholdRequest = { name: string };

/** Error body for every non-2xx response (SPEC §7, §8). */
export interface ApiError {
  error: { code: string; message: string };
}

export interface Member {
  household_id: UUID;
  user_id: UUID;
  role: Role;
  joined_at: ISODateTime;
  left_at: ISODateTime | null;
  readonly updated_at: ISODateTime;
  readonly sync_seq: number;
}

export interface Invite extends Synced {
  code: string;
  role: Role;
  created_by: UUID;
  expires_at: ISODateTime;
  max_uses: number;
  uses: number;
}

/** `POST /households/{id}/invites` body. Ranges are checked by the API (`validation`). */
export type CreateInviteRequest = { role?: Role; expires_in_days?: number; max_uses?: number };

/** `POST /invites/{code}/accept` response. */
export type AcceptInviteResponse = { household_id: UUID };
