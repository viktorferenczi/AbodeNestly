import type { Modules } from './household/HouseholdProvider.tsx';

export type SearchTable =
  | 'list_item'
  | 'store'
  | 'pantry_item'
  | 'location'
  | 'recipe'
  | 'asset'
  | 'maint_task'
  | 'contractor';
export type SearchModule = keyof Modules;

/** A local row reduced to what search needs; `maint_task` supplies its `title` as `name`. */
export type SearchRow = { table: SearchTable; id: string; name: string; deleted_at: string | null; list_id?: string };
export type SearchTarget = { module: SearchModule; table: SearchTable; id: string; listId?: string };
export type SearchItem = { key: string; name: string; kind: string; target: SearchTarget };
export type SearchSection = { module: SearchModule; items: SearchItem[] };

// SPEC §5 module mapping; the array order is the section order.
export const MODULE_TABLES: Record<SearchModule, SearchTable[]> = {
  shopping: ['list_item', 'store'],
  pantry: ['pantry_item', 'location', 'recipe'],
  maintenance: ['asset', 'maint_task', 'contractor'],
};
export const MODULE_ORDER: SearchModule[] = ['shopping', 'pantry', 'maintenance'];
export const MODULE_LABEL: Record<SearchModule, string> = { shopping: 'Shopping', pantry: 'Pantry', maintenance: 'Maintenance' };

const KIND: Record<SearchTable, string> = {
  list_item: 'List item',
  store: 'Store',
  pantry_item: 'Pantry item',
  location: 'Location',
  recipe: 'Recipe',
  asset: 'Asset',
  maint_task: 'Task',
  contractor: 'Contractor',
};

const MODULE_OF = Object.fromEntries(
  MODULE_ORDER.flatMap((m) => MODULE_TABLES[m].map((t) => [t, m] as const)),
) as Record<SearchTable, SearchModule>;

/** The tables to query: only those of enabled modules, so a disabled module's rows are never read. */
export function enabledTables(modules: Modules): SearchTable[] {
  return MODULE_ORDER.filter((m) => modules[m]).flatMap((m) => MODULE_TABLES[m]);
}

const fold = (s: string) => s.trim().toLowerCase();

/** Locale-insensitive on purpose: `toLowerCase` ignores the user's locale, so results match across devices. */
export function searchSections(rows: readonly SearchRow[], query: string, modules: Modules): SearchSection[] {
  const q = fold(query);
  if (q === '') return [];
  const byModule = new Map<SearchModule, SearchItem[]>();
  for (const r of rows) {
    const module = MODULE_OF[r.table];
    if (!modules[module] || r.deleted_at !== null || !fold(r.name).includes(q)) continue;
    const target: SearchTarget = { module, table: r.table, id: r.id, ...(r.list_id ? { listId: r.list_id } : {}) };
    const list = byModule.get(module) ?? [];
    list.push({ key: `${r.table}:${r.id}`, name: r.name, kind: KIND[r.table], target });
    byModule.set(module, list);
  }
  return MODULE_ORDER.flatMap((module) => {
    const items = byModule.get(module);
    if (!items) return [];
    const prefix = (i: SearchItem) => (fold(i.name).startsWith(q) ? 0 : 1);
    items.sort((a, b) => prefix(a) - prefix(b) || fold(a.name).localeCompare(fold(b.name), 'en') || (a.key < b.key ? -1 : 1));
    return [{ module, items }];
  });
}

export type SearchState = 'empty-query' | 'no-results' | 'results';

export function searchState(query: string, sections: readonly SearchSection[]): SearchState {
  if (fold(query) === '') return 'empty-query';
  return sections.length === 0 ? 'no-results' : 'results';
}

/** DESIGN B8 entity paths; the destinations are placeholders until each module's list story. */
export function targetPath(t: SearchTarget): string {
  switch (t.table) {
    case 'list_item':
      return `/shopping/${t.listId ?? ''}`;
    case 'store':
      return `/shopping/stores/${t.id}`;
    case 'pantry_item':
      return `/pantry?item=${t.id}`;
    case 'location':
      return '/pantry/settings';
    case 'recipe':
      return `/pantry/recipes/${t.id}`;
    case 'asset':
      return `/maintenance/assets/${t.id}`;
    case 'maint_task':
      return `/maintenance/tasks/${t.id}`;
    case 'contractor':
      return '/maintenance/contractors';
  }
}

export const RECENT_LIMIT = 8;
const recentKey = (householdId: string) => `abodenestly:search:${householdId}`;

export function readRecentSearches(householdId: string): string[] {
  try {
    const json: unknown = JSON.parse(localStorage.getItem(recentKey(householdId)) ?? 'null');
    return Array.isArray(json) ? json.filter((s): s is string => typeof s === 'string').slice(0, RECENT_LIMIT) : [];
  } catch {
    return [];
  }
}

/** Newest first, case-insensitive de-duplication, capped at 8. Returns the stored list. */
export function pushRecentSearch(householdId: string, query: string): string[] {
  const q = query.trim();
  const current = readRecentSearches(householdId);
  if (q === '') return current;
  const next = [q, ...current.filter((s) => fold(s) !== fold(q))].slice(0, RECENT_LIMIT);
  try {
    localStorage.setItem(recentKey(householdId), JSON.stringify(next));
  } catch {
    // Storage blocked (private mode): recents just aren't remembered.
  }
  return next;
}
