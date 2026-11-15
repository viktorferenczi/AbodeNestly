import { useState } from 'react';
import { Outlet } from 'react-router';
import { useAuth } from '../core/auth/AuthProvider.tsx';
import { HouseholdProvider, useHousehold } from '../core/household/HouseholdProvider.tsx';
import { Switcher } from '../core/Switcher.tsx';
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
      <TopBar title={household?.name ?? 'AbodeNestly'} trailing={profile?.display_name} onTitleClick={() => setOpen(true)} />
      {open ? <Switcher onClose={() => setOpen(false)} /> : null}
    </>
  );
}

// Authenticated layout. Sync status arrives with later stories.
export function Shell() {
  return (
    <HouseholdProvider>
      <div className={styles.shell}>
        <Bar />
        <main className={styles.main}>
          <Outlet />
        </main>
        <Tabs />
      </div>
    </HouseholdProvider>
  );
}
