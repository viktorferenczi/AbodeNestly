import { useMemo } from 'react';
import { Link, useParams } from 'react-router';
import { useHousehold } from '../core/household/HouseholdProvider.tsx';
import { EmptyState } from '../ui/EmptyState.tsx';
import { OfflineBanner } from '../ui/OfflineBanner.tsx';
import { useLiveQuery } from '../ui/useLiveQuery.ts';
import { useOnline } from '../ui/useOnline.ts';
import { QuickAdd } from './QuickAdd.tsx';
import styles from './ListDetail.module.css';

// DESIGN C2, minimal: name, store chip, item names, a read-only Checked section and the quick add bar.
// Checkboxes, quantities, sorting and the overflow menu belong to SHOP-03/04/06/07.
export function ListDetail() {
  const { listId = '' } = useParams();
  const { status, db, household, modules } = useHousehold();
  const online = useOnline();
  const householdId = household?.id;

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

  const empty = view.unchecked.length === 0 && view.checked.length === 0;

  return (
    <div className={styles.detail} data-testid="list-detail">
      <div className={styles.content}>
        {back}
        <div className={styles.head}>
          <h2 className={styles.heading}>{data.list.name}</h2>
          {view.storeName ? <span className={styles.chip}>{view.storeName}</span> : null}
        </div>
        {online ? null : <OfflineBanner message="You're offline. Changes are saved and sync when you reconnect." />}
        {empty ? <p className={styles.hint}>Add your first item</p> : null}
        <ul className={styles.rows} aria-label="Items">
          {view.unchecked.map((i) => (
            <li key={i.id} className={styles.row}>
              {i.name}
            </li>
          ))}
        </ul>
        {view.checked.length > 0 ? (
          <details className={styles.checked}>
            <summary>Checked ({view.checked.length})</summary>
            <ul className={styles.rows}>
              {view.checked.map((i) => (
                <li key={i.id} className={`${styles.row} ${styles.dim}`}>
                  {i.name}
                </li>
              ))}
            </ul>
          </details>
        ) : null}
      </div>
      <QuickAdd db={db} householdId={householdId} listId={listId} items={data.items} autoFocus={empty} />
    </div>
  );
}
