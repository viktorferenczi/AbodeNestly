import { useEffect, useState } from 'react';
import type { HouseholdDb } from '../sync/db.ts';
import type { MutateDeps } from '../sync/outbox.ts';
import { Sheet } from '../ui/Sheet.tsx';
import { formatQty } from './ItemSheet.ts';
import { addItem } from './QuickAdd.ts';
import { parseSpoken, type SpokenItem } from './VoiceAdd.ts';
import { browserVoiceRecognizer, type VoiceRecognizer } from './VoiceRecognizer.ts';
import styles from './VoiceAdd.module.css';

type Phase =
  | { kind: 'listening' }
  | { kind: 'parsed'; item: SpokenItem }
  | { kind: 'no-item' }
  | { kind: 'not-heard' }
  | { kind: 'permission-denied' }
  | { kind: 'unsupported' };

type Props = {
  db: HouseholdDb;
  deps: Partial<MutateDeps>;
  householdId: string;
  listId: string;
  userId: string | null;
  onClose: () => void;
  /** Closes the sheet and puts the cursor in the Quick add field. */
  onTypeInstead: () => void;
  /** Tests pass a fake; the app uses the browser's speech recognition. */
  recognizer?: VoiceRecognizer;
};

// DESIGN C6: states listening, parsed, permission-denied and unsupported, plus "Didn't catch that" for recognizer
// errors. The sheet never adds on its own: the person taps Add.
export function VoiceAdd({ db, deps, householdId, listId, userId, onClose, onTypeInstead, recognizer = browserVoiceRecognizer }: Props) {
  const [phase, setPhase] = useState<Phase>({ kind: 'listening' });
  const [transcript, setTranscript] = useState('');
  const [attempt, setAttempt] = useState(0);
  const [failed, setFailed] = useState(false);

  // One recognition per attempt; leaving (close, unmount, Try again) stops it.
  useEffect(() => {
    let cancelled = false;
    let settled = false;
    let stop: (() => void) | null = null;
    const settle = (next: Phase) => {
      if (cancelled || settled) return;
      settled = true;
      setPhase(next);
    };
    const offline = !navigator.onLine;
    if (!recognizer.supported() || (offline && !recognizer.canProcessLocally())) {
      setPhase({ kind: 'unsupported' });
      return;
    }
    void recognizer.permission().then((state) => {
      if (cancelled) return;
      if (state === 'denied') return settle({ kind: 'permission-denied' });
      stop = recognizer.start(
        {
          onTranscript: (text, final) => {
            if (cancelled || settled) return;
            setTranscript(text);
            if (final) {
              const item = parseSpoken(text, navigator.language);
              settle(item ? { kind: 'parsed', item } : { kind: 'no-item' });
            }
          },
          onError: (error) => settle(error === 'not-allowed' ? { kind: 'permission-denied' } : { kind: 'not-heard' }),
          onEnd: () => settle({ kind: 'not-heard' }),
        },
        { offline },
      );
    });
    return () => {
      cancelled = true;
      stop?.();
    };
  }, [recognizer, attempt]);

  const tryAgain = () => {
    setTranscript('');
    setFailed(false);
    setPhase({ kind: 'listening' });
    setAttempt((a) => a + 1);
  };

  const add = async (item: SpokenItem) => {
    try {
      await addItem(db, deps, { householdId, listId, userId, name: item.name, quantity: item.quantity, unit: item.unit });
      onClose();
    } catch {
      setFailed(true);
    }
  };

  const typeInstead = (
    <button type="button" className={styles.primary} onClick={onTypeInstead}>
      Type instead
    </button>
  );
  const retry = (
    <button type="button" className={styles.primary} onClick={tryAgain}>
      Try again
    </button>
  );

  let body;
  switch (phase.kind) {
    case 'listening':
      body = (
        <>
          <div className={styles.indicator} aria-hidden="true" data-testid="listening-indicator" />
          <h3 className={styles.heading}>Listening…</h3>
          <p className={styles.transcript} aria-live="polite">
            {transcript}
          </p>
          <div className={styles.actions}>
            <button type="button" className={styles.secondary} onClick={onClose}>
              Cancel
            </button>
          </div>
        </>
      );
      break;
    case 'parsed': {
      const item = phase.item;
      body = (
        <>
          <p className={styles.transcript}>{transcript}</p>
          <ul className={styles.chips} aria-label="Parsed item">
            {item.quantity !== null ? <li className={styles.chip}>{formatQty(item.quantity, null)}</li> : null}
            {item.unit ? <li className={styles.chip}>{item.unit}</li> : null}
            <li className={styles.chip}>{item.name}</li>
          </ul>
          <div className={styles.actions}>
            <button type="button" className={styles.secondary} onClick={tryAgain}>
              Try again
            </button>
            <button type="button" className={styles.primary} onClick={() => void add(item)}>
              Add
            </button>
          </div>
        </>
      );
      break;
    }
    case 'no-item':
      body = (
        <>
          <p className={styles.transcript}>{transcript}</p>
          <p className={styles.hint}>Didn't catch an item. Try again.</p>
          <div className={styles.actions}>{retry}</div>
        </>
      );
      break;
    case 'not-heard':
      body = (
        <>
          <p className={styles.hint}>Didn't catch that.</p>
          <div className={styles.actions}>{retry}</div>
        </>
      );
      break;
    case 'permission-denied':
      body = (
        <>
          <h3 className={styles.heading}>Microphone is off</h3>
          <p className={styles.hint}>Microphone access is off. Allow it in your browser settings, or type the item instead.</p>
          <div className={styles.actions}>{typeInstead}</div>
        </>
      );
      break;
    case 'unsupported':
      body = (
        <>
          <h3 className={styles.heading}>Voice isn't available</h3>
          <p className={styles.hint}>Voice input isn't available here. Type the item instead.</p>
          <div className={styles.actions}>{typeInstead}</div>
        </>
      );
  }

  return (
    <Sheet title="Voice add" onClose={onClose}>
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
