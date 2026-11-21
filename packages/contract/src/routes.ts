// Route table that `scripts/openapi.ts` turns into docs/openapi.yaml (SPEC §7).
// Schemas here must mirror the types in types.ts.

type JsonSchemaType = 'object' | 'string' | 'number' | 'integer' | 'boolean' | 'array' | 'null';

export type JsonSchema = {
  type?: JsonSchemaType | JsonSchemaType[];
  format?: 'uuid' | 'date-time' | 'date';
  properties?: Record<string, JsonSchema>;
  required?: string[];
  const?: string | number | boolean;
  enum?: string[];
  items?: JsonSchema;
};

export type Route = {
  method: 'get' | 'post' | 'patch' | 'delete';
  path: string;
  summary: string;
  public: boolean;
  requestBody?: { schemaName: string; schema: JsonSchema };
  // A `schemaName` that already exists in `componentSchemas` is referenced, not redefined.
  // Only 204 may omit the body schema, so a 200/201 can never publish without one.
  response:
    | { status: 204; schemaName?: undefined; schema?: undefined }
    | { status: number; schemaName: string; schema?: JsonSchema };
};

// Path parameter names, derived from the `{name}` segments of a route path.
export const pathParams = (path: string): string[] =>
  [...path.matchAll(/\{(\w+)\}/g)].map((m) => m[1]!);

const uuid: JsonSchema = { type: 'string', format: 'uuid' };
const dateTime: JsonSchema = { type: 'string', format: 'date-time' };
const nullableDateTime: JsonSchema = { type: ['string', 'null'], format: 'date-time' };

export const routes: Route[] = [
  {
    method: 'get',
    path: '/health',
    summary: 'Liveness',
    public: true,
    response: {
      status: 200,
      schemaName: 'HealthResponse',
      schema: {
        type: 'object',
        properties: { ok: { type: 'boolean', const: true } },
        required: ['ok'],
      },
    },
  },
  {
    method: 'post',
    path: '/households',
    summary: 'Create household with defaults',
    public: false,
    requestBody: {
      schemaName: 'CreateHouseholdRequest',
      schema: {
        type: 'object',
        properties: { name: { type: 'string' } },
        required: ['name'],
      },
    },
    response: { status: 201, schemaName: 'Household' },
  },
  {
    method: 'post',
    path: '/households/{id}/invites',
    summary: 'Create an invite code',
    public: false,
    requestBody: {
      schemaName: 'CreateInviteRequest',
      schema: {
        type: 'object',
        properties: {
          role: { type: 'string', enum: ['owner', 'member'] },
          expires_in_days: { type: 'integer' },
          max_uses: { type: 'integer' },
        },
        required: [],
      },
    },
    response: { status: 201, schemaName: 'Invite' },
  },
  {
    method: 'patch',
    path: '/households/{id}/modules',
    summary: 'Toggle modules (owner)',
    public: false,
    requestBody: {
      schemaName: 'UpdateModulesRequest',
      schema: {
        type: 'object',
        properties: {
          shopping: { type: 'boolean' },
          pantry: { type: 'boolean' },
          maintenance: { type: 'boolean' },
        },
        required: [],
      },
    },
    response: { status: 200, schemaName: 'Household' },
  },
  {
    method: 'post',
    path: '/invites/{code}/accept',
    summary: 'Join a household with an invite code',
    public: false,
    response: {
      status: 200,
      schemaName: 'AcceptInviteResponse',
      schema: {
        type: 'object',
        properties: { household_id: { type: 'string', format: 'uuid' } },
        required: ['household_id'],
      },
    },
  },
  {
    method: 'post',
    path: '/attachments/sign',
    summary: 'Signed upload URL for an attachment',
    public: false,
    requestBody: {
      schemaName: 'SignAttachmentRequest',
      schema: {
        type: 'object',
        properties: {
          household_id: uuid,
          entity: { type: 'string', enum: ['pantry_item', 'asset', 'maint_log'] },
          entity_id: uuid,
          filename: { type: 'string' },
          mime: { type: 'string' },
          size: { type: 'integer' },
        },
        required: ['household_id', 'entity', 'entity_id', 'filename', 'mime', 'size'],
      },
    },
    response: {
      status: 201,
      schemaName: 'SignAttachmentResponse',
      schema: {
        type: 'object',
        properties: { attachment_id: uuid, upload_url: { type: 'string' }, storage_path: { type: 'string' } },
        required: ['attachment_id', 'upload_url', 'storage_path'],
      },
    },
  },
  {
    method: 'post',
    path: '/lists/{id}/share',
    summary: 'Create or rotate the read-only share link',
    public: false,
    response: {
      status: 200,
      schemaName: 'ShareLinkResponse',
      schema: {
        type: 'object',
        properties: { url: { type: 'string' } },
        required: ['url'],
      },
    },
  },
  {
    method: 'delete',
    path: '/lists/{id}/share',
    summary: 'Revoke the share link',
    public: false,
    response: { status: 204 },
  },
  {
    method: 'get',
    path: '/shared/{token}',
    summary: 'Read-only list view for a share link',
    public: true,
    response: {
      status: 200,
      schemaName: 'SharedListResponse',
      schema: {
        type: 'object',
        properties: {
          list: { type: 'object', properties: { name: { type: 'string' } }, required: ['name'] },
          items: {
            type: 'array',
            items: {
              type: 'object',
              properties: { name: { type: 'string' }, quantity: { type: ['number', 'null'] } },
              required: ['name', 'quantity'],
            },
          },
        },
        required: ['list', 'items'],
      },
    },
  },
  {
    method: 'delete',
    path: '/households/{id}/members/{user_id}',
    summary: 'Remove a member or leave',
    public: false,
    response: { status: 204 },
  },
];


