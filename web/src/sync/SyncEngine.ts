import type { ApiClient } from '../core/api/apiClient.ts';
import type { HouseholdDb } from './db.ts';
import { FEED_DAYS, FEED_LIMIT, windowStart } from '../core/HomeFeed.ts';
import { createContext, createElement, useContext, type ReactNode } from 'react';

/** What the screens call to ask for fresh data. SHOP-08's full push/pull loop replaces `ActivityPull` behind this seam. */
export interface SyncEngine {
  kick(): Promise<void>;
}

type PullDeps = {
  api: Pick<ApiClient, 'fetchActivity'>;
  householdId: string;
  /** The household's open database, or `null` while none is open. */
  getDb: () => HouseholdDb | null;
  isOnline: () => boolean;
  now?: () => Date;
};

/**
 * Interim engine (CORE-08, until SHOP-08): pulls only `activity` into the local database. Concurrent kicks share
 * one pull; offline it does nothing, so the cached rows stay (SPEC §6.5).
 */
export function createActivityPull({ api, householdId, getDb, isOnline, now = () => new Date() }: PullDeps): SyncEngine {
  let inflight: Promise<void> | null = null;
  async function pull(): Promise<void> {
    const db = getDb();
    if (!db || !db.isOpen()) return;
    const since = windowStart(now(), FEED_DAYS);
    const result = await api.fetchActivity(householdId, since.toISOString(), FEED_LIMIT);
    // A failed read leaves the cache as it was; the database may have been closed by a switch meanwhile.
    if (!result.ok || !db.isOpen()) return;
    // The read is live rows only, so a cached row inside the window that the page omits was soft-deleted on the
    // server (SPEC §6.3). Replace the window with the page, an empty page included.
    await db.transaction('rw', db.activity, async () => {
      const stale = await db.activity
        .where('household_id')
        .equals(householdId)
        .filter((r) => new Date(r.created_at) >= since)
        .primaryKeys();
      await db.activity.bulkDelete(stale);
      await db.activity.bulkPut(result.value);
    });
  }
  return {
    kick() {
      if (!isOnline()) return Promise.resolve();
      if (!inflight) {
        inflight = pull()
          .catch(() => undefined)
          .finally(() => {
            inflight = null;
          });
      }
      return inflight;
    },
  };
}

const NOOP: SyncEngine = { kick: () => Promise.resolve() };
const SyncContext = createContext<SyncEngine>(NOOP);

export function SyncProvider({ engine, children }: { engine: SyncEngine; children: ReactNode }) {
  return createElement(SyncContext.Provider, { value: engine }, children);
}

export function useSync(): SyncEngine {
  return useContext(SyncContext);
}
