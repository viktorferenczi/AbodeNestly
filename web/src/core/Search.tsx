import { useEffect, useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import { useLiveQuery } from '../ui/useLiveQuery.ts';
import { useHousehold } from './household/HouseholdProvider.tsx';
import {
  enabledTables,
  MODULE_LABEL,
  pushRecentSearch,
  readRecentSearches,
  searchSections,
  searchState,
  targetPath,
  type SearchRow,
} from './Search.ts';
import styles from './Search.module.css';

export const SEARCH_DEBOUNCE_MS = 150;

// No debounce library is allowed (SPEC §3), so this is a plain timer effect.
function useDebounced<T>(value: T, ms: number): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const t = setTimeout(() => setDebounced(value), ms);
    return () => clearTimeout(t);
  }, [value, ms]);
  return debounced;
}

// DESIGN B8. Reads only the local database, so it works offline; tables of disabled modules are not queried.
export function Search() {
  const { status, db, household, modules } = useHousehold();
  const householdId = household?.id;
  const [query, setQuery] = useState('');
  const debounced = useDebounced(query, SEARCH_DEBOUNCE_MS);
  const [recents, setRecents] = useState<string[]>([]);
  useEffect(() => setRecents(householdId ? readRecentSearches(householdId) : []), [householdId]);

  const tables = enabledTables(modules);
  const rows = useLiveQuery<SearchRow[]>(async () => {
    if (!db || !householdId || debounced.trim() === '') return [];
    const parts = await Promise.all(
      tables.map(async (table): Promise<SearchRow[]> => {
        const all = await db.table(table).where('household_id').equals(householdId).toArray();
        return all.map((r) => ({
          table,
          id: r.id,
          name: table === 'maint_task' ? r.title : r.name,
          deleted_at: r.deleted_at,
          list_id: r.list_id,
        }));
      }),
    );
    return parts.flat();
  }, [db, householdId, debounced, tables.join(',')]);

  if (status !== 'ready') return null;

  const sections = rows ? searchSections(rows, debounced, modules) : [];
  const state = searchState(debounced, sections);
  const remember = () => {
    if (householdId) setRecents(pushRecentSearch(householdId, debounced));
  };
  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    if (state === 'results') remember();
  };

  return (
    <div className={styles.search} data-testid="search">
      <form onSubmit={onSubmit} role="search">
        <input
          className={styles.field}
          type="search"
          autoFocus
          aria-label="Search"
          placeholder="Search your household"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
      </form>

      {state === 'empty-query' ? (
        <section data-testid="search.empty" aria-label="Recent searches">
          <h2 className={styles.heading}>Recent searches</h2>
          {recents.length === 0 ? (
            <p className={styles.hint}>Search lists, pantry and maintenance by name.</p>
          ) : (
            <ul className={styles.list}>
              {recents.map((r) => (
                <li key={r}>
                  <button type="button" className={styles.row} onClick={() => setQuery(r)}>
                    {r}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </section>
      ) : null}

      {state === 'no-results' && rows ? (
        <p className={styles.hint} data-testid="search.noResults">
          No results for “{debounced.trim()}”.
        </p>
      ) : null}

      {state === 'results'
        ? sections.map((s) => (
            <section key={s.module} data-testid={`search.section.${s.module}`} aria-label={MODULE_LABEL[s.module]}>
              <h2 className={`${styles.heading} ${styles[s.module]}`}>{MODULE_LABEL[s.module]}</h2>
              <ul className={styles.list}>
                {s.items.map((i) => (
                  <li key={i.key}>
                    <Link to={targetPath(i.target)} className={styles.row} data-testid="search.row" onClick={remember}>
                      <span>{i.name}</span>
                      <span className={styles.kind}>{i.kind}</span>
                    </Link>
                  </li>
                ))}
              </ul>
            </section>
          ))
        : null}
    </div>
  );
}
