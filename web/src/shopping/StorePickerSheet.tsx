import { useState } from 'react';
import type { Store } from '@abodenestly/contract';
import { Link } from 'react-router';
import { Sheet } from '../ui/Sheet.tsx';
import { validateStoreName } from './Stores.ts';
import styles from './Stores.module.css';

// DESIGN C2 store picker: No store, the household's stores, an inline New store field, and a link to Stores.
export function StorePickerSheet({
  stores,
  current,
  onSave,
  onCreate,
  onClose,
}: {
  /** Live stores, sorted by name. */
  stores: Store[];
  current: string | null;
  onSave: (storeId: string | null) => Promise<void>;
  /** Creates the store and returns its id. */
  onCreate: (name: string) => Promise<string>;
  onClose: () => void;
}) {
  const [choice, setChoice] = useState<string | null>(current);
  const [name, setName] = useState('');
  const [problem, setProblem] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  // A store deleted elsewhere while the sheet is open drops out; the selection falls back to the list's own value.
  const selected = choice === null || stores.some((s) => s.id === choice) ? choice : current;

  async function add() {
    const bad = validateStoreName(name, stores);
    if (bad) return setProblem(bad === 'empty' ? 'Give the store a name' : `You already have ${name.trim()}`);
    setProblem(null);
    setBusy(true);
    try {
      setChoice(await onCreate(name.trim()));
      setName('');
    } catch {
      setProblem('Could not save the store. Try again.');
    } finally {
      setBusy(false);
    }
  }

  async function save() {
    setBusy(true);
    try {
      if (selected !== current) await onSave(selected);
      onClose();
    } catch {
      setProblem('Could not save the store. Try again.');
      setBusy(false);
    }
  }

  return (
    <Sheet
      title="Store"
      onClose={onClose}
      footer={
        <button type="button" className={styles.primary} disabled={busy} onClick={() => void save()}>
          Save
        </button>
      }
    >
      <div className={styles.form}>
        <fieldset className={styles.options} aria-label="Store">
          <label className={styles.option}>
            <input type="radio" name="store" checked={selected === null} onChange={() => setChoice(null)} />
            No store
          </label>
          {stores.map((s) => (
            <label key={s.id} className={styles.option}>
              <input type="radio" name="store" checked={selected === s.id} onChange={() => setChoice(s.id)} />
              {s.name}
            </label>
          ))}
        </fieldset>
        <div className={styles.inline}>
          <label className={styles.field}>
            New store
            <input
              value={name}
              onChange={(e) => setName(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') {
                  e.preventDefault();
                  void add();
                }
              }}
              autoComplete="off"
            />
          </label>
          <button type="button" className={styles.secondary} disabled={busy} onClick={() => void add()}>
            Add
          </button>
        </div>
        {problem ? <p role="alert">{problem}</p> : null}
        <Link to="/shopping/stores" className={styles.manage} onClick={onClose}>
          Manage stores
        </Link>
      </div>
    </Sheet>
  );
}
