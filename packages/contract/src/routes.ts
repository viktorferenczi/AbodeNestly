// Route table that `scripts/openapi.ts` turns into docs/openapi.yaml (SPEC §7).
// Schemas here must mirror the types in types.ts.

export type JsonSchema = {
  type?: 'object' | 'string' | 'number' | 'integer' | 'boolean' | 'array';
  properties?: Record<string, JsonSchema>;
  required?: string[];
  const?: string | number | boolean;
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
