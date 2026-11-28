import { useEffect, useRef, useState } from 'react';
import { useApi } from '../core/api/ApiProvider.tsx';
import { Sheet } from '../ui/Sheet.tsx';
import { useOnline } from '../ui/useOnline.ts';
import { shareLink, tokenFromUrl } from './ShareSheet.ts';
import styles from './ShareSheet.module.css';

const COPIED_MS = 3000;

// DESIGN C8: switch "Share with a link", the link with Copy, the hint, and Stop sharing. Online only.
// `token` is the list's local share_token; a POST or DELETE made in this sheet wins over it until the sheet closes,
// because the local row only catches up after the next pull.
export function ShareSheet({ listId, token, onChanged, onClose }: { listId: string; token: string | null; onChanged: (token: string | null) => void; onClose: () => void }) {
  const api = useApi();
  const online = useOnline();
  const [override, setOverride] = useState<{ token: string | null } | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const copiedTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const input = useRef<HTMLInputElement>(null);
  useEffect(() => () => clearTimeout(copiedTimer.current), []);

  const current = override ? override.token : token;
  const on = current != null;
  const link = current != null ? shareLink(window.location.origin, current) : null;
  const disabled = busy || !online;

  async function turnOn() {
    setBusy(true);
    setError(null);
    const result = await api.shareList(listId);
    setBusy(false);
    // The link is built from the token, not the returned URL, which points at the API's JSON route.
    const minted = result.ok ? tokenFromUrl(result.value.url) : null;
    if (minted) {
      setOverride({ token: minted });
      onChanged(minted);
    } else {
      setError('Could not turn on sharing. Try again.');
    }
  }

  async function turnOff() {
    setBusy(true);
    setError(null);
    const result = await api.stopSharing(listId);
    setBusy(false);
    if (result.ok) {
      setOverride({ token: null });
      setCopied(false);
      onChanged(null);
    } else {
      setError('Could not stop sharing. Try again.');
    }
  }

  async function copy() {
    if (!link) return;
    try {
      await navigator.clipboard.writeText(link);
      setCopied(true);
      clearTimeout(copiedTimer.current);
      copiedTimer.current = setTimeout(() => setCopied(false), COPIED_MS);
    } catch {
      // No clipboard (insecure origin, denied): select the text so the person can copy it by hand.
      input.current?.focus();
      input.current?.select();
    }
  }

  return (
    <Sheet title="Share list" onClose={onClose}>
      <div className={styles.body}>
        <label className={styles.switchRow}>
          <input type="checkbox" role="switch" checked={on} disabled={disabled} onChange={(e) => void (e.target.checked ? turnOn() : turnOff())} />
          Share with a link
        </label>
        {!online ? <p className={styles.hint}>Sharing needs a connection</p> : null}
        {error ? <p role="alert">{error}</p> : null}
        {link ? (
          <>
            <div className={styles.link}>
              <input ref={input} readOnly aria-label="Share link" value={link} onFocus={(e) => e.currentTarget.select()} />
              <button type="button" className={styles.button} onClick={() => void copy()}>
                Copy link
              </button>
            </div>
            {copied ? (
              <p role="status" className={styles.hint}>
                Link copied
              </p>
            ) : null}
            <p className={styles.hint}>Anyone with the link can view unchecked items</p>
            <button type="button" className={styles.button} disabled={disabled} onClick={() => void turnOff()}>
              Stop sharing
            </button>
            <p className={styles.hint}>The current link will stop working</p>
          </>
        ) : null}
      </div>
    </Sheet>
  );
}
