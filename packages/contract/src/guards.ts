// Hand-written type guards for request bodies (SPEC §8).
import type { CreateHouseholdRequest } from './types.ts';

function isRecord(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null && !Array.isArray(v);
}

export function isCreateHouseholdRequest(v: unknown): v is CreateHouseholdRequest {
  return isRecord(v) && typeof v.name === 'string';
}
