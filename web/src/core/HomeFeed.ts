import type { Activity } from '@abodenestly/contract';
import type { Modules } from './household/HouseholdProvider.tsx';

export const FEED_DAYS = 30;
export const FEED_LIMIT = 20;
const DAY_MS = 24 * 60 * 60 * 1000;

export function windowStart(now: Date, days: number): Date {
  return new Date(now.getTime() - days * DAY_MS);
}

/** DESIGN B4: the newest 20 live rows of the last 30 days (CORE-08 AC3 of the db story, same window as the query). */
export function homeFeed(rows: readonly Activity[], now: Date): Activity[] {
  const from = windowStart(now, FEED_DAYS).getTime();
  return rows
    .filter((r) => r.deleted_at === null && Date.parse(r.created_at) >= from)
    // Same-instant rows keep a stable order across clients.
    .sort((a, b) => Date.parse(b.created_at) - Date.parse(a.created_at) || (a.id < b.id ? 1 : a.id > b.id ? -1 : 0))
    .slice(0, FEED_LIMIT);
}

export type HomeState = 'allModulesOff' | 'empty' | 'populated';

/** `feed` is the windowed list. All-off wins: the settings prompt shows and the feed still renders under it. */
export function homeState(feed: readonly Activity[], modules: Modules): HomeState {
  if (!modules.shopping && !modules.pantry && !modules.maintenance) return 'allModulesOff';
  return feed.length === 0 ? 'empty' : 'populated';
}
