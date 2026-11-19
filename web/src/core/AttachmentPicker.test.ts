import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import Dexie from 'dexie';
import { FakeApiClient } from './api/FakeApiClient.ts';
import type { ApiResult, UploadFn } from './api/apiClient.ts';
import { openHouseholdDb, type HouseholdDb } from '../sync/db.ts';
import { drainAttachmentOutbox, isAllowedFile, pickAttachment, type AttachmentDeps } from './AttachmentPicker.ts';

let db: HouseholdDb;
let api: FakeApiClient;
let puts: Array<{ url: string; mime: string }>;
let putResult: ApiResult<null>;
let online = true;
let n = 0;

const upload: UploadFn = (url, _blob, mime) => {
  puts.push({ url, mime });
  return Promise.resolve(putResult);
};
const deps = (): AttachmentDeps => ({ db, api, upload, online: () => online, userId: 'u1', newId: () => `q${++n}` });
const photo = () => new File(['x'], 'milk.jpg', { type: 'image/jpeg' });
const input = (file = photo()) => ({ householdId: 'h1', entity: 'pantry_item' as const, entityId: 'e1', file });

beforeEach(async () => {
  await Dexie.delete('abodenestly-h1');
  db = await openHouseholdDb('h1');
  api = new FakeApiClient();
  puts = [];
  putResult = { ok: true, value: null };
  online = true;
  n = 0;
});
afterEach(() => db.close());

describe('CORE-11: isAllowedFile', () => {
  const MB = 1024 * 1024;
  it.each([
    ['image/jpeg', 1, true],
    ['image/png', 1, true],
    ['image/webp', 1, true],
    ['image/heic', 1, true],
    ['application/pdf', 25 * MB, true],
    ['application/pdf', 25 * MB + 1, false],
    ['image/gif', 1, false],
    ['text/plain', 1, false],
    ['image/jpeg', 0, false],
  ])('%s of %i bytes → %s', (type, size, expected) => {
    expect(isAllowedFile({ type, size })).toBe(expected);
  });
});

describe('CORE-11: pickAttachment', () => {
  it('CORE-11: AC4 offline writes an outbox row (bytes kept) and never calls sign', async () => {
    online = false;
    expect(await pickAttachment(deps(), input())).toEqual({ status: 'queued' });
    expect(api.signAttachmentCalls).toEqual([]);
    expect(puts).toEqual([]);
    const rows = await db.attachment_outbox.toArray();
    expect(rows).toHaveLength(1);
    expect(rows[0]).toMatchObject({ id: 'q1', household_id: 'h1', entity: 'pantry_item', entity_id: 'e1', filename: 'milk.jpg', mime: 'image/jpeg', size: 1 });
    expect(rows[0]!.bytes.byteLength).toBe(1);
    expect(await db.attachment.count()).toBe(0);
  });

  it('CORE-11: online signs, PUTs with the mime, and records the attachment', async () => {
    const outcome = await pickAttachment(deps(), input());
    expect(outcome.status).toBe('uploaded');
    expect(api.signAttachmentCalls).toEqual([{ household_id: 'h1', entity: 'pantry_item', entity_id: 'e1', filename: 'milk.jpg', mime: 'image/jpeg', size: 1 }]);
    expect(puts).toEqual([{ url: 'https://upload.test/a1', mime: 'image/jpeg' }]);
    expect(await db.attachment.get('a1')).toMatchObject({ entity: 'pantry_item', entity_id: 'e1', created_by: 'u1', mime: 'image/jpeg', size_bytes: 1, deleted_at: null });
    expect(await db.attachment_outbox.count()).toBe(0);
  });

  it('CORE-11: AC5 a sign failure is an error and records nothing locally', async () => {
    api.signAttachmentResult = { ok: false, error: { code: 'module_disabled' } };
    expect(await pickAttachment(deps(), input())).toEqual({ status: 'error', code: 'module_disabled' });
    expect(puts).toEqual([]);
    expect(await db.attachment.count()).toBe(0);
    expect(await db.attachment_outbox.count()).toBe(0);
  });

  it('CORE-11: a network failure while signing falls back to the queue', async () => {
    api.signAttachmentResult = { ok: false, error: { code: 'network' } };
    expect(await pickAttachment(deps(), input())).toEqual({ status: 'queued' });
    expect(await db.attachment_outbox.count()).toBe(1);
  });

  it('CORE-11: a failed PUT is an error, records nothing, and a retry signs again', async () => {
    putResult = { ok: false, error: { code: 'unknown' } };
    expect(await pickAttachment(deps(), input())).toEqual({ status: 'error', code: 'unknown' });
    expect(await db.attachment.count()).toBe(0);
    putResult = { ok: true, value: null };
    expect((await pickAttachment(deps(), input())).status).toBe('uploaded');
    expect(api.signAttachmentCalls).toHaveLength(2);
  });
});

describe('CORE-11: drainAttachmentOutbox', () => {
  it('CORE-11: uploads queued rows oldest first and deletes each on success', async () => {
    online = false;
    let tick = 0;
    const d = { ...deps(), now: () => new Date(Date.UTC(2026, 0, 1, 0, 0, tick++)) };
    await pickAttachment(d, input(new File(['a'], 'first.jpg', { type: 'image/jpeg' })));
    await pickAttachment(d, input(new File(['b'], 'second.pdf', { type: 'application/pdf' })));
    online = true;
    expect(await drainAttachmentOutbox(d)).toEqual({ status: 'done', uploaded: 2 });
    expect(api.signAttachmentCalls.map((c) => c.filename)).toEqual(['first.jpg', 'second.pdf']);
    expect(await db.attachment_outbox.count()).toBe(0);
  });

  it('CORE-11: a non-network failure leaves the row and stops', async () => {
    online = false;
    await pickAttachment(deps(), input());
    online = true;
    api.signAttachmentResult = { ok: false, error: { code: 'validation' } };
    expect(await drainAttachmentOutbox(deps())).toEqual({ status: 'error', code: 'validation' });
    expect(await db.attachment_outbox.count()).toBe(1);
  });

  it('CORE-11: a network failure leaves the row and reports offline', async () => {
    online = false;
    await pickAttachment(deps(), input());
    online = true;
    api.signAttachmentResult = { ok: false, error: { code: 'network' } };
    expect(await drainAttachmentOutbox(deps())).toEqual({ status: 'offline' });
    expect(await db.attachment_outbox.count()).toBe(1);
  });

  it('CORE-11: does not call sign while offline', async () => {
    online = false;
    await pickAttachment(deps(), input());
    expect(await drainAttachmentOutbox(deps())).toEqual({ status: 'offline' });
    expect(api.signAttachmentCalls).toEqual([]);
  });
});
