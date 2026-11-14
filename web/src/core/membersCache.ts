import type { Role } from '@abodenestly/contract';
import type { MemberRow } from './api/apiClient.ts';

// Web has no local DB or sync yet, so the last fetched list is kept per household for offline reads.
// Replaced by the Dexie `member` table when web sync lands.
const key = (householdId: string) => `abodenestly:members:${householdId}`;

const isRole = (v: unknown): v is Role => v === 'owner' || v === 'member';

/** Validates the parsed JSON; anything unexpected yields `null` so a stale shape never crashes the screen. */
export function parseMembersCache(raw: string | null): MemberRow[] | null {
  if (!raw) return null;
  let json: unknown;
  try {
    json = JSON.parse(raw);
  } catch {
    return null;
  }
  if (!Array.isArray(json)) return null;
  for (const row of json) {
    if (typeof row !== 'object' || row === null) return null;
    const { member, profile } = row as { member?: Record<string, unknown>; profile?: Record<string, unknown> | null };
    if (!member || typeof member.user_id !== 'string' || typeof member.household_id !== 'string' || !isRole(member.role)) {
      return null;
    }
    if (profile !== null && (typeof profile !== 'object' || typeof profile.display_name !== 'string')) return null;
  }
  return json as MemberRow[];
}

export function readMembersCache(householdId: string): MemberRow[] | null {
  try {
    return parseMembersCache(localStorage.getItem(key(householdId)));
  } catch {
    return null;
  }
}

export function writeMembersCache(householdId: string, rows: MemberRow[]): void {
  try {
    localStorage.setItem(key(householdId), JSON.stringify(rows));
  } catch {
    // Storage blocked (private mode): the list just isn't available offline.
  }
}
