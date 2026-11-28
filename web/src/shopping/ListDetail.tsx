import { useEffect, useMemo, useReducer, useRef, useState } from 'react';
import type { ListItem } from '@abodenestly/contract';
import { Link, useParams } from 'react-router';
import { useApi } from '../core/api/ApiProvider.tsx';
import { useAuth } from '../core/auth/AuthProvider.tsx';
import { useHousehold } from '../core/household/HouseholdProvider.tsx';
import { useSync } from '../sync/SyncEngine.ts';
import { EmptyState } from '../ui/EmptyState.tsx';
import { ListRow } from '../ui/ListRow.tsx';
import { UndoToast } from '../ui/UndoToast.tsx';
import { useLiveQuery } from '../ui/useLiveQuery.ts';
import { deleteItem, formatQty, unitSuggestions, updateItem } from './ItemSheet.ts';
import { clearChecked, createCheckOffQueue, uncheck } from './CheckOff.ts';
import { ItemSheet, type Pricing } from './ItemSheet.tsx';
import { lastBoughtLookup, priceCaption, saveWithPrice } from './Price.ts';
import { UNDO_MS } from './Lists.ts';
import { StorePickerSheet } from './StorePickerSheet.tsx';
import { createStore, setListStore } from './Stores.ts';
import { drainLookups } from './Barcode.ts';
import { QuickAdd } from './QuickAdd.tsx';
import { AddAllSheet } from './AddAllSheet.tsx';
import { addAllToList, targetLists } from './Templates.ts';
import styles from './ListDetail.module.css';

