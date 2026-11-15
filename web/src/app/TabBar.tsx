import { NavLink } from 'react-router';
import { useHousehold } from '../core/household/HouseholdProvider.tsx';
import { visibleTabs } from './tabs.ts';
import styles from './TabBar.module.css';

export function TabBar() {
  const { modules } = useHousehold();
  return (
    <nav aria-label="Main" className={styles.bar}>
      {visibleTabs(modules).map((tab) => (
        <NavLink key={tab.key} to={tab.to} className={({ isActive }) => (isActive ? `${styles.tab} ${styles.active}` : styles.tab)}>
          {tab.label}
        </NavLink>
      ))}
    </nav>
  );
}
