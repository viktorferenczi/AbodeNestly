import { useEffect, useMemo, useRef, useState } from 'react';
import type { ListItem } from '@abodenestly/contract';
import { Link, useParams } from 'react-router';
import { useHousehold } from '../core/household/HouseholdProvider.tsx';
import { useSync } from '../sync/SyncEngine.ts';
import { EmptyState } from '../ui/EmptyState.tsx';
import { ListRow } from '../ui/ListRow.tsx';
import { UndoToast } from '../ui/UndoToast.tsx';
import { useLiveQuery } from '../ui/useLiveQuery.ts';
import { deleteItem, formatQty, restoreItem, unitSuggestions, updateItem } from './ItemSheet.ts';
import { ItemSheet } from './ItemSheet.tsx';
import { UNDO_MS } from './Lists.ts';
import { QuickAdd } from './QuickAdd.tsx';
import styles from './ListDetail.module.css';

// DESIGN C2, minimal: name, store chip, item rows (qty, unit, note; tap opens the item sheet), a Checked section and the quick add bar.
// Checkboxes, sorting and the overflow menu belong to SHOP-04/06/07.
export function ListDetail() {
  const { listId = '' } = useParams();
  const { status, db, household, modules } = useHousehold();
  const sync = useSync();
  const syncRef = useRef(sync);
  syncRef.current = sync;
  const deps = useMemo(() => ({ onQueued: () => void syncRef.current.kick() }), []);
  const householdId = household?.id;
  const [editing, setEditing] = useState<string | null>(null);
  const [deleted, setDeleted] = useState<{ id: string; name: string }[]>([]);
  const timers = useRef(new Map<string, ReturnType<typeof setTimeout>>());
  useEffect(() => {
    const t = timers.current;
    return () => t.forEach(clearTimeout);
  }, []);

  const data = useLiveQuery(async () => {
    if (!db || !householdId) return null;
    const [list, items, stores] = await Promise.all([
      db.shopping_list.get(listId),
      db.list_item.where('household_id').equals(householdId).toArray(),
      db.store.where('household_id').equals(householdId).toArray(),
    ]);
    return { list, items, stores };
  }, [db, householdId, listId]);

  const view = useMemo(() => {
    if (!data) return null;
    const mine = data.items.filter((i) => i.list_id === listId && i.deleted_at == null);
    const byCreated = (a: { created_at: string }, b: { created_at: string }) => a.created_at.localeCompare(b.created_at);
    const store = data.list?.store_id ? data.stores.find((s) => s.id === data.list!.store_id && s.deleted_at == null) : undefined;
    return {
      unchecked: mine.filter((i) => !i.checked).sort(byCreated),
      checked: mine.filter((i) => i.checked).sort(byCreated),
      storeName: store?.name ?? null,
    };
  }, [data, listId]);

  const editingItem = editing ? data?.items.find((i) => i.id === editing && i.list_id === listId && i.deleted_at == null) : undefined;
  // The row was deleted elsewhere while its sheet was open: drop the sheet.
  useEffect(() => {
    if (editing && data && !editingItem) setEditing(null);
  }, [editing, data, editingItem]);

  if (status !== 'ready' || !modules.shopping || !data || !view || !db || !householdId) return null;

  const back = (
    <Link to="/shopping" className={styles.back}>
      Shopping
    </Link>
  );

  if (!data.list || data.list.household_id !== householdId || data.list.deleted_at != null) {
    return (
      <div className={styles.detail} data-testid="list-detail">
        <EmptyState title="This list is gone" text="It was deleted on another device." action={back} />
      </div>
    );
  }

  const row = (i: ListItem, muted: boolean) => (
    <ListRow key={i.id} title={i.name} subtitle={i.note ?? undefined} trailing={formatQty(i.quantity, i.unit)} muted={muted} onOpen={() => setEditing(i.id)} />
  );
  const remove = (i: ListItem) => {
    setEditing(null);
    setDeleted((d) => [...d.filter((x) => x.id !== i.id), { id: i.id, name: i.name }]);
    const timer = setTimeout(() => {
      timers.current.delete(i.id);
      setDeleted((d) => d.filter((x) => x.id !== i.id));
    }, UNDO_MS);
    timers.current.set(i.id, timer);
    void deleteItem(db, deps, i.id);
  };
  const undo = (id: string) => {
    clearTimeout(timers.current.get(id));
    timers.current.delete(id);
    setDeleted((d) => d.filter((x) => x.id !== id));
    void restoreItem(db, deps, id);
  };

  const empty = view.unchecked.length === 0 && view.checked.length === 0;

  return (
    <div className={styles.detail} data-testid="list-detail">
      <div className={styles.content}>
        {back}
        <div className={styles.head}>
          <h2 className={styles.heading}>{data.list.name}</h2>
          {view.storeName ? <span className={styles.chip}>{view.storeName}</span> : null}
        </div>
        {empty ? <p className={styles.hint}>Add your first item</p> : null}
        <ul className={styles.rows} aria-label="Items">
          {view.unchecked.map((i) => row(i, false))}
        </ul>
        {view.checked.length > 0 ? (
          <details className={styles.checked}>
            <summary>Checked ({view.checked.length})</summary>
            <ul className={styles.rows}>
              {view.checked.map((i) => row(i, true))}
            </ul>
          </details>
        ) : null}
      </div>
      {editingItem ? (
        <ItemSheet
          mode="edit"
          item={editingItem}
          units={unitSuggestions(data.items)}
          onClose={() => setEditing(null)}
          onSave={async (fields) => {
            await updateItem(db, deps, editingItem, fields);
            setEditing(null);
          }}
          onDelete={() => remove(editingItem)}
        />
      ) : null}
      {deleted.map((d, index) => (
        <UndoToast key={d.id} index={index} message={`Deleted ${d.name}`} onUndo={() => undo(d.id)} />
      ))}
      <QuickAdd db={db} householdId={householdId} listId={listId} items={data.items} autoFocus={empty} />
    </div>
  );
}