/**
 * Component schemas for shared entities in types.ts (SPEC §8); Swift/Kotlin models are generated from these.
  */
export const componentSchemas: Record<string, JsonSchema> = {
  Household: {
    type: 'object',
    properties: {
      id: uuid,
      name: { type: 'string' },
      created_by: uuid,
      module_shopping: { type: 'boolean' },
      module_pantry: { type: 'boolean' },
      module_maintenance: { type: 'boolean' },
      currency: { type: 'string' },
      created_at: dateTime,
      updated_at: dateTime,
      deleted_at: nullableDateTime,
      sync_seq: { type: 'integer' },
    },
    required: [
      'id', 'name', 'created_by', 'module_shopping', 'module_pantry', 'module_maintenance',
      'currency', 'created_at', 'updated_at', 'deleted_at', 'sync_seq',
    ],
  },
  Invite: {
    type: 'object',
    properties: {
      id: uuid,
      household_id: uuid,
      code: { type: 'string' },
      role: { type: 'string', enum: ['owner', 'member'] },
      created_by: uuid,
      expires_at: dateTime,
      max_uses: { type: 'integer' },
      uses: { type: 'integer' },
      created_at: dateTime,
      updated_at: dateTime,
      deleted_at: nullableDateTime,
      sync_seq: { type: 'integer' },
    },
    required: [
      'id', 'household_id', 'code', 'role', 'created_by', 'expires_at', 'max_uses', 'uses',
      'created_at', 'updated_at', 'deleted_at', 'sync_seq',
    ],
  },
  Activity: {
    type: 'object',
    properties: {
      id: uuid,
      household_id: uuid,
      actor_id: { type: ['string', 'null'], format: 'uuid' },
      module: { type: 'string', enum: ['shopping', 'pantry', 'maintenance', 'core'] },
      entity: { type: 'string' },
      entity_id: uuid,
      action: { type: 'string' },
      summary: { type: 'string' },
      created_at: dateTime,
      updated_at: dateTime,
      deleted_at: nullableDateTime,
      sync_seq: { type: 'integer' },
    },
    required: [
      'id', 'household_id', 'actor_id', 'module', 'entity', 'entity_id', 'action', 'summary',
      'created_at', 'updated_at', 'deleted_at', 'sync_seq',
    ],
  },
  Profile: {
    type: 'object',
    properties: {
      id: uuid,
      display_name: { type: 'string' },
      avatar_path: { type: ['string', 'null'] },
      created_at: dateTime,
      updated_at: dateTime,
    },
    required: ['id', 'display_name', 'avatar_path', 'created_at', 'updated_at'],
  },
  Member: {
    type: 'object',
    properties: {
      household_id: uuid,
      user_id: uuid,
      role: { type: 'string', enum: ['owner', 'member'] },
      joined_at: dateTime,
      left_at: nullableDateTime,
      updated_at: dateTime,
      sync_seq: { type: 'integer' },
    },
    required: ['household_id', 'user_id', 'role', 'joined_at', 'left_at', 'updated_at', 'sync_seq'],
  },
  Attachment: {
    type: 'object',
    properties: {
      id: uuid,
      household_id: uuid,
      entity: { type: 'string' },
      entity_id: uuid,
      storage_path: { type: 'string' },
      mime: { type: 'string' },
      size_bytes: { type: 'integer' },
      created_by: { type: ['string', 'null'], format: 'uuid' },
      created_at: dateTime,
      updated_at: dateTime,
      deleted_at: nullableDateTime,
      sync_seq: { type: 'integer' },
    },
    required: [
      'id', 'household_id', 'entity', 'entity_id', 'storage_path', 'mime', 'size_bytes', 'created_by',
      'created_at', 'updated_at', 'deleted_at', 'sync_seq',
    ],
  },
};
