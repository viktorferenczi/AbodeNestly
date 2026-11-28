// Allow-list for GET/POST /sync (SPEC §6, node-backend skill). Table and column names reach SQL only
// from here, never from request input. Each later `-db` story registers its own table in this file.

type PullOnly = { scope: 'id' | 'household_id'; push: false };
type Pushable = { scope: 'household_id'; push: true; columns: readonly string[] };
export type SyncTableDef = PullOnly | Pushable;

// Dropped silently from an upsert unless the table's `columns` names the key: the server owns them (SPEC §6.2). `id` comes from `row_id` and
// `household_id` is forced to the request's; the rest are written by defaults, triggers or the share endpoints.
export const SERVER_OWNED: ReadonlySet<string> = new Set([
  'id',
  'household_id',
  'created_at',
  'updated_at',
  'sync_seq',
  'name_key',
  'share_token',
]);

// Pull-only tables are written by API endpoints, triggers or other stories (household, member, invite,
// activity, attachment) or have no `id` to key a mutation on (member, store_item_order).
export const SYNC_TABLES: Readonly<Record<string, SyncTableDef>> = {
  household: { scope: 'id', push: false },
  member: { scope: 'household_id', push: false },
  invite: { scope: 'household_id', push: false },
  activity: { scope: 'household_id', push: false },
  attachment: { scope: 'household_id', push: false },
  store_item_order: { scope: 'household_id', push: false },
  store: { scope: 'household_id', push: true, columns: ['name', 'deleted_at'] },
  shopping_list: {
    scope: 'household_id',
    push: true,
    columns: ['name', 'store_id', 'is_template', 'archived_at', 'deleted_at'],
  },
  list_item: {
    scope: 'household_id',
    push: true,
    columns: [
      'list_id', 'name', 'quantity', 'unit', 'note', 'barcode', 'checked', 'checked_at', 'checked_by',
      'manual_position', 'source', 'pantry_item_id', 'maint_task_id', 'created_by', 'deleted_at',
    ],
  },
  // Clients must send name_key for this table only (no name column; a trigger normalises it).
  item_price: {
    scope: 'household_id',
    push: true,
    columns: ['store_id', 'name_key', 'price', 'seen_at', 'deleted_at'],
  },
  location: { scope: 'household_id', push: true, columns: ['name', 'kind', 'deleted_at'] },
};
