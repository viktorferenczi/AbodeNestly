import { Outlet } from 'react-router';
import { useAuth } from '../core/auth/AuthProvider.tsx';
import { HouseholdProvider, useHousehold } from '../core/household/HouseholdProvider.tsx';
import { TopBar } from '../ui/TopBar.tsx';
import styles from './Shell.module.css';
import { TabBar } from './TabBar.tsx';

function Tabs() {
  const { status } = useHousehold();
  return status === 'ready' ? <TabBar /> : null;
}

// Authenticated layout. Household switcher and sync status arrive with later CORE stories.
export function Shell() {
  const { profile } = useAuth();
  return (
    <HouseholdProvider>
      <div className={styles.shell}>
        <TopBar title="AbodeNestly" trailing={profile?.display_name} />
        <main className={styles.main}>
          <Outlet />
        </main>
        <Tabs />
      </div>
    </HouseholdProvider>
  );
}
