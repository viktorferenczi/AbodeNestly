import type { Product } from '@abodenestly/contract';
import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { useApi } from '../core/api/ApiProvider.tsx';
import type { HouseholdDb } from '../sync/db.ts';
import type { MutateDeps } from '../sync/outbox.ts';
import { Sheet } from '../ui/Sheet.tsx';
import { addNamed, addOffline, addProduct, cachedName, CODE_ERROR, isValidCode, lookup } from './Barcode.ts';
import styles from './BarcodeScanner.module.css';
import { browserScannerDevice, type ScannerDevice, type ScanSession } from './ScannerDevice.ts';

type Phase =
  | { kind: 'scanning' }
  | { kind: 'looking'; code: string }
  | { kind: 'found'; product: Product }
  | { kind: 'not-found'; code: string }
  | { kind: 'queued'; code: string }
  | { kind: 'permission-denied' }
  | { kind: 'unsupported' };

type Props = {
  db: HouseholdDb;
  deps: Partial<MutateDeps>;
  householdId: string;
  listId: string;
  userId: string | null;
  onClose: () => void;
  /** Tests pass a fake; the app uses the browser camera. */
  device?: ScannerDevice;
};

// DESIGN C7: states scanning, found, not-found, permission-denied and offline (here `queued`), plus `unsupported`
// for browsers without BarcodeDetector (same layout as permission-denied, manual entry only).
export function BarcodeScanner({ db, deps, householdId, listId, userId, onClose, device = browserScannerDevice }: Props) {
  const api = useApi();
  const [phase, setPhase] = useState<Phase>(() => (device.supported() ? { kind: 'scanning' } : { kind: 'unsupported' }));
  const [session, setSession] = useState<ScanSession | null>(null);
  const [torchOn, setTorchOn] = useState(false);
  const [manual, setManual] = useState(false);
  const [code, setCode] = useState('');
  const [codeError, setCodeError] = useState(false);
  const [name, setName] = useState('');
  const [failed, setFailed] = useState(false);
  const video = useRef<HTMLVideoElement>(null);
  const handled = useRef(false);
  const input = { householdId, listId, userId };
  // Sheet focuses its own panel in an effect that runs after its children's, so focus waits one microtask.
  const focusRef = useCallback((el: HTMLInputElement | null) => {
    if (el) queueMicrotask(() => el.focus());
  }, []);

  const scanning = phase.kind === 'scanning';

  const handle = async (raw: string) => {
    if (handled.current) return;
    handled.current = true;
    setPhase({ kind: 'looking', code: raw });
    setFailed(false);
    try {
      const result = await lookup(api, raw, navigator.onLine);
      if (result.kind === 'found') setPhase({ kind: 'found', product: result.product });
      else if (result.kind === 'not-found') {
        setName(cachedName(raw) ?? '');
        setPhase({ kind: 'not-found', code: raw });
      } else {
        await addOffline(db, deps, input, raw);
        setPhase({ kind: 'queued', code: raw });
      }
    } catch {
      setFailed(true);
      handled.current = false;
      setPhase({ kind: 'scanning' });
    }
  };

  // The camera runs only while scanning; leaving the state (a code was read, Close) stops it.
  useEffect(() => {
    if (!scanning || !device.supported()) return;
    let cancelled = false;
    let started: ScanSession | null = null;
    handled.current = false;
    setTorchOn(false);
    device
      .start(video.current!, (raw) => {
        // Unreadable or partial reads are ignored; the next frame tries again.
        if (!cancelled && isValidCode(raw)) void handle(raw);
      })
      .then((s) => {
        if (cancelled) return s.stop();
        started = s;
        setSession(s);
      })
      .catch((e: unknown) => {
        if (cancelled) return;
        const denied = e instanceof Error && (e.name === 'NotAllowedError' || e.name === 'SecurityError');
        setPhase({ kind: denied ? 'permission-denied' : 'unsupported' });
      });
    return () => {
      cancelled = true;
      started?.stop();
      setSession(null);
    };
    // handle closes over props that do not change while the scanner is open.
  }, [scanning, device]);

  const toggleTorch = async () => {
    const next = !torchOn;
    try {
      await session?.setTorch(next);
      setTorchOn(next);
    } catch {
      setTorchOn(false);
    }
  };

  const submitCode = (e: FormEvent) => {
    e.preventDefault();
    const trimmed = code.trim();
    if (!isValidCode(trimmed)) return setCodeError(true);
    setCodeError(false);
    void handle(trimmed);
  };

  const run = async (write: () => Promise<unknown>, then: () => void) => {
    try {
      await write();
      then();
    } catch {
      setFailed(true);
    }
  };

  const scanAgain = () => {
    handled.current = false;
    setFailed(false);
    setCode('');
    setPhase({ kind: device.supported() ? 'scanning' : 'unsupported' });
  };

  const manualForm = (
    <form onSubmit={submitCode} className={styles.manual}>
      <label className={styles.label}>
        Barcode
        <input
          className={styles.field}
          inputMode="numeric"
          autoComplete="off"
          // The field is the whole point of the unsupported and permission-denied layouts, and appears on request while scanning.
          ref={focusRef}
          value={code}
          aria-invalid={codeError}
          aria-describedby={codeError ? 'barcode-error' : undefined}
          onChange={(e) => {
            setCode(e.target.value);
            setCodeError(false);
          }}
        />
      </label>
      {codeError ? (
        <p id="barcode-error" role="alert" className={styles.error}>
          {CODE_ERROR}
        </p>
      ) : null}
      <button type="submit" className={styles.primary}>
        Look up
      </button>
    </form>
  );

  let body;
  switch (phase.kind) {
    case 'scanning':
      body = (
        <>
          <div className={styles.preview}>
            <video ref={video} className={styles.video} muted playsInline data-testid="scanner-video" />
            <div className={styles.guide} data-testid="guide-box" aria-hidden="true" />
          </div>
          <p className={styles.hint}>Point the camera at a barcode</p>
          <div className={styles.actions}>
            {session?.torch ? (
              <button type="button" className={styles.secondary} aria-pressed={torchOn} onClick={() => void toggleTorch()}>
                Torch
              </button>
            ) : null}
            {!manual ? (
              <button type="button" className={styles.secondary} onClick={() => setManual(true)}>
                Enter code manually
              </button>
            ) : null}
          </div>
          {manual ? manualForm : null}
        </>
      );
      break;
    case 'looking':
      body = (
        <p role="status" className={styles.hint}>
          Looking up {phase.code}…
        </p>
      );
      break;
    case 'found':
      body = (
        <div className={styles.card}>
          {phase.product.image_url ? <img className={styles.image} src={phase.product.image_url} alt={phase.product.name} /> : null}
          <h3 className={styles.name}>{phase.product.name}</h3>
          {phase.product.brand ? <p className={styles.brand}>{phase.product.brand}</p> : null}
          <div className={styles.actions}>
            <button type="button" className={styles.secondary} onClick={scanAgain}>
              Scan again
            </button>
            <button type="button" className={styles.primary} onClick={() => void run(() => addProduct(db, deps, input, (phase as { product: Product }).product), onClose)}>
              Add
            </button>
          </div>
        </div>
      );
      break;
    case 'not-found': {
      const notFoundCode = phase.code;
      const typed = name.trim();
      body = (
        <form
          className={styles.card}
          onSubmit={(e) => {
            e.preventDefault();
            if (typed) void run(() => addNamed(db, deps, input, notFoundCode, typed), onClose);
          }}
        >
          <h3 className={styles.name}>We don't know this product</h3>
          <p className={styles.brand}>{notFoundCode}</p>
          <label className={styles.label}>
            Name
            <input className={styles.field} ref={focusRef} autoComplete="off" value={name} onChange={(e) => setName(e.target.value)} />
          </label>
          <div className={styles.actions}>
            <button type="button" className={styles.secondary} onClick={scanAgain}>
              Scan again
            </button>
            <button type="submit" className={styles.primary} disabled={!typed}>
              Add
            </button>
          </div>
        </form>
      );
      break;
    }
    case 'queued':
      body = (
        <div className={styles.card}>
          <p role="status">Added {phase.code}. We'll look it up when you're back online</p>
          <div className={styles.actions}>
            <button type="button" className={styles.secondary} onClick={scanAgain}>
              Scan another
            </button>
            <button type="button" className={styles.primary} onClick={onClose}>
              Done
            </button>
          </div>
        </div>
      );
      break;
    case 'permission-denied':
    case 'unsupported':
      body = (
        <>
          <h3 className={styles.name}>{phase.kind === 'permission-denied' ? 'Camera access is off' : "Scanning isn't available in this browser"}</h3>
          <p className={styles.hint}>
            {phase.kind === 'permission-denied'
              ? "Allow the camera for this site in your browser's settings, then open the scanner again. You can still enter the code."
              : 'You can still enter the code printed under the barcode.'}
          </p>
          {manualForm}
        </>
      );
  }

  return (
    <Sheet title="Scan barcode" onClose={onClose} fullScreen>
      <div className={styles.body}>
        {failed ? (
          <p role="alert" className={styles.error}>
            Couldn't add that item. Try again.
          </p>
        ) : null}
        {body}
      </div>
    </Sheet>
  );
}
