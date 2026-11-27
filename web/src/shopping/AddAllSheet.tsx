import { useState } from 'react';
import type { ShoppingList } from '@abodenestly/contract';
import { Sheet } from '../ui/Sheet.tsx';
import styles from './Stores.module.css';

// SHOP-11: "Add all to…" picks one active, non-template list to receive a template's items.
export function AddAllSheet({ lists, onPick, onClose }: { lists: ShoppingList[]; onPick: (list: ShoppingList) => Promise<void>; onClose: () => void }) {
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);

  async function pick(list: ShoppingList) {
    if (busy) return;
    setBusy(true);
    setFailed(false);
    try {
      await onPick(list);
    } catch {
      setFailed(true);
      setBusy(false);
    }
  }

  return (
    <Sheet title="Add all to…" onClose={onClose}>
      <div className={styles.form}>
        {lists.length === 0 ? (
          <p>Make a list first</p>
        ) : (
          <ul aria-label="Lists" style={{ margin: 0, padding: 0, listStyle: 'none' }}>
            {lists.map((l) => (
              <li key={l.id}>
                <button type="button" className={styles.secondary} style={{ width: '100%', textAlign: 'left' }} disabled={busy} onClick={() => void pick(l)}>
                  {l.name}
                </button>
              </li>
            ))}
          </ul>
        )}
        {failed ? <p role="alert">Could not add the items. Try again.</p> : null}
      </div>
    </Sheet>
  );
}
