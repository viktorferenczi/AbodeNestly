import type { Modules } from '../core/household/HouseholdProvider.tsx';

export type Tab = { key: string; label: string; to: string };

// DESIGN A6 order (Shopping, Pantry, Maintenance, Home). Profile is last: B7 is a tab-level route.
const ALL_TABS: Array<Tab & { module?: keyof Modules }> = [
  { key: 'shopping', label: 'Shopping', to: '/shopping', module: 'shopping' },
  { key: 'pantry', label: 'Pantry', to: '/pantry', module: 'pantry' },
  { key: 'maintenance', label: 'Maintenance', to: '/maintenance', module: 'maintenance' },
  { key: 'home', label: 'Home', to: '/home' },
  { key: 'profile', label: 'Profile', to: '/profile' },
];

/** Home and Profile always show; a module tab shows only while its flag is on. */
export function visibleTabs(modules: Modules): Tab[] {
  return ALL_TABS.filter((t) => !t.module || modules[t.module]).map(({ key, label, to }) => ({ key, label, to }));
}
