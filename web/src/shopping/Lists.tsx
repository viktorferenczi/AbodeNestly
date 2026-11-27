import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { useHousehold } from '../core/household/HouseholdProvider.tsx';
import { useSync } from '../sync/SyncEngine.ts';
import { EmptyState } from '../ui/EmptyState.tsx';
import { ListRow } from '../ui/ListRow.tsx';
import { UndoToast } from '../ui/UndoToast.tsx';
import { useLiveQuery } from '../ui/useLiveQuery.ts';
import { archiveList, createList, deleteList, listSections, restoreList, UNDO_MS, type ListRowModel } from './Lists.ts';
import { NewListSheet } from './NewListSheet.tsx';
import styles from './Lists.module.css';

type Pending = { id: string; kind: 'archive' | 'delete'; name: string };

// DESIGN C1. Reads only the local database; every write goes through the outbox, so it all works offline.
export function Lists() {
  const { status, db, household, modules } = useHousehold();
  const sync = useSync();
  const navigate = useNavigate();
  const householdId = household?.id;
  const [sheet, setSheet] = useState(false);
  const [pending, setPending] = useState<Pending[]>([]);
  const timers = useRef(new Map<string, { entry: Pending; timer: ReturnType<typeof setTimeout> }>());
  const syncRef = useRef(sync);
  syncRef.current = sync;

  const data = useLiveQuery(async () => {
    if (!db || !householdId) return null;
    const [lists, items, stores] = await Promise.all([
      db.shopping_list.where('household_id').equals(householdId).toArray(),
      db.list_item.where('household_id').equals(householdId).toArray(),
      db.store.where('household_id').equals(householdId).toArray(),
    ]);
    return { lists, items, stores };
  }, [db, householdId]);

  const deps = useMemo(() => ({ onQueued: () => void syncRef.current.kick() }), []);

  // A swipe is written after the undo window. Leaving the screen (or the page) writes what is still waiting.
  useEffect(() => {
    if (!db) return;
    const flush = () => {
      for (const { entry, timer } of timers.current.values()) {
        clearTimeout(timer);
        void (entry.kind === 'archive' ? archiveList : deleteList)(db, deps, entry.id).catch(() => undefined);
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

  const sections = useMemo(() => {
    if (!data) return null;
    const hidden = new Set(pending.map((p) => p.id));
    return listSections(
      data.lists.filter((l) => !hidden.has(l.id)),
      data.items,
      data.stores,
    );
  }, [data, pending]);

  if (status !== 'ready' || !modules.shopping || !sections || !db || !householdId) return null;

  const defer = (entry: Pending) => {
    // A second action on the same list replaces the first, so only one write is ever queued.
    const old = timers.current.get(entry.id);
    if (old) clearTimeout(old.timer);
    const timer = setTimeout(() => {
      timers.current.delete(entry.id);
      setPending((p) => p.filter((x) => x.id !== entry.id));
      void (entry.kind === 'archive' ? archiveList : deleteList)(db, deps, entry.id);
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

  const stores = (data?.stores ?? []).filter((s) => s.deleted_at == null).sort((a, b) => a.name.localeCompare(b.name));
  const open = (id: string) => navigate(`/shopping/${id}`);

  const row = (l: ListRowModel, archived: boolean) => (
    <ListRow
      key={l.id}
      title={l.name}
      chip={l.storeName ?? undefined}
      subtitle={l.count}
      onOpen={() => open(l.id)}
      onSwipeLeft={() => defer({ id: l.id, kind: archived ? 'delete' : 'archive', name: l.name })}
      actions={
        archived ? (
          <>
            <button type="button" aria-label={`Restore ${l.name}`} onClick={() => void restoreList(db, deps, l.id)}>
              Restore
            </button>
            <button type="button" aria-label={`Delete ${l.name}`} onClick={() => defer({ id: l.id, kind: 'delete', name: l.name })}>
              Delete
            </button>
          </>
        ) : (
          <button type="button" aria-label={`Archive ${l.name}`} onClick={() => defer({ id: l.id, kind: 'archive', name: l.name })}>
            Archive
          </button>
        )
      }
    />
  );

  // Archived lists are lists: the first-list prompt is only for a household with none at all.
  const empty = sections.lists.length === 0 && sections.templates.length === 0 && sections.archived.length === 0;

  return (
    <div className={styles.lists} data-testid="lists">
      <div className={styles.head}>
        <h2 className={styles.heading}>Shopping</h2>
        <div className={styles.headActions}>
          <Link to="/shopping/stores" className={styles.storesLink}>
            Stores
          </Link>
          <button type="button" className={styles.plus} aria-label="New list" onClick={() => setSheet(true)}>
            +
          </button>
        </div>
      </div>
      {empty ? (
        <EmptyState
          title="Make your first list"
          text="Lists keep what you need to buy, shared with everyone in your household."
          action={
            <button type="button" className={styles.primary} onClick={() => setSheet(true)}>
              New list
            </button>
          }
        />
      ) : (
        <>
          <section aria-labelledby="lists-h">
            <h3 id="lists-h" className={styles.section}>
              Lists
            </h3>
            <ul className={styles.rows}>{sections.lists.map((l) => row(l, false))}</ul>
          </section>
          {sections.templates.length > 0 ? (
            <section aria-labelledby="templates-h">
              <h3 id="templates-h" className={styles.section}>
                Templates
              </h3>
              <ul className={styles.rows}>{sections.templates.map((l) => row(l, false))}</ul>
            </section>
          ) : null}
        </>
      )}
      {sections.archived.length > 0 ? (
        <details className={styles.archived}>
          <summary>Archived ({sections.archived.length})</summary>
          <ul className={styles.rows}>{sections.archived.map((l) => row(l, true))}</ul>
        </details>
      ) : null}
      {empty ? null : (
        <button type="button" className={styles.fab} onClick={() => setSheet(true)}>
          New list
        </button>
      )}
      {pending.map((p, index) => (
        <UndoToast key={p.id} index={pending.length - 1 - index} message={`${p.kind === 'archive' ? 'Archived' : 'Deleted'} ${p.name}`} onUndo={() => undo(p.id)} />
      ))}
      {sheet ? (
        <NewListSheet
          stores={stores}
          onClose={() => setSheet(false)}
          onSave={async (name, storeId, isTemplate) => {
            const id = await createList(db, deps, { householdId, name, storeId, isTemplate });
            setSheet(false);
            open(id);
          }}
        />
      ) : null}
    </div>
  );
}
