import type { Attachment, AttachmentEntity } from '@abodenestly/contract';
import type { ApiClient, ApiFailure, UploadFn } from './api/apiClient.ts';
import type { AttachmentOutboxRow, HouseholdDb } from '../sync/db.ts';

// Mirrors the API's limits (POST /attachments/sign); the server stays the authority.
export const ALLOWED_MIMES = ['image/jpeg', 'image/png', 'image/webp', 'image/heic', 'application/pdf'];
export const MAX_ATTACHMENT_BYTES = 25 * 1024 * 1024;

export function isAllowedFile(file: { type: string; size: number }): boolean {
  return ALLOWED_MIMES.includes(file.type.toLowerCase()) && file.size > 0 && file.size <= MAX_ATTACHMENT_BYTES;
}

export type AttachmentDeps = {
  db: HouseholdDb;
  api: Pick<ApiClient, 'signAttachment'>;
  upload: UploadFn;
  online: () => boolean;
  userId: string | null;
  newId: () => string;
  now?: () => Date;
};

export type PickInput = {
  householdId: string;
  entity: AttachmentEntity;
  entityId: string;
  file: File;
};

export type PickOutcome =
  | { status: 'uploaded'; attachment: Attachment }
  | { status: 'queued' }
  | { status: 'error'; code: ApiFailure['code'] };

type Upload = Pick<AttachmentOutboxRow, 'household_id' | 'entity' | 'entity_id' | 'filename' | 'mime' | 'size' | 'bytes'>;

// Older WebKit and jsdom lack Blob.arrayBuffer().
function readBytes(file: Blob): Promise<ArrayBuffer> {
  if (typeof file.arrayBuffer === 'function') return file.arrayBuffer();
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result as ArrayBuffer);
    reader.onerror = () => reject(reader.error);
    reader.readAsArrayBuffer(file);
  });
}

const nowIso = (deps: AttachmentDeps) => (deps.now?.() ?? new Date()).toISOString();

async function enqueue(deps: AttachmentDeps, upload: Upload): Promise<void> {
  await deps.db.attachment_outbox.put({ id: deps.newId(), ...upload, created_at: nowIso(deps) });
}

/** Steps 2 and 3 of the pick flow: sign, PUT, record. Nothing is written locally unless both succeed. */
async function signAndUpload(deps: AttachmentDeps, upload: Upload): Promise<PickOutcome | { status: 'network' }> {
  const signed = await deps.api.signAttachment({
    household_id: upload.household_id,
    entity: upload.entity,
    entity_id: upload.entity_id,
    filename: upload.filename,
    mime: upload.mime,
    size: upload.size,
  });
  if (!signed.ok) return signed.error.code === 'network' ? { status: 'network' } : { status: 'error', code: signed.error.code };
  const put = await deps.upload(signed.value.upload_url, new Blob([upload.bytes], { type: upload.mime }), upload.mime);
  // A PUT that cannot reach Storage is as offline as a sign that cannot reach the API.
  if (!put.ok) return put.error.code === 'network' ? { status: 'network' } : { status: 'error', code: put.error.code };
  const stamp = nowIso(deps);
  const attachment: Attachment = {
    id: signed.value.attachment_id,
    household_id: upload.household_id,
    entity: upload.entity,
    entity_id: upload.entity_id,
    storage_path: signed.value.storage_path,
    mime: upload.mime,
    size_bytes: upload.size,
    created_by: deps.userId,
    created_at: stamp,
    updated_at: stamp,
    deleted_at: null,
    sync_seq: 0,
  };
  await deps.db.attachment.put(attachment);
  return { status: 'uploaded', attachment };
}

/** Offline picks go to the queue without calling sign (AC4); a network failure falls back to it too. */
export async function pickAttachment(deps: AttachmentDeps, input: PickInput): Promise<PickOutcome> {
  const upload: Upload = {
    household_id: input.householdId,
    entity: input.entity,
    entity_id: input.entityId,
    filename: input.file.name,
    mime: input.file.type,
    size: input.file.size,
    bytes: await readBytes(input.file),
  };
  if (!deps.online()) {
    await enqueue(deps, upload);
    return { status: 'queued' };
  }
  const result = await signAndUpload(deps, upload);
  if (result.status !== 'network') return result;
  await enqueue(deps, upload);
  return { status: 'queued' };
}

export type DrainOutcome = { status: 'done'; uploaded: number } | { status: 'offline' } | { status: 'error'; code: ApiFailure['code'] };

/** Step 4: queued picks in creation order. A row leaves the queue only after it was uploaded and recorded. */
export async function drainAttachmentOutbox(deps: AttachmentDeps): Promise<DrainOutcome> {
  let uploaded = 0;
  let firstError: DrainOutcome | null = null;
  for (const next of await deps.db.attachment_outbox.orderBy('created_at').toArray()) {
    if (!deps.online()) return { status: 'offline' };
    const result = await signAndUpload(deps, next);
    if (result.status === 'network') return { status: 'offline' };
    if (result.status === 'error') {
      // A permanent failure (module off, entity gone) must not hold up the picks behind it: its row stays for
      // Retry, the rest of the queue goes on, and the error is reported when the pass ends.
      firstError ??= { status: 'error', code: result.code };
      continue;
    }
    await deps.db.attachment_outbox.delete(next.id);
    uploaded += 1;
  }
  return firstError ?? { status: 'done', uploaded };
}
