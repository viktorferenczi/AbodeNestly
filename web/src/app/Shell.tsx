import { Outlet } from 'react-router';
import { useAuth } from '../core/auth/AuthProvider.tsx';
import { TopBar } from '../ui/TopBar.tsx';
import styles from './Shell.module.css';

// Authenticated layout. Tabs, household switcher and sync status arrive with later CORE stories.
export function Shell() {
  const { profile } = useAuth();
  return (
    <div className={styles.shell}>
      <TopBar title="AbodeNestly" trailing={profile?.display_name} />
      <main className={styles.main}>
        <Outlet />
      </main>
    </div>
  );
}
