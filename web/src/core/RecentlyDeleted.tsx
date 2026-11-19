import { useMemo, useState } from 'react';
import { useSync } from '../sync/SyncEngine.ts';
import { useLiveQuery } from '../ui/useLiveQuery.ts';
import { useHousehold } from './household/HouseholdProvider.tsx';
import { deletedSections, restore, type DeletedRow } from './RecentlyDeleted.ts';
import { enabledTables, MODULE_LABEL, type SearchModule } from './Search.ts';
import styles from './RecentlyDeleted.module.css';

const deps = { uuid: () => crypto.randomUUID(), now: () => new Date().toISOString() };

// DESIGN B11. Reads only the local database; tables of disabled modules are never queried (AC5).
export function RecentlyDeleted() {
  const { status, db, household, modules } = useHousehold();
  const sync = useSync();
  const householdId = household?.id;
  const [picked, setPicked] = useState<SearchModule | null>(null);

  const tables = enabledTables(modules);
  const rows = useLiveQuery<DeletedRow[]>(async () => {
    if (!db || !householdId) return [];
    const parts = await Promise.all(
      tables.map(async (table): Promise<DeletedRow[]> => {
        const all = await db.table(table).where('household_id').equals(householdId).toArray();
        return all
          .filter((r) => r.deleted_at != null)
          .map((r) => ({ table, id: r.id, name: table === 'maint_task' ? r.title : r.name, deleted_at: r.deleted_at }));
      }),
    );
    return parts.flat();
  }, [db, householdId, tables.join(',')]);

  // `now` is read per render: days left only change at day granularity and every restore re-renders.
  const sections = useMemo(() => (rows ? deletedSections(rows, modules, new Date()) : []), [rows, modules]);

  if (status !== 'ready' || !rows) return null;

  const selected = sections.find((s) => s.module === picked) ?? sections[0];
  const nothing = sections.every((s) => s.items.length === 0);

  const onRestore = async (table: DeletedRow['table'], id: string) => {
    if (!db) return;
    await restore(db, table, id, deps);
    void sync.kick();
  };

  return (
    <div className={styles.deleted} data-testid="deleted">
      <h2 className={styles.heading}>Recently deleted</h2>
      {nothing || !selected ? (
        <p className={styles.hint} data-testid="deleted.empty">
          Nothing deleted in the last 30 days.
        </p>
      ) : (
        <>
          <div role="tablist" aria-label="Module" className={styles.segments}>
            {sections.map((s) => (
              <button
                key={s.module}
                type="button"
                role="tab"
                aria-selected={s.module === selected.module}
                className={styles.segment}
                data-testid={`deleted.segment.${s.module}`}
                onClick={() => setPicked(s.module)}
              >
                {MODULE_LABEL[s.module]}
              </button>
            ))}
          </div>
          <div aria-live="polite">
            {selected.items.length === 0 ? (
              <p className={styles.hint}>Nothing deleted in {MODULE_LABEL[selected.module]}.</p>
            ) : (
              <ul className={styles.list}>
                {selected.items.map((i) => (
                  <li key={i.key} className={styles.row} data-testid="deleted.row">
                    <span className={styles.meta}>
                      <span>{i.name}</span>
                      <span className={styles.left}>{i.daysLeft === 1 ? '1 day left' : `${i.daysLeft} days left`}</span>
                    </span>
                    <button
                      type="button"
                      className={styles.restore}
                      data-testid="deleted.restore"
                      aria-label={`Restore ${i.name}`}
                      onClick={() => void onRestore(i.table, i.id)}
                    >
                      Restore
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </>
      )}
    </div>
  );
}
