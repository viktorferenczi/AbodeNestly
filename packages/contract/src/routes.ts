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
  response: { status: number; schemaName: string; schema: JsonSchema };
};

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
];

const uuid: JsonSchema = { type: 'string', format: 'uuid' };
const dateTime: JsonSchema = { type: 'string', format: 'date-time' };
const nullableDateTime: JsonSchema = { type: ['string', 'null'], format: 'date-time' };

/** Component schemas for the shared entities in types.ts (SPEC §8); Swift/Kotlin models are generated from these. */
export const componentSchemas: Record<string, JsonSchema> = {
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
  Household: {
    type: 'object',
    properties: {
      id: uuid,
      household_id: uuid,
      created_at: dateTime,
      updated_at: dateTime,
      deleted_at: nullableDateTime,
      sync_seq: { type: 'integer' },
      name: { type: 'string' },
      created_by: uuid,
      module_shopping: { type: 'boolean' },
      module_pantry: { type: 'boolean' },
      module_maintenance: { type: 'boolean' },
      currency: { type: 'string' },
    },
    required: [
      'id',
      'household_id',
      'created_at',
      'updated_at',
      'deleted_at',
      'sync_seq',
      'name',
      'created_by',
      'module_shopping',
      'module_pantry',
      'module_maintenance',
      'currency',
    ],
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
};
