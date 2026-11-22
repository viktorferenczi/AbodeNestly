import type { ListItem } from '@abodenestly/contract';
import { useMemo, useRef, useState } from 'react';
import { useAuth } from '../core/auth/AuthProvider.tsx';
import { useSync } from '../sync/SyncEngine.ts';
import type { HouseholdDb } from '../sync/db.ts';
import { QuickAddBar } from '../ui/QuickAddBar.tsx';
import { addItem, historyOf, suggest } from './QuickAdd.ts';
import { createItem, unitSuggestions } from './ItemSheet.ts';
import { ItemSheet } from './ItemSheet.tsx';
import styles from './QuickAdd.module.css';

type Props = { db: HouseholdDb; householdId: string; listId: string; items: ListItem[]; autoFocus: boolean };

/** Wires the QuickAddBar to one list: suggestions from the household's item history, writes through the outbox. */
export function QuickAdd({ db, householdId, listId, items, autoFocus }: Props) {
  const { session } = useAuth();
  const sync = useSync();
  const syncRef = useRef(sync);
  syncRef.current = sync;
  const deps = useMemo(() => ({ onQueued: () => void syncRef.current.kick() }), []);
  const [value, setValue] = useState('');
  const [failed, setFailed] = useState(false);
  // The Details button opens the item sheet prefilled with the typed text (SHOP-03).
  const [details, setDetails] = useState<string | null>(null);

  const history = useMemo(() => historyOf(items), [items]);
  const suggestions = useMemo(() => suggest(history, value, { listId }), [history, value, listId]);

  // The field clears only once the row and its outbox entry are committed; on failure the text stays for a retry.
  const add = async (name: string) => {
    const trimmed = name.trim();
    if (!trimmed) return;
    try {
      await addItem(db, deps, { householdId, listId, name: trimmed, userId: session?.user.id ?? null });
      setValue('');
      setFailed(false);
    } catch {
      setFailed(true);
    }
  };

  return (
    <>
      {failed ? (
        <p role="alert" className={styles.failed}>
          Couldn't add that item. Try again.
        </p>
      ) : null}
      <QuickAddBar value={value} onChange={setValue} suggestions={suggestions} onPick={(l) => void add(l)} onSubmit={(t) => void add(t)} autoFocus={autoFocus} onDetails={setDetails} />
      {details !== null ? (
        <ItemSheet
          mode="new"
          item={null}
          initialName={details}
          units={unitSuggestions(items)}
          onClose={() => setDetails(null)}
          onSave={async (fields) => {
            await createItem(db, deps, { householdId, listId, userId: session?.user.id ?? null, fields });
            setValue('');
            setDetails(null);
          }}
        />
      ) : null}
    </>
  );
}
