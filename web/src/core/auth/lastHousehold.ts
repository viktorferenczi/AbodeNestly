// CORE-07 (household switcher) will write this key too.
const KEY = 'abodenestly:lastHousehold';

export function readLastHousehold(): string | null {
  try {
    return localStorage.getItem(KEY);
  } catch {
    return null;
  }
}

export function writeLastHousehold(id: string): void {
  try {
    localStorage.setItem(KEY, id);
  } catch {
    // Storage blocked (private mode): the earliest-joined fallback still applies.
  }
}
