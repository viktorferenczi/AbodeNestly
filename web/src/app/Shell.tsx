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

const PULL_INTERVAL_MS = 5 * 60 * 1000;

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

// Provides the interim sync engine (CORE-08) and kicks it on the SPEC §6.4 triggers available here: opening the
// household, coming back to the tab, reconnecting, and every 5 minutes while the tab is visible. The Realtime
// `postgres_changes` kick is not wired: it arrives with SHOP-08's channel subscription, which replaces this engine.
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
    const timer = window.setInterval(onVisible, PULL_INTERVAL_MS);
    document.addEventListener('visibilitychange', onVisible);
    window.addEventListener('online', onOnline);
    return () => {
      window.clearInterval(timer);
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
