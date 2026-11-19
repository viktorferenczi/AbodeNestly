// Hand-written type guards for request bodies (SPEC §8).
import type {
  CreateHouseholdRequest,
  CreateInviteRequest,
  SignAttachmentRequest,
  UpdateModulesRequest,
} from './types.ts';

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

const absentOrBoolean = (v: unknown): boolean => v === undefined || typeof v === 'boolean';

export function isUpdateModulesRequest(v: unknown): v is UpdateModulesRequest {
  return (
    isRecord(v) &&
    absentOrBoolean(v.shopping) &&
    absentOrBoolean(v.pantry) &&
    absentOrBoolean(v.maintenance)
  );
}

const ATTACHMENT_ENTITIES: readonly unknown[] = ['pantry_item', 'asset', 'maint_log'];

// Shape only; mime/size/UUID rules are the API's `validation` errors.
export function isSignAttachmentRequest(v: unknown): v is SignAttachmentRequest {
  return (
    isRecord(v) &&
    typeof v.household_id === 'string' &&
    ATTACHMENT_ENTITIES.includes(v.entity) &&
    typeof v.entity_id === 'string' &&
    typeof v.filename === 'string' &&
    typeof v.mime === 'string' &&
    Number.isInteger(v.size)
  );
}
