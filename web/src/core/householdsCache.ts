import type { Household, Role } from '@abodenestly/contract';

export type HouseholdEntry = { household: Household; role: Role };

// Per-user, like membersCache.ts: a different account on this browser must not see this one's households.
// Lets the switcher work offline (SPEC §6.1).
const key = (userId: string) => `abodenestly:households:${userId}`;

export function readHouseholds(userId: string): HouseholdEntry[] {
  try {
    const json: unknown = JSON.parse(localStorage.getItem(key(userId)) ?? 'null');
    if (!Array.isArray(json)) return [];
    return json.filter(
      (e): e is HouseholdEntry =>
        typeof e === 'object' &&
        e !== null &&
        typeof e.household?.id === 'string' &&
        typeof e.household?.name === 'string' &&
        (e.role === 'owner' || e.role === 'member'),
    );
  } catch {
    return [];
  }
}

export function writeHouseholds(userId: string, list: HouseholdEntry[]): void {
  try {
    localStorage.setItem(key(userId), JSON.stringify(list));
  } catch {
    // Storage blocked (private mode): the switcher just isn't available offline.
  }
}

export function clearHouseholds(userId: string): void {
  try {
    localStorage.removeItem(key(userId));
  } catch {
    // Storage blocked: nothing was stored.
  }
}
