import { useState, type FormEvent } from 'react';
import type { Store } from '@abodenestly/contract';
import { Sheet } from '../ui/Sheet.tsx';
import styles from './Lists.module.css';

// DESIGN C1 sheet: name and store. The Template switch waits for SHOP-11.
export function NewListSheet({ stores, onSave, onClose }: { stores: Store[]; onSave: (name: string, storeId: string | null) => Promise<void>; onClose: () => void }) {
  const [name, setName] = useState('');
  const [storeId, setStoreId] = useState('');
  const [busy, setBusy] = useState(false);
  const valid = name.trim().length > 0;

  async function submit(e?: FormEvent) {
    e?.preventDefault();
    if (!valid || busy) return;
    setBusy(true);
    await onSave(name.trim(), storeId || null);
  }

  return (
    <Sheet
      title="New list"
      onClose={onClose}
      footer={
        <button type="button" className={styles.primary} disabled={!valid || busy} onClick={() => void submit()}>
          Save
        </button>
      }
    >
      <form className={styles.form} onSubmit={(e) => void submit(e)}>
        <label className={styles.field}>
          Name
          <input value={name} onChange={(e) => setName(e.target.value)} autoComplete="off" />
        </label>
        {stores.length > 0 ? (
          <label className={styles.field}>
            Store
            <select value={storeId} onChange={(e) => setStoreId(e.target.value)}>
              <option value="">No store</option>
              {stores.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name}
                </option>
              ))}
            </select>
          </label>
        ) : null}
      </form>
    </Sheet>
  );
}
