import type { AttachmentEntity, SignAttachmentRequest, SignAttachmentResponse } from '@abodenestly/contract';
import type { Queryable } from './queryable.ts';
import { ApiError } from './errors.ts';
import { isUuid } from './invites.ts';

// Table and module names come only from this map, never from request input, so interpolating
// them into SQL below is safe.
export const ENTITIES: Record<AttachmentEntity, { table: string; module: 'pantry' | 'maintenance' }> = {
  pantry_item: { table: 'pantry_item', module: 'pantry' },
  asset: { table: 'asset', module: 'maintenance' },
  maint_log: { table: 'maint_log', module: 'maintenance' },
};

export const ALLOWED_MIMES: readonly string[] = [
  'image/jpeg',
  'image/png',
  'image/webp',
  'image/heic',
  'application/pdf',
];
export const MAX_SIZE = 25 * 1024 * 1024;
const MAX_FILENAME = 100;

// One path segment of [A-Za-z0-9._-]: the Storage insert policy and the attachment CHECK both
// expect exactly one non-empty file segment under the entity folder.
export function sanitizeFilename(name: string): string {
  const safe = name
    .replace(/[^A-Za-z0-9._-]/g, '_')
    .replace(/\.{2,}/g, '.')
    .replace(/^\.+/, '')
    .slice(0, MAX_FILENAME);
  if (!safe) throw new ApiError(400, 'validation', 'filename is empty');
  return safe;
}

// Pure checks, run before any DB call so a bad request never costs a query.
export function validateSignRequest(body: SignAttachmentRequest): SignAttachmentRequest & { safeName: string } {
  if (!Object.hasOwn(ENTITIES, body.entity)) throw new ApiError(400, 'validation', 'Unknown entity');
  if (!ALLOWED_MIMES.includes(body.mime)) throw new ApiError(400, 'validation', 'mime type is not allowed');
  if (body.size < 1 || body.size > MAX_SIZE) throw new ApiError(400, 'validation', 'size must be 1 byte to 25 MB');
  if (!isUuid(body.household_id) || !isUuid(body.entity_id)) {
    throw new ApiError(400, 'validation', 'household_id and entity_id must be UUIDs');
  }
  return { ...body, safeName: sanitizeFilename(body.filename) };
}

const hasCode = (e: unknown, code: string): boolean =>
  typeof e === 'object' && e !== null && (e as { code?: unknown }).code === code;

// Signs with the caller's own JWT so the Storage insert policy decides; never the service role.
export async function createSignedUploadUrl(token: string, storagePath: string): Promise<string> {
  const base = process.env.SUPABASE_URL;
  const anonKey = process.env.SUPABASE_ANON_KEY;
  if (!base || !anonKey) throw new Error('SUPABASE_URL and SUPABASE_ANON_KEY must be set');
  let res: Response;
  try {
    res = await fetch(`${base}/storage/v1/object/upload/sign/households/${storagePath}`, {
      method: 'POST',
      headers: { authorization: `Bearer ${token}`, apikey: anonKey, 'content-type': 'application/json' },
      body: '{}',
    });
  } catch {
    throw new ApiError(502, 'storage_unavailable', 'Storage is unreachable');
  }
  if (!res.ok) throw new ApiError(502, 'storage_unavailable', `Storage refused the upload URL (${res.status})`);
  const { url } = (await res.json()) as { url?: unknown };
  if (typeof url !== 'string') throw new ApiError(502, 'storage_unavailable', 'Storage returned no upload URL');
  return `${base}/storage/v1${url}`;
}

// Runs inside withUser: a throw (including a Storage failure) rolls back the attachment row.
export async function signAttachment(
  client: Queryable,
  sub: string,
  token: string,
  input: ReturnType<typeof validateSignRequest>,
): Promise<SignAttachmentResponse> {
  const { table, module } = ENTITIES[input.entity];

  // The entity tables arrive with their modules; until then there is nothing to attach to.
  const reg = await client.query('select to_regclass($1) as reg', [`public.${table}`]);
  if (!reg.rows[0]?.reg) throw new ApiError(400, 'entity_unavailable', `${input.entity} is not available yet`);

  // Not-found and foreign-household both give 403 so the response doesn't reveal the entity.
  const entity = await client.query(`select household_id from ${table} where id = $1 and deleted_at is null`, [
    input.entity_id,
  ]);
  if (entity.rows[0]?.household_id !== input.household_id) {
    throw new ApiError(403, 'forbidden', 'Not allowed to attach to this entity');
  }

  const flag = await client.query(`select module_${module} as enabled from household where id = $1`, [
    input.household_id,
  ]);
  if (!flag.rows[0]) throw new ApiError(403, 'forbidden', 'Not allowed to attach to this entity');
  if (!flag.rows[0].enabled) throw new ApiError(403, 'module_disabled', `The ${module} module is off`);

  const id = crypto.randomUUID();
  const storagePath = `households/${input.household_id}/${input.entity}/${input.entity_id}/${id}-${input.safeName}`;
  try {
    await client.query(
      `insert into attachment (id, household_id, entity, entity_id, storage_path, mime, size_bytes, created_by)
       values ($1, $2, $3, $4, $5, $6, $7, $8)`,
      [id, input.household_id, input.entity, input.entity_id, storagePath, input.mime, input.size, sub],
    );
  } catch (e) {
    if (hasCode(e, '42501')) throw new ApiError(403, 'forbidden', 'Not allowed to attach to this entity');
    throw e;
  }
  return { attachment_id: id, upload_url: await createSignedUploadUrl(token, storagePath), storage_path: storagePath };
}
