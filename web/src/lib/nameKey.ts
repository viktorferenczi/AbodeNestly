/** Client twin of the SQL `name_key`: lower(regexp_replace(trim(p), '\s+', ' ', 'g')) (SPEC §5). */
export function nameKey(name: string): string {
  return name.trim().replace(/\s+/g, ' ').toLowerCase();
}
