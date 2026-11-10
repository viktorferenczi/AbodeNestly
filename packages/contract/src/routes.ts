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
  minLength?: number;
};

export type Route = {
  method: 'get' | 'post' | 'patch' | 'delete';
  path: string;
  summary: string;
  public: boolean;
  requestBody?: { schemaName: string; schema: JsonSchema };
  // A `schemaName` that already exists in `componentSchemas` is referenced, not redefined.
  response: { status: number; schemaName: string; schema?: JsonSchema };
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
  {
    method: 'post',
    path: '/households',
    summary: 'Create household with defaults',
    public: false,
    requestBody: {
      schemaName: 'CreateHouseholdRequest',
      schema: {
        type: 'object',
        properties: { name: { type: 'string', minLength: 1 } },
        required: ['name'],
      },
    },
    response: { status: 201, schemaName: 'Household' },
  },
];

const uuid: JsonSchema = { type: 'string', format: 'uuid' };
const dateTime: JsonSchema = { type: 'string', format: 'date-time' };
const nullableDateTime: JsonSchema = { type: ['string', 'null'], format: 'date-time' };

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
};