// DESIGN C2: name, store chip, item rows with a checkbox (qty, unit, note; tap opens the item sheet), a Checked section, Clear checked and the quick add bar.
// Sorting and the other overflow actions belong to SHOP-06/07.
export function ListDetail() {
  const { listId = '' } = useParams();
  const { status, db, household, modules } = useHousehold();
  const { session } = useAuth();
  const userId = session?.user.id ?? null;
  const sync = useSync();
  const syncRef = useRef(sync);
  syncRef.current = sync;
  const deps = useMemo(() => ({ onQueued: () => void syncRef.current.kick() }), []);
  const householdId = household?.id;
  const api = useApi();
  // SHOP-10: queued barcode lookups resolve when the screen opens, when the network returns and after each sync.
  useEffect(() => {
    if (!db || !householdId) return;
    const drain = () => void drainLookups(db, deps, api, householdId).catch(() => undefined);
    drain();
    window.addEventListener('online', drain);
    const unsubscribe = sync.subscribe(() => {
      if (sync.getPhase() === 'idle') drain();
    });
    return () => {
      window.removeEventListener('online', drain);
      unsubscribe();
    };
  }, [db, householdId, deps, api, sync]);
  // The row as it was when its sheet opened: the form starts from it, so Save diffs against it, not against a row a pull changed meanwhile.
  const [editing, setEditing] = useState<ListItem | null>(null);
  const [pending, setPending] = useState<{ id: string; name: string }[]>([]);
  const timers = useRef(new Map<string, { name: string; timer: ReturnType<typeof setTimeout> }>());
  // Clear checked: the ids captured at tap time, written after the undo window.
  const [clears, setClears] = useState<{ key: number; ids: string[]; writing: boolean }[]>([]);
  // Creation order of each toast, so the newest sits on top whatever its kind (DESIGN A6).
  const toastSeq = useRef(new Map<string, number>());
  const seqCounter = useRef(0);
  const stamp = (key: string) => toastSeq.current.set(key, ++seqCounter.current);
  const clearTimers = useRef(new Map<number, { ids: string[]; timer: ReturnType<typeof setTimeout> }>());
  const clearSeq = useRef(0);
  const [menuOpen, setMenuOpen] = useState(false);
  const [picking, setPicking] = useState(false);
  const [addingAll, setAddingAll] = useState(false);
  // SHOP-11: a plain message after Add all (no Undo); it clears itself.
  const [notice, setNotice] = useState<string | null>(null);
  const noticeTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  useEffect(() => () => clearTimeout(noticeTimer.current), []);
  const [, rerender] = useReducer((n: number) => n + 1, 0);
  const queue = useMemo(() => (db ? createCheckOffQueue({ db, deps, userId, onChange: rerender }) : null), [db, deps, userId]);

  // A delete is written after the undo window, like Lists. Leaving the screen (or the page) writes what is still waiting.
  useEffect(() => {
    if (!db) return;
    const t = timers.current;
    const flush = () => {
      for (const [id, { timer }] of t) {
        clearTimeout(timer);
        void deleteItem(db, deps, id).catch(() => undefined);
      }
      t.clear();
      for (const { ids, timer } of clearTimers.current.values()) {
        clearTimeout(timer);
        void clearChecked(db, deps, ids).catch(() => undefined);
      }
      clearTimers.current.clear();
      void queue?.flush();
    };
    window.addEventListener('pagehide', flush);
    return () => {
      window.removeEventListener('pagehide', flush);
      flush();
    };
  }, [db, deps, queue]);

  const data = useLiveQuery(async () => {
    if (!db || !householdId) return null;
    const [list, items, stores, lists, prices] = await Promise.all([
      db.shopping_list.get(listId),
      db.list_item.where('household_id').equals(householdId).toArray(),
      db.store.where('household_id').equals(householdId).toArray(),
      db.shopping_list.where('household_id').equals(householdId).toArray(),
      db.item_price.where('household_id').equals(householdId).toArray(),
    ]);
    return { list, items, stores, lists, prices };
  }, [db, householdId, listId]);

  const view = useMemo(() => {
    if (!data) return null;
    const hidden = new Set([...pending.map((p) => p.id), ...clears.flatMap((c) => c.ids)]);
    const mine = data.items.filter((i) => i.list_id === listId && i.deleted_at == null && !hidden.has(i.id));
    const byCreated = (a: { created_at: string }, b: { created_at: string }) => a.created_at.localeCompare(b.created_at);
    const store = data.list?.store_id ? data.stores.find((s) => s.id === data.list!.store_id && s.deleted_at == null) : undefined;
    const currency = household?.currency ?? 'EUR';
    const lookup = lastBoughtLookup(data.prices, data.stores, store?.id ?? null);
    const pricing: Pricing = { storeName: store?.name ?? null, currency, lastBought: lookup };
    return {
      pricing,
      storeId: store?.id ?? null,
      caption: (name: string) => {
        const last = lookup(name);
        return last ? priceCaption(last, currency) : undefined;
      },
      unchecked: mine.filter((i) => !i.checked).sort(byCreated),
      checked: mine.filter((i) => i.checked).sort((a, b) => (b.checked_at ?? '').localeCompare(a.checked_at ?? '')),
      storeName: store?.name ?? null,
      stores: data.stores.filter((s) => s.deleted_at == null).sort((a, b) => a.name.localeCompare(b.name)),
    };
  }, [data, listId, pending, clears, household?.currency]);

  // The row was deleted elsewhere while its sheet was open: drop the sheet.
  const gone = editing != null && data != null && !data.items.some((i) => i.id === editing.id && i.deleted_at == null);
  useEffect(() => {
    if (gone) setEditing(null);
  }, [gone]);

  if (status !== 'ready' || !modules.shopping || !data || !view || !db || !householdId) return null;

  const pendingChecks = new Set(queue?.pending().map((p) => p.id));

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

  const isTemplate = data.list.is_template;

  const row = (i: ListItem, muted: boolean) => {
    const waiting = pendingChecks.has(i.id);
    const ticked = muted || waiting;
    return (
      <ListRow
        key={i.id}
        title={i.name}
        subtitle={i.note ?? undefined}
        caption={view.caption(i.name)}
        trailing={formatQty(i.quantity, i.unit)}
        muted={muted}
        check={{
          checked: ticked,
          label: `${ticked ? 'Uncheck' : 'Check off'} ${i.name}`,
          onToggle: () => {
            if (waiting) queue?.undo(i.id);
            else if (muted) void uncheck(db, deps, i.id);
            else {
              stamp(`c${i.id}`);
              queue?.check(i);
            }
          },
        }}
        onOpen={() => setEditing(i)}
      />
    );
  };
  const clearAll = () => {
    setMenuOpen(false);
    const ids = view.checked.map((i) => i.id);
    if (ids.length === 0) return;
    const key = ++clearSeq.current;
    const timer = setTimeout(() => {
      clearTimers.current.delete(key);
      // The rows stay hidden until the delete has landed, so they do not flash back under Checked.
      setClears((c) => c.map((x) => (x.key === key ? { ...x, writing: true } : x)));
      void clearChecked(db, deps, ids)
        .catch(() => undefined)
        .finally(() => setClears((c) => c.filter((x) => x.key !== key)));
    }, UNDO_MS);
    clearTimers.current.set(key, { ids, timer });
    stamp(`x${key}`);
    setClears((c) => [...c, { key, ids, writing: false }]);
  };
  const undoClear = (key: number) => {
    clearTimeout(clearTimers.current.get(key)?.timer);
    clearTimers.current.delete(key);
    setClears((c) => c.filter((x) => x.key !== key));
  };
  const remove = (i: ListItem) => {
    setEditing(null);
    queue?.undo(i.id);
    stamp(`d${i.id}`);
    const old = timers.current.get(i.id);
    if (old) clearTimeout(old.timer);
    const timer = setTimeout(() => {
      timers.current.delete(i.id);
      setPending((p) => p.filter((x) => x.id !== i.id));
      void deleteItem(db, deps, i.id);
    }, UNDO_MS);
    timers.current.set(i.id, { name: i.name, timer });
    setPending((p) => [...p.filter((x) => x.id !== i.id), { id: i.id, name: i.name }]);
  };
  // Nothing was written yet, so Undo only drops the pending delete.
  const undo = (id: string) => {
    clearTimeout(timers.current.get(id)?.timer);
    timers.current.delete(id);
    setPending((p) => p.filter((x) => x.id !== id));
  };

  const empty = view.unchecked.length === 0 && view.checked.length === 0;
  const allChecked = view.unchecked.length === 0 && view.checked.length > 0;
  // Items hidden by a pending Clear checked still count as a list that has items.
  const clearing = clears.length > 0;

  // One stack, newest on top (DESIGN A6).
  const toasts = [
    ...pending.map((d) => ({ key: `d${d.id}`, message: `Deleted ${d.name}`, undo: () => undo(d.id) })),
    ...(queue?.pending() ?? []).filter((c) => !c.writing).map((c) => ({ key: `c${c.id}`, message: `Checked ${c.name}`, undo: () => queue?.undo(c.id) })),
    ...clears.filter((c) => !c.writing).map((c) => ({ key: `x${c.key}`, message: `Cleared ${c.ids.length} ${c.ids.length === 1 ? 'item' : 'items'}`, undo: () => undoClear(c.key) })),
  ].sort((a, b) => (toastSeq.current.get(a.key) ?? 0) - (toastSeq.current.get(b.key) ?? 0));

  return (
    <div className={styles.detail} data-testid="list-detail">
      <div className={styles.content}>
        {back}
        <div className={styles.head}>
          <div>
            <h2 className={styles.heading}>{data.list.name}</h2>
            {isTemplate ? <p className={styles.caption}>Template</p> : null}
          </div>
          <button type="button" className={view.storeName ? styles.chip : `${styles.chip} ${styles.chipEmpty}`} aria-label={view.storeName ? `Store: ${view.storeName}` : undefined} onClick={() => setPicking(true)}>
            {view.storeName ?? 'Add store'}
          </button>
          {view.checked.length > 0 || isTemplate ? (
            <div
              className={styles.menuWrap}
              onKeyDown={(e) => {
                if (e.key === 'Escape') setMenuOpen(false);
              }}
              onBlur={(e) => {
                if (!e.currentTarget.contains(e.relatedTarget)) setMenuOpen(false);
              }}
            >
              <button type="button" className={styles.more} aria-label="List actions" aria-haspopup="menu" aria-expanded={menuOpen} onClick={() => setMenuOpen(!menuOpen)}>
                ⋯
              </button>
              {menuOpen ? (
                <div className={styles.menu} role="menu">
                  {isTemplate ? (
                    <button
                      type="button"
                      role="menuitem"
                      className={styles.menuItem}
                      onClick={() => {
                        setMenuOpen(false);
                        setAddingAll(true);
                      }}
                    >
                      Add all to list
                    </button>
                  ) : null}
                  {view.checked.length > 0 ? (
                    <button type="button" role="menuitem" className={styles.menuItem} onClick={clearAll}>
                      Clear checked
                    </button>
                  ) : null}
                </div>
              ) : null}
            </div>
          ) : null}
        </div>
        {empty && !clearing && !pending.length ? <p className={styles.hint}>Add your first item</p> : null}
        {allChecked ? (
          <div className={styles.done}>
            <p className={styles.hint}>All done</p>
            <button type="button" className={styles.clear} onClick={clearAll}>
              Clear checked
            </button>
          </div>
        ) : null}
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
      {editing && !gone ? (
        <ItemSheet
          mode="edit"
          item={editing}
          units={unitSuggestions(data.items)}
          pricing={view.pricing}
          onClose={() => setEditing(null)}
          onSave={async (fields, price) => {
            // The price and the item change commit together; an empty price field writes nothing.
            const priced = price != null && view.storeId ? { householdId, storeId: view.storeId, name: fields.name, price, now: new Date().toISOString() } : null;
            await saveWithPrice(db, deps, priced, (quiet) => updateItem(db, quiet, editing, fields));
            setEditing(null);
          }}
          onDelete={() => remove(editing)}
        />
      ) : null}
      {picking ? (
        <StorePickerSheet
          stores={view.stores}
          current={data.list.store_id && view.storeName ? data.list.store_id : null}
          onClose={() => setPicking(false)}
          onSave={(storeId) => setListStore(db, deps, listId, storeId)}
          onCreate={(name) => createStore(db, deps, { householdId, name })}
        />
      ) : null}
      {addingAll ? (
        <AddAllSheet
          lists={targetLists(data.lists).filter((l) => l.household_id === householdId)}
          onClose={() => setAddingAll(false)}
          onPick={async (target) => {
            const n = await addAllToList(db, deps, { householdId, templateId: listId, targetListId: target.id, userId });
            setAddingAll(false);
            setNotice(n === 0 ? 'Nothing to add: this template is empty' : `Added ${n} ${n === 1 ? 'item' : 'items'} to ${target.name}`);
            clearTimeout(noticeTimer.current);
            noticeTimer.current = setTimeout(() => setNotice(null), UNDO_MS);
          }}
        />
      ) : null}
      {notice ? (
        <p role="status" aria-live="polite" className={styles.notice}>
          {notice}
        </p>
      ) : null}
      {toasts.map((t, index) => (
        <UndoToast key={t.key} index={index} message={t.message} onUndo={t.undo} />
      ))}
      <QuickAdd db={db} householdId={householdId} listId={listId} items={data.items} pricing={view.pricing} storeId={view.storeId} autoFocus={empty && !clearing} />
    </div>
  );
}
