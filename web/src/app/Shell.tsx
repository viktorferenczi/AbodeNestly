import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { Link, Outlet } from 'react-router';
import { useAuth } from '../core/auth/AuthProvider.tsx';
import { HouseholdProvider, useHousehold } from '../core/household/HouseholdProvider.tsx';
import { useApi } from '../core/api/ApiProvider.tsx';
import { useRealtimeClient } from '../core/realtime/RealtimeProvider.tsx';
import { subscribeShoppingRealtime } from '../shopping/Realtime.ts';
import { Switcher } from '../core/Switcher.tsx';
import { createSyncEngine, NOOP_SYNC, SyncProvider } from '../sync/SyncEngine.ts';
import { SyncStatusChip } from '../sync/SyncStatusChip.tsx';
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
  const { status, household } = useHousehold();
  const [open, setOpen] = useState(false);
  return (
    <>
      <TopBar title={household?.name ?? 'AbodeNestly'} trailing={
          <>
            <span className={styles.name}>{profile?.display_name}</span>
            {status === 'ready' ? <SyncStatusChip /> : null}
            {status === 'ready' ? (
              <Link to="/search" aria-label="Search" className={styles.search}>
                🔍
              </Link>
            ) : null}
          </>
        } onTitleClick={() => setOpen(true)} titleExpanded={open} />
      {open ? <Switcher onClose={() => setOpen(false)} /> : null}
    </>
  );
}

// Provides the sync engine (SHOP-08) and kicks it on the SPEC §6.4 triggers available here: opening the
// household, coming back to the tab, reconnecting, and every 5 minutes while the tab is visible. Writes kick it
// themselves (`mutate`). The Realtime `postgres_changes` kick (SHOP-09) is the effect below; it only kicks, the pull applies data.
function HouseholdSync({ children }: { children: ReactNode }) {
  const api = useApi();
  const { db, household, modules } = useHousehold();
  const realtime = useRealtimeClient();
  const householdId = household?.id;
  const engine = useMemo(
    () =>
      householdId && db
        ? createSyncEngine({ api, householdId, getDb: () => db, isOnline: () => navigator.onLine })
        : NOOP_SYNC,
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
  const shopping = modules.shopping;
  useEffect(() => {
    if (!realtime || !householdId) return;
    return subscribeShoppingRealtime({ client: realtime, householdId, modules: { shopping }, onChange: () => void engine.kick() });
  }, [engine, realtime, householdId, shopping]);
  return <SyncProvider engine={engine}>{children}</SyncProvider>;
}

// Authenticated layout.
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
