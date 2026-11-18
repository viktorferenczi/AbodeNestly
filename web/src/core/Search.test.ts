import { beforeEach, describe, expect, it } from 'vitest';
import type { Modules } from './household/HouseholdProvider.tsx';
import {
  enabledTables,
  pushRecentSearch,
  readRecentSearches,
  searchSections,
  searchState,
  targetPath,
  type SearchRow,
  type SearchTable,
} from './Search.ts';

const ALL: Modules = { shopping: true, pantry: true, maintenance: true };
const row = (table: SearchTable, id: string, name: string, over: Partial<SearchRow> = {}): SearchRow => ({
  table, id, name, deleted_at: null, ...over,
});

describe('CORE-09: search model', () => {
  it('CORE-09: AC3 groups list item and pantry item under Shopping then Pantry', () => {
    const s = searchSections([row('pantry_item', 'p1', 'Milk'), row('list_item', 'l1', 'Milk', { list_id: 'L' })], 'milk', ALL);
    expect(s.map((x) => x.module)).toEqual(['shopping', 'pantry']);
    expect(s[0]!.items[0]!.target).toEqual({ module: 'shopping', table: 'list_item', id: 'l1', listId: 'L' });
  });

  it.each([
    ['pantry off', { ...ALL, pantry: false }, ['shopping', 'maintenance']],
    ['all off', { shopping: false, pantry: false, maintenance: false }, []],
    ['only maintenance', { shopping: false, pantry: false, maintenance: true }, ['maintenance']],
  ])('CORE-09: AC4 %s omits disabled sections even when rows are passed in', (_n, modules, expected) => {
    const rows = [row('list_item', 'a', 'Tea'), row('pantry_item', 'b', 'Tea'), row('asset', 'c', 'Tea')];
    expect(searchSections(rows, 'tea', modules).map((x) => x.module)).toEqual(expected);
  });

  it.each([
    ['trim and case', '  MILK ', 1],
    ['substring', 'ilk', 1],
    ['no match', 'bread', 0],
    ['empty', '', 0],
    ['whitespace', '   ', 0],
  ])('CORE-09: matching %s', (_n, q, count) => {
    expect(searchSections([row('store', 's', 'Milk Bar')], q, ALL).flatMap((s) => s.items)).toHaveLength(count);
  });

  it('CORE-09: drops soft-deleted rows', () => {
    expect(searchSections([row('store', 's', 'Milk', { deleted_at: '2026-01-01T00:00:00Z' })], 'milk', ALL)).toEqual([]);
  });

  it('CORE-09: orders prefix matches first, then alphabetical, within a section', () => {
    const s = searchSections(
      [row('store', '1', 'Big Oat'), row('list_item', '2', 'Oats'), row('list_item', '3', 'Oat milk'), row('store', '4', 'Abc oat')],
      'oat',
      ALL,
    );
    expect(s[0]!.items.map((i) => i.name)).toEqual(['Oat milk', 'Oats', 'Abc oat', 'Big Oat']);
  });

  it('CORE-09: kind labels', () => {
    const s = searchSections([row('recipe', 'r', 'Soup'), row('maint_task', 't', 'Soup pipe')], 'soup', ALL);
    expect(s.flatMap((x) => x.items.map((i) => i.kind))).toEqual(['Recipe', 'Task']);
  });

  it('CORE-09: state derives from query and sections', () => {
    const s = searchSections([row('store', 's', 'Milk')], 'milk', ALL);
    expect(searchState(' ', [])).toBe('empty-query');
    expect(searchState('x', [])).toBe('no-results');
    expect(searchState('milk', s)).toBe('results');
  });

  it('CORE-09: enabledTables skips disabled modules', () => {
    expect(enabledTables({ ...ALL, pantry: false })).toEqual(['list_item', 'store', 'asset', 'maint_task', 'contractor']);
  });

  it.each([
    [{ module: 'shopping', table: 'list_item', id: 'i', listId: 'L' }, '/shopping/L?item=i'],
    [{ module: 'shopping', table: 'list_item', id: 'i' }, null],
    [{ module: 'shopping', table: 'store', id: 's' }, '/shopping/stores/s'],
    [{ module: 'pantry', table: 'pantry_item', id: 'p' }, '/pantry?item=p'],
    [{ module: 'pantry', table: 'location', id: 'x' }, '/pantry/settings'],
    [{ module: 'pantry', table: 'recipe', id: 'r' }, '/pantry/recipes/r'],
    [{ module: 'maintenance', table: 'asset', id: 'a' }, '/maintenance/assets/a'],
    [{ module: 'maintenance', table: 'maint_task', id: 't' }, '/maintenance/tasks/t'],
    [{ module: 'maintenance', table: 'contractor', id: 'c' }, '/maintenance/contractors'],
  ] as const)('CORE-09: targetPath %j', (t, path) => {
    expect(targetPath(t)).toBe(path);
  });
});

describe('CORE-09: recent searches', () => {
  beforeEach(() => localStorage.clear());

  it('de-duplicates case-insensitively, newest first', () => {
    pushRecentSearch('h1', 'milk');
    pushRecentSearch('h1', 'eggs');
    expect(pushRecentSearch('h1', ' MILK ')).toEqual(['MILK', 'eggs']);
  });

  it('caps at 8 and is per household', () => {
    for (let i = 0; i < 10; i++) pushRecentSearch('h1', `q${i}`);
    expect(readRecentSearches('h1')).toEqual(['q9', 'q8', 'q7', 'q6', 'q5', 'q4', 'q3', 'q2']);
    expect(readRecentSearches('h2')).toEqual([]);
  });

  it('ignores blank queries and corrupt storage', () => {
    expect(pushRecentSearch('h1', '  ')).toEqual([]);
    localStorage.setItem('abodenestly:search:h1', '{bad');
    expect(readRecentSearches('h1')).toEqual([]);
  });
});
