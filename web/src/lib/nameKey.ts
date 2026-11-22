/**
 * Client twin of the SQL `name_key`: lower(regexp_replace(trim(p), '\s+', ' ', 'g')) (SPEC §5). Postgres `trim()` strips
 * only U+0020, so JS `trim` (tabs, newlines, Unicode spaces) would differ for a leading or trailing tab.
 */
export function nameKey(name: string): string {
  return name.replace(/^ +| +$/g, '').replace(/\s+/g, ' ').toLowerCase();
}
