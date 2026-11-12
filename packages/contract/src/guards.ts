// Hand-written type guards for request bodies (SPEC §8).
import type { CreateHouseholdRequest, CreateInviteRequest } from './types.ts';

function isRecord(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null && !Array.isArray(v);
}

export function isCreateHouseholdRequest(v: unknown): v is CreateHouseholdRequest {
  return isRecord(v) && typeof v.name === 'string';
}

const absentOrInteger = (v: unknown): boolean => v === undefined || Number.isInteger(v);

export function isCreateInviteRequest(v: unknown): v is CreateInviteRequest {
  return (
    isRecord(v) &&
    (v.role === undefined || v.role === 'owner' || v.role === 'member') &&
    absentOrInteger(v.expires_in_days) &&
    absentOrInteger(v.max_uses)
  );
}
