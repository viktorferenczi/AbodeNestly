import { useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router';
import { useHousehold } from '../core/household/HouseholdProvider.tsx';
import { useSync } from '../sync/SyncEngine.ts';
import { EmptyState } from '../ui/EmptyState.tsx';
import { ListRow } from '../ui/ListRow.tsx';
import { UndoToast } from '../ui/UndoToast.tsx';
import { useLiveQuery } from '../ui/useLiveQuery.ts';
import { UNDO_MS } from './Lists.ts';
import { StoreSheet } from './StoreSheet.tsx';
import { createStore, deleteStore, learnedLabel, storeRows } from './Stores.ts';
import styles from './Stores.module.css';

type Pending = { id: string; name: string };

// DESIGN C4. Reads only the local database; every write goes through the outbox, so it all works offline.
export function Stores() {
  const { status, db, household, modules } = useHousehold();
  const sync = useSync();
  const householdId = household?.id;
  const [sheet, setSheet] = useState(false);
  const [pending, setPending] = useState<Pending[]>([]);
  const timers = useRef(new Map<string, { entry: Pending; timer: ReturnType<typeof setTimeout> }>());
  const syncRef = useRef(sync);
  syncRef.current = sync;

  const data = useLiveQuery(async () => {
    if (!db || !householdId) return null;
    const [stores, orders] = await Promise.all([
      db.store.where('household_id').equals(householdId).toArray(),
      db.store_item_order.where('household_id').equals(householdId).toArray(),
    ]);
    return { stores, orders };
  }, [db, householdId]);

  const deps = useMemo(() => ({ onQueued: () => void syncRef.current.kick() }), []);

  // A delete is written after the undo window. Leaving the screen (or the page) writes what is still waiting.
  useEffect(() => {
    if (!db) return;
    const flush = () => {
      for (const { entry, timer } of timers.current.values()) {
        clearTimeout(timer);
        void deleteStore(db, deps, entry.id).catch(() => undefined);
      }
      timers.current.clear();
      setPending([]);
    };
    window.addEventListener('pagehide', flush);
    return () => {
      window.removeEventListener('pagehide', flush);
      flush();
    };
  }, [db, deps]);

  const rows = useMemo(() => {
    if (!data) return null;
    const hidden = new Set(pending.map((p) => p.id));
    return storeRows(
      data.stores.filter((s) => !hidden.has(s.id)),
      data.orders,
    );
  }, [data, pending]);

  if (status !== 'ready' || !modules.shopping || !rows || !data || !db || !householdId) return null;

  const defer = (entry: Pending) => {
    const old = timers.current.get(entry.id);
    if (old) clearTimeout(old.timer);
    const timer = setTimeout(() => {
      timers.current.delete(entry.id);
      setPending((p) => p.filter((x) => x.id !== entry.id));
      void deleteStore(db, deps, entry.id);
    }, UNDO_MS);
    timers.current.set(entry.id, { entry, timer });
    setPending((p) => [entry, ...p.filter((x) => x.id !== entry.id)]);
  };
  const undo = (id: string) => {
    const t = timers.current.get(id);
    if (t) clearTimeout(t.timer);
    timers.current.delete(id);
    setPending((p) => p.filter((x) => x.id !== id));
  };

  return (
    <div className={styles.stores} data-testid="stores">
      <Link to="/shopping" className={styles.back}>
        Shopping
      </Link>
      <div className={styles.head}>
        <h2 className={styles.heading}>Stores</h2>
        <button type="button" className={styles.plus} aria-label="New store" onClick={() => setSheet(true)}>
          +
        </button>
      </div>
      {rows.length === 0 ? (
        <EmptyState
          title="No stores yet"
          text="Name the shops you go to, then pick one on a list."
          action={
            <button type="button" className={styles.primary} onClick={() => setSheet(true)}>
              New store
            </button>
          }
        />
      ) : (
        <ul className={styles.rows} aria-label="Stores">
          {rows.map((s) => (
            <ListRow
              key={s.id}
              title={s.name}
              subtitle={learnedLabel(s.learned) ?? undefined}
              // Aisle order (C5) arrives with SHOP-06; until then a tap has nowhere to go.
              onOpen={() => undefined}
              onSwipeLeft={() => defer({ id: s.id, name: s.name })}
              actions={
                <button type="button" aria-label={`Delete ${s.name}`} onClick={() => defer({ id: s.id, name: s.name })}>
                  Delete
                </button>
              }
            />
          ))}
        </ul>
      )}
      {pending.map((p, index) => (
        <UndoToast key={p.id} index={pending.length - 1 - index} message={`Deleted ${p.name}`} onUndo={() => undo(p.id)} />
      ))}
      {sheet ? (
        <StoreSheet
          stores={data.stores}
          onClose={() => setSheet(false)}
          onSave={async (name) => {
            await createStore(db, deps, { householdId, name });
            setSheet(false);
          }}
        />
      ) : null}
    </div>
  );
}
