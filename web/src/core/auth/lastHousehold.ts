// Written by Welcome, Landing and the household switcher (CORE-07).
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

export function clearLastHousehold(): void {
  try {
    localStorage.removeItem(KEY);
  } catch {
    // Storage blocked (private mode): nothing was stored to clear.
  }
}
