import { startTab, type LandingHousehold } from './auth/landing.ts';

export type SwitcherState = 'one-household' | 'many' | 'switching';

const TAB_MODULE: Record<string, keyof Omit<LandingHousehold, 'id'>> = {
  '/shopping': 'module_shopping',
  '/pantry': 'module_pantry',
  '/maintenance': 'module_maintenance',
};

/** Same tab in the target household; a tab whose module is off there falls back to its start tab. */
export function switchTargetPath(currentPath: string, target: LandingHousehold): string {
  const tab = '/' + (currentPath.split(/[?#]/)[0] ?? '').split('/')[1];
  const flag = TAB_MODULE[tab];
  if (flag && !target[flag]) return startTab(target);
  return currentPath;
}

export function switcherState(households: readonly unknown[], switching: boolean): SwitcherState {
  if (switching) return 'switching';
  return households.length > 1 ? 'many' : 'one-household';
}
