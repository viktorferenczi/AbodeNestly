import type { Attachment, AttachmentEntity } from '@abodenestly/contract';
import { useCallback, useEffect, useMemo, useRef, useState, type ChangeEvent } from 'react';
import { useOnline } from '../ui/useOnline.ts';
import { useLiveQuery } from '../ui/useLiveQuery.ts';
import { useApi } from './api/ApiProvider.tsx';
import { uploadToSignedUrl, type UploadFn } from './api/apiClient.ts';
import { useAuth } from './auth/AuthProvider.tsx';
import { useHousehold } from './household/HouseholdProvider.tsx';
import { drainAttachmentOutbox, isAllowedFile, pickAttachment, type AttachmentDeps, type PickOutcome } from './AttachmentPicker.ts';
import styles from './AttachmentPicker.module.css';

type Props = {
  householdId: string;
  entity: AttachmentEntity;
  entityId: string;
  /** The entity's module flag; off renders nothing (SPEC §9 X7). */
  enabled: boolean;
  /** Injectable for tests; the real PUT by default. */
  upload?: UploadFn;
};

type Retry = { kind: 'pick'; file: File } | { kind: 'drain' };

const isImage = (mime: string) => mime.startsWith('image/');

function Thumb({ attachment }: { attachment: Attachment }) {
  const { client } = useAuth();
  const [url, setUrl] = useState<string | null>(null);
  useEffect(() => {
    if (!isImage(attachment.mime)) return;
    let active = true;
    void client.attachmentUrl(attachment.storage_path).then((u) => active && setUrl(u));
    return () => {
      active = false;
    };
  }, [client, attachment.storage_path, attachment.mime]);
  const name = attachment.storage_path.split('/').pop() ?? attachment.storage_path;
  if (isImage(attachment.mime) && url) return <img src={url} alt="" className={styles.thumb} />;
  return <span className={styles.file}>{name}</span>;
}

function QueuedThumb({ bytes, filename, mime }: { bytes: ArrayBuffer; filename: string; mime: string }) {
  // The local preview of a pick that has not uploaded yet.
  const url = useMemo(() => (isImage(mime) && typeof URL.createObjectURL === 'function' ? URL.createObjectURL(new Blob([bytes], { type: mime })) : null), [bytes, mime]);
  useEffect(() => () => void (url && URL.revokeObjectURL?.(url)), [url]);
  return url ? <img src={url} alt="" className={styles.thumb} /> : <span className={styles.file}>{filename}</span>;
}

// CORE-11: the shared attachment control that pantry items, assets and log entries host.
export function AttachmentPicker({ householdId, entity, entityId, enabled, upload = uploadToSignedUrl }: Props) {
  const online = useOnline();
  const api = useApi();
  const { session } = useAuth();
  const { db } = useHousehold();
  const photoInput = useRef<HTMLInputElement>(null);
  const pdfInput = useRef<HTMLInputElement>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [retry, setRetry] = useState<Retry | null>(null);
  const draining = useRef(false);
  const onlineRef = useRef(online);
  onlineRef.current = online;
  const userId = session?.user.id ?? null;

  const attachments = useLiveQuery(
    () => (db ? db.attachment.where('[entity+entity_id]').equals([entity, entityId]).toArray() : []),
    [db, entity, entityId],
  );
  const queued = useLiveQuery(
    () => (db ? db.attachment_outbox.filter((r) => r.entity === entity && r.entity_id === entityId).toArray() : []),
    [db, entity, entityId],
  );

  const deps = useCallback(
    (): AttachmentDeps | null =>
      db ? { db, api, upload, online: () => onlineRef.current, userId, newId: () => crypto.randomUUID() } : null,
    [db, api, upload, userId],
  );

  const finish = useCallback((outcome: PickOutcome | { status: 'drained'; error?: string }, again: Retry) => {
    if (outcome.status === 'error') {
      setError(outcome.code === 'module_disabled' ? 'Attachments are turned off for this household.' : 'Could not attach the file.');
      setRetry(again);
    } else {
      setError(null);
      setRetry(null);
    }
  }, []);

  const drain = useCallback(async () => {
    const d = deps();
    if (!d || draining.current) return;
    draining.current = true;
    try {
      // Mount and reconnect always call this; an empty queue must not flash "uploading".
      if ((await d.db.attachment_outbox.count()) === 0) return;
      setBusy(true);
      const result = await drainAttachmentOutbox(d);
      if (result.status === 'error') finish({ status: 'error', code: result.code }, { kind: 'drain' });
      else finish({ status: 'drained' }, { kind: 'drain' });
    } finally {
      draining.current = false;
      setBusy(false);
    }
  }, [deps, finish]);

  // On mount and whenever the device comes back online (step 4).
  useEffect(() => {
    if (enabled && online && db) void drain();
  }, [enabled, online, db, drain]);

  const pick = useCallback(
    async (file: File) => {
      const d = deps();
      if (!d) return;
      setBusy(true);
      setError(null);
      try {
        finish(await pickAttachment(d, { householdId, entity, entityId, file }), { kind: 'pick', file });
      } finally {
        setBusy(false);
      }
    },
    [deps, householdId, entity, entityId, finish],
  );

  function onFile(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file) return;
    if (!isAllowedFile(file)) {
      setError('Choose a photo or a PDF up to 25 MB.');
      setRetry(null);
      return;
    }
    void pick(file);
  }

  if (!enabled) return null;

  const live = (attachments ?? []).filter((a) => a.deleted_at === null);
  const waiting = queued ?? [];
  const state = busy ? 'uploading' : error ? 'error' : waiting.length > 0 ? 'queued' : 'idle';

  return (
    <section className={styles.root} aria-label="Attachments" data-testid={`attachments.${state}`}>
      <div className={styles.actions}>
        <button type="button" className={styles.button} onClick={() => photoInput.current?.click()} disabled={busy}>
          Add photo
        </button>
        <button type="button" className={styles.button} onClick={() => pdfInput.current?.click()} disabled={busy}>
          Add PDF
        </button>
      </div>
      <input ref={photoInput} type="file" accept="image/*" hidden data-testid="attachments.photo" onChange={onFile} />
      <input ref={pdfInput} type="file" accept="application/pdf" hidden data-testid="attachments.pdf" onChange={onFile} />

      <p aria-live="polite" className={styles.note}>
        {state === 'uploading' ? 'Uploading…' : state === 'queued' ? "Will upload when you're online" : ''}
      </p>
      {state === 'error' ? (
        <div role="alert" className={styles.error}>
          <span>{error}</span>
          {retry ? (
            <button
              type="button"
              className={styles.button}
              data-testid="attachments.retry"
              onClick={() => (retry.kind === 'pick' ? void pick(retry.file) : void drain())}
            >
              Retry
            </button>
          ) : null}
        </div>
      ) : null}

      <ul className={styles.list}>
        {live.map((a) => (
          <li key={a.id} data-testid="attachments.item">
            <Thumb attachment={a} />
          </li>
        ))}
        {waiting.map((q) => (
          <li key={q.id} data-testid="attachments.queuedItem">
            <QueuedThumb bytes={q.bytes} filename={q.filename} mime={q.mime} />
          </li>
        ))}
      </ul>
    </section>
  );
}
