import type { Household, Role } from '@abodenestly/contract';
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { useLocation } from 'react-router';
import { useApi } from '../api/ApiProvider.tsx';
import { useAuth } from '../auth/AuthProvider.tsx';
import { readLastHousehold } from '../auth/lastHousehold.ts';

export type Modules = { shopping: boolean; pantry: boolean; maintenance: boolean };
type Cached = { household: Household; role: Role };

// Keyed by user too: a different account on the same browser must not inherit this one's role.
const cacheKey = (userId: string, id: string) => `abodenestly:household:${userId}:${id}`;

function readCache(userId: string, id: string): Cached | null {
  try {
    const raw = localStorage.getItem(cacheKey(userId, id));
    return raw ? (JSON.parse(raw) as Cached) : null;
  } catch {
    return null;
  }
}

function writeCache(userId: string, id: string, value: Cached): void {
  try {
    localStorage.setItem(cacheKey(userId, id), JSON.stringify(value));
  } catch {
    // Storage blocked (private mode): settings just aren't readable offline.
  }
}

export function clearCachedHousehold(userId: string, id: string): void {
  try {
    localStorage.removeItem(cacheKey(userId, id));
  } catch {
    // Storage blocked: nothing was stored.
  }
}

export function modulesOf(household: Pick<Household, 'module_shopping' | 'module_pantry' | 'module_maintenance'>): Modules {
  return {
    shopping: household.module_shopping,
    pantry: household.module_pantry,
    maintenance: household.module_maintenance,
  };
}

const NO_MODULES: Modules = { shopping: false, pantry: false, maintenance: false };

export type HouseholdState = {
  status: 'loading' | 'ready' | 'none';
  household: Household | null;
  role: Role | null;
  modules: Modules;
  /** Swaps in a household the caller just saved, so tabs and settings update without a reload. */
  replace(household: Household): void;
  reload(): void;
};

type Loaded = { id: string; status: 'ready' | 'none'; household: Household | null; role: Role | null };

const HouseholdContext = createContext<HouseholdState | null>(null);

// Holds the active household for the shell. Until the Dexie layer exists the last good read is cached in
// localStorage (SPEC §6.5), so settings stay readable offline.
export function HouseholdProvider({ children }: { children: ReactNode }) {
  const api = useApi();
  const { session } = useAuth();
  const userId = session?.user.id;
  // Welcome and Landing write the last household, then navigate, so a location change picks it up.
  const location = useLocation();
  const householdId = useMemo(() => readLastHousehold(), [location]);
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    if (!householdId || !userId) return;
    let active = true;
    const cached = readCache(userId, householdId);
    if (cached) setLoaded({ id: householdId, status: 'ready', ...cached });
    void api.fetchHousehold(householdId, userId).then((result) => {
      if (!active) return;
      if (!result.ok) {
        // Offline or failed read: the cached copy (if any) stays.
        if (!cached) setLoaded({ id: householdId, status: 'none', household: null, role: null });
        return;
      }
      if (!result.value) {
        clearCachedHousehold(userId, householdId);
        return setLoaded({ id: householdId, status: 'none', household: null, role: null });
      }
      writeCache(userId, householdId, result.value);
      setLoaded({ id: householdId, status: 'ready', ...result.value });
    });
    return () => {
      active = false;
    };
  }, [api, householdId, userId, attempt]);

  const replace = useCallback(
    (household: Household) => {
      setLoaded((prev) => {
        if (!prev || prev.status !== 'ready' || !prev.role || !userId) return prev;
        writeCache(userId, prev.id, { household, role: prev.role });
        return { ...prev, household };
      });
    },
    [userId],
  );
  const reload = useCallback(() => setAttempt((n) => n + 1), []);

  const value = useMemo<HouseholdState>(() => {
    const current = householdId && loaded?.id === householdId ? loaded : null;
    const status = !householdId ? 'none' : (current?.status ?? 'loading');
    const household = current?.household ?? null;
    return {
      status,
      household,
      role: current?.role ?? null,
      modules: household ? modulesOf(household) : NO_MODULES,
      replace,
      reload,
    };
  }, [householdId, loaded, replace, reload]);

  return <HouseholdContext.Provider value={value}>{children}</HouseholdContext.Provider>;
}

export function useHousehold(): HouseholdState {
  const value = useContext(HouseholdContext);
  if (!value) throw new Error('useHousehold must be used inside <HouseholdProvider>');
  return value;
}
