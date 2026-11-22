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

// ---------- shopping (SPEC §5) ----------
export type ListItemSource = 'manual' | 'pantry' | 'maintenance' | 'template' | 'recipe';

export interface Store extends Synced {
  name: string;
}

export interface ShoppingList extends Synced {
  name: string;
  store_id: UUID | null;
  is_template: boolean;
  readonly share_token: string | null; // server-owned: written only by POST/DELETE /lists/{id}/share, never by a sync upsert
  archived_at: ISODateTime | null;
}

export interface ListItem extends Synced {
  list_id: UUID;
  name: string;
  readonly name_key: string;
  quantity: number | null;
  unit: string | null;
  note: string | null;
  barcode: string | null;
  checked: boolean;
  checked_at: ISODateTime | null;
  checked_by: UUID | null;
  manual_position: number | null;
  source: ListItemSource;
  pantry_item_id: UUID | null;
  maint_task_id: UUID | null;
  created_by: UUID | null;
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

/** `PATCH /households/{id}/modules` body. Partial; the API requires at least one flag (`validation`). */
export type UpdateModulesRequest = { shopping?: boolean; pantry?: boolean; maintenance?: boolean };

/**
 * A sentence a member sees on Home (SPEC §8, CORE-08). Written server-side (X6); clients only read it,
 * so there is no request type. `module` is `core` for household-level events.
 */
export interface Activity extends Synced {
  actor_id: UUID | null;
  module: Module | 'core';
  entity: string;
  entity_id: UUID;
  action: string;
  summary: string;
}

/** `POST /invites/{code}/accept` response. */
export type AcceptInviteResponse = { household_id: UUID };

// ---------- attachments (CORE-11) ----------
/** Entities that can carry an attachment (SPEC §4.1 CORE-11). Maps to a table in the API, never from input. */
export type AttachmentEntity = 'pantry_item' | 'asset' | 'maint_log';

export interface Attachment extends Synced {
  entity: string;
  entity_id: UUID;
  storage_path: string;
  mime: string;
  size_bytes: number;
  created_by: UUID | null;
}

/** `POST /attachments/sign` body. Mime and size limits are checked by the API (`validation`). */
export type SignAttachmentRequest = {
  household_id: UUID;
  entity: AttachmentEntity;
  entity_id: UUID;
  filename: string;
  mime: string;
  size: number;
};

/** `POST /attachments/sign` response: the row exists; the client PUTs the bytes to `upload_url`. */
export type SignAttachmentResponse = {
  attachment_id: UUID;
  upload_url: string;
  storage_path: string;
};

// ---------- share links (SHOP-13) ----------
/** `POST /lists/{id}/share` response: the public URL of the read-only view. */
export type ShareLinkResponse = { url: string };

/** `GET /shared/{token}` response (SPEC §11): the list name and unchecked items only, no ids. */
export type SharedListResponse = {
  list: { name: string };
  items: Array<{ name: string; quantity: number | null }>;
};

// ---------- notification preferences (CORE-12) ----------
export type NotificationType = 'eat_soon' | 'low_stock' | 'task_due' | 'warranty' | 'activity';

/** One switch per (user, household, type). A missing row means enabled. */
export interface NotificationPref {
  user_id: UUID;
  household_id: UUID;
  type: NotificationType;
  enabled: boolean;
}

// ---------- sync (SPEC §6.2, §6.3) ----------
export type MovementReason = 'added' | 'used' | 'restocked' | 'discarded' | 'adjusted';

export type SyncTable =
  | 'household' | 'member' | 'invite' | 'activity' | 'attachment'
  | 'store' | 'shopping_list' | 'list_item' | 'store_item_order' | 'item_price'
  | 'location' | 'category' | 'pantry_item' | 'pantry_movement'
  | 'unit_conversion' | 'recipe' | 'recipe_ingredient'
  | 'house' | 'contractor' | 'asset' | 'maint_task' | 'task_consumable' | 'maint_log';

export interface MutationUpsert {
  id: UUID;
  table: SyncTable;
  op: 'upsert';
  row_id: UUID;
  data: Record<string, unknown>; // row fields minus server-owned columns
  client_ts: ISODateTime;
}
export interface MutationDelete {
  id: UUID;
  table: SyncTable;
  op: 'delete';
  row_id: UUID;
  client_ts: ISODateTime;
}
export interface MutationDelta {
  id: UUID;
  table: 'pantry_item';
  op: 'delta';
  row_id: UUID;
  data: { field: 'quantity'; delta: number; reason: MovementReason };
  client_ts: ISODateTime;
}
export type Mutation = MutationUpsert | MutationDelete | MutationDelta;

export interface SyncPushRequest {
  household_id: UUID;
  mutations: Mutation[];
}
export interface SyncRejection {
  id: UUID;
  reason: 'rls_denied' | 'validation' | 'unknown_table' | 'module_disabled';
  detail: string;
}
export interface SyncPushResponse {
  applied: UUID[];
  rejected: SyncRejection[];
}
export interface SyncPullResponse {
  cursor: number;
  has_more: boolean;
  changes: Partial<Record<SyncTable, unknown[]>>;
}
