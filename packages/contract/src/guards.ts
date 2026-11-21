// Hand-written type guards for request bodies (SPEC §8).
import type {
  CreateHouseholdRequest,
  Mutation,
  SyncPushRequest,
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

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const isUuidString = (v: unknown): v is string => typeof v === 'string' && UUID_RE.test(v);

const MOVEMENT_REASONS: readonly unknown[] = ['added', 'used', 'restocked', 'discarded', 'adjusted'];

// Envelope shape only (SPEC §6.2). Whether the table exists or the columns are allowed is the
// API's per-mutation `unknown_table` / `validation` rejection, so one bad mutation never fails the push.
export function isMutation(v: unknown): v is Mutation {
  if (!isRecord(v)) return false;
  if (!isUuidString(v.id) || !isUuidString(v.row_id) || typeof v.table !== 'string') return false;
  if (typeof v.client_ts !== 'string') return false;
  if (v.op === 'delete') return true;
  if (v.op === 'upsert') return isRecord(v.data);
  if (v.op === 'delta') {
    const d = v.data;
    return (
      v.table === 'pantry_item' &&
      isRecord(d) &&
      d.field === 'quantity' &&
      typeof d.delta === 'number' &&
      Number.isFinite(d.delta) &&
      MOVEMENT_REASONS.includes(d.reason)
    );
  }
  return false;
}

export const MAX_PUSH_MUTATIONS = 500;

export function isSyncPushRequest(v: unknown): v is SyncPushRequest {
  return (
    isRecord(v) &&
    isUuidString(v.household_id) &&
    Array.isArray(v.mutations) &&
    v.mutations.length <= MAX_PUSH_MUTATIONS &&
    v.mutations.every(isMutation)
  );
}
