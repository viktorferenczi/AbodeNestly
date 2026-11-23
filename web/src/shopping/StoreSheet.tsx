import { useState, type FormEvent } from 'react';
import type { Store } from '@abodenestly/contract';
import { Sheet } from '../ui/Sheet.tsx';
import { validateStoreName } from './Stores.ts';
import styles from './Stores.module.css';

// DESIGN C4 sheet: one Name field.
export function StoreSheet({ stores, onSave, onClose }: { stores: Store[]; onSave: (name: string) => Promise<void>; onClose: () => void }) {
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);
  const [touched, setTouched] = useState(false);
  const problem = validateStoreName(name, stores);

  async function submit(e?: FormEvent) {
    e?.preventDefault();
    setTouched(true);
    if (problem || busy) return;
    setBusy(true);
    setFailed(false);
    try {
      await onSave(name.trim());
    } catch {
      setFailed(true);
    } finally {
      setBusy(false);
    }
  }

  const message = problem === 'empty' ? 'Give the store a name' : problem === 'duplicate' ? `You already have ${name.trim()}` : null;

  return (
    <Sheet
      title="New store"
      onClose={onClose}
      footer={
        <button type="button" className={styles.primary} disabled={busy} onClick={() => void submit()}>
          Save
        </button>
      }
    >
      <form className={styles.form} onSubmit={(e) => void submit(e)}>
        <label className={styles.field}>
          Name
          <input value={name} onChange={(e) => setName(e.target.value)} autoComplete="off" aria-invalid={touched && problem != null} />
        </label>
        {touched && message ? <p role="alert">{message}</p> : null}
        {failed ? <p role="alert">Could not save the store. Try again.</p> : null}
      </form>
    </Sheet>
  );
}
