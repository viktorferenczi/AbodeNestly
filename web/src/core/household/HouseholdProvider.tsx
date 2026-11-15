import type { Household, Role } from '@abodenestly/contract';
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { useLocation, useNavigate } from 'react-router';
import { dbName, openHouseholdDb, type HouseholdDb } from '../../sync/db.ts';
import { useApi } from '../api/ApiProvider.tsx';
import { useAuth } from '../auth/AuthProvider.tsx';
import { readLastHousehold, writeLastHousehold } from '../auth/lastHousehold.ts';
import { readHouseholds } from '../householdsCache.ts';
import { switchTargetPath } from '../Switcher.ts';

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
  /** The open local database of the current household (`null` while none is open or during a switch). */
  db: HouseholdDb | null;
  switching: boolean;
  /** Closes the current database, opens the target's, and lands on the same tab. Needs no network (SPEC §6.1). */
  switchTo(id: string): Promise<void>;
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
  const navigate = useNavigate();
  const [db, setDb] = useState<HouseholdDb | null>(null);
  const [switching, setSwitching] = useState(false);
  const dbRef = useRef<HouseholdDb | null>(null);
  const pathRef = useRef(location.pathname + location.search);
  pathRef.current = location.pathname + location.search;

  // Opens the household's database; switchTo may have opened it already, in which case it is adopted.
  useEffect(() => {
    if (!householdId) return;
    let active = true;
    let mine: HouseholdDb | null = null;
    const existing = dbRef.current;
    if (existing && existing.name === dbName(householdId) && existing.isOpen()) {
      mine = existing;
    } else {
      openHouseholdDb(householdId)
        .then((opened) => {
          if (!active) return opened.close();
          mine = opened;
          dbRef.current = opened;
          setDb(opened);
        })
        // IndexedDB blocked (private mode): the household still renders from its cached copy.
        .catch(() => undefined);
    }
    return () => {
      active = false;
      mine?.close();
      if (dbRef.current === mine) dbRef.current = null;
    };
  }, [householdId]);

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

  const switchTo = useCallback(
    async (id: string) => {
      if (!userId || id === householdId) return;
      setSwitching(true);
      const previous = dbRef.current;
      try {
        previous?.close();
        dbRef.current = null;
        setDb(null);
        const opened = await openHouseholdDb(id);
        dbRef.current = opened;
        setDb(opened);
        writeLastHousehold(id);
        // The cached copy, not the network: switching must work offline (AC5).
        const entry = readHouseholds(userId).find((e) => e.household.id === id) ?? readCache(userId, id);
        if (entry) {
          writeCache(userId, id, entry);
          setLoaded({ id, status: 'ready', household: entry.household, role: entry.role });
        }
        void navigate(entry ? switchTargetPath(pathRef.current, entry.household) : pathRef.current, { replace: true });
      } catch (error) {
        // Could not open the target: put the current household back so the app is not left without a database.
        if (previous && householdId) {
          const back = await openHouseholdDb(householdId).catch(() => null);
          dbRef.current = back;
          setDb(back);
        }
        throw error;
      } finally {
        setSwitching(false);
      }
    },
    [userId, householdId, navigate],
  );

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
      db,
      switching,
      switchTo,
    };
  }, [householdId, loaded, replace, reload, db, switching, switchTo]);

  return <HouseholdContext.Provider value={value}>{children}</HouseholdContext.Provider>;
}

export function useHousehold(): HouseholdState {
  const value = useContext(HouseholdContext);
  if (!value) throw new Error('useHousehold must be used inside <HouseholdProvider>');
  return value;
}
