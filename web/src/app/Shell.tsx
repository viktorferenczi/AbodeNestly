import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { Outlet } from 'react-router';
import { useAuth } from '../core/auth/AuthProvider.tsx';
import { HouseholdProvider, useHousehold } from '../core/household/HouseholdProvider.tsx';
import { useApi } from '../core/api/ApiProvider.tsx';
import { Switcher } from '../core/Switcher.tsx';
import { createActivityPull, SyncProvider } from '../sync/SyncEngine.ts';
import { TopBar } from '../ui/TopBar.tsx';
import styles from './Shell.module.css';
import { TabBar } from './TabBar.tsx';

function Tabs() {
  const { status } = useHousehold();
  return status === 'ready' ? <TabBar /> : null;
}

function Bar() {
  const { profile } = useAuth();
  const { household } = useHousehold();
  const [open, setOpen] = useState(false);
  return (
    <>
      <TopBar title={household?.name ?? 'AbodeNestly'} trailing={profile?.display_name} onTitleClick={() => setOpen(true)} titleExpanded={open} />
      {open ? <Switcher onClose={() => setOpen(false)} /> : null}
    </>
  );
}

// Provides the interim sync engine (CORE-08) and kicks it on the triggers SPEC §6.4 names: opening the household,
// coming back to the tab and reconnecting. SHOP-08 swaps the engine for the full loop.
function HouseholdSync({ children }: { children: ReactNode }) {
  const api = useApi();
  const { db, household } = useHousehold();
  const householdId = household?.id;
  const engine = useMemo(
    () =>
      householdId && db
        ? createActivityPull({ api, householdId, getDb: () => db, isOnline: () => navigator.onLine })
        : { kick: () => Promise.resolve() },
    [api, db, householdId],
  );
  useEffect(() => {
    void engine.kick();
    const onVisible = () => {
      if (document.visibilityState === 'visible') void engine.kick();
    };
    const onOnline = () => void engine.kick();
    document.addEventListener('visibilitychange', onVisible);
    window.addEventListener('online', onOnline);
    return () => {
      document.removeEventListener('visibilitychange', onVisible);
      window.removeEventListener('online', onOnline);
    };
  }, [engine]);
  return <SyncProvider engine={engine}>{children}</SyncProvider>;
}

// Authenticated layout. Sync status arrives with later stories.
export function Shell() {
  return (
    <HouseholdProvider>
      <HouseholdSync>
        <div className={styles.shell}>
          <Bar />
          <main className={styles.main}>
            <Outlet />
          </main>
          <Tabs />
        </div>
      </HouseholdSync>
    </HouseholdProvider>
  );
}
