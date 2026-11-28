import type { Mutation, SyncPullResponse, SyncRejection } from '@abodenestly/contract';
import { createContext, createElement, useContext, type ReactNode } from 'react';
import type { ApiClient } from '../core/api/apiClient.ts';
import { readCursor, writeCursor, type HouseholdDb, type OutboxFailedRow, type OutboxRow } from './db.ts';

/** `offline` is only what the last run saw; the chip also reads `navigator.onLine`. */
export type SyncPhase = 'idle' | 'syncing' | 'offline';

/** What the screens call to ask for fresh data, and what the sync sheet calls to recover failed changes. */
export interface SyncEngine {
  kick(): Promise<void>;
  /** Puts a failed change back at its old place in the outbox and syncs. */
  retry(seq: number): Promise<void>;
  /** Drops a failed change and, unless another change targets the row, the local copy; the server's version is pulled back. */
  discard(seq: number): Promise<void>;
  getPhase(): SyncPhase;
  subscribe(listener: () => void): () => void;
}

/** Tables the server accepts mutations for (api/_lib/sync/tables.ts). Other queued rows wait until their `-api` story adds them to both lists. */
export const PUSHABLE: ReadonlySet<string> = new Set(['store', 'shopping_list', 'list_item', 'location', 'item_price']);

const PAGE = 500;
const RETRYABLE_DELAY_MS = 1000;
/** Local-only tables a pull must never write to. */
const LOCAL_ONLY = new Set(['outbox', 'outbox_failed', 'meta', 'attachment_outbox']);

type Deps = {
  api: Pick<ApiClient, 'syncPush' | 'syncPull' | 'refreshSession'>;
  householdId: string;
  /** The household's open database, or `null` while none is open. */
  getDb: () => HouseholdDb | null;
  isOnline: () => boolean;
  sleep?: (ms: number) => Promise<void>;
  now?: () => Date;
};

type Step = 'ok' | 'network' | 'stop';

const mutationOf = (payload: unknown): Mutation => payload as Mutation;

export function createSyncEngine({
  api,
  householdId,
  getDb,
  isOnline,
  sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
  now = () => new Date(),
}: Deps): SyncEngine {
  let phase: SyncPhase = 'idle';
  const listeners = new Set<() => void>();
  let inflight: Promise<void> | null = null;
  let again = false;
  /** Mutation ids that already had their one retry after a token refresh. */
  const refreshed = new Set<string>();

  function setPhase(next: SyncPhase) {
    if (phase === next) return;
    phase = next;
    listeners.forEach((l) => l());
  }

  async function push(db: HouseholdDb): Promise<Step> {
    let retriedBusy = false;
    for (;;) {
      const rows = await db.outbox
        .orderBy('seq')
        .filter((r) => PUSHABLE.has(r.table))
        .limit(PAGE)
        .toArray();
      if (rows.length === 0) return 'ok';
      const mutations = rows.map((r) => mutationOf(r.payload));
      const result = await api.syncPush({ household_id: householdId, mutations });
      if (!result.ok) {
        if (result.error.code === 'network') return 'network';
        if (result.error.code === 'retryable' && !retriedBusy) {
          retriedBusy = true;
          await sleep(RETRYABLE_DELAY_MS);
          continue;
        }
        return 'stop';
      }
      const applied = new Set(result.value.applied);
      const rejected = new Map<string, SyncRejection>(result.value.rejected.map((r) => [r.id, r]));
      let needsRefresh = false;
      const done: number[] = [];
      const failed: OutboxFailedRow[] = [];
      rows.forEach((row, i) => {
        const id = mutations[i]!.id;
        const rejection = rejected.get(id);
        if (applied.has(id)) done.push(row.seq!);
        else if (rejection && rejection.reason === 'rls_denied' && !refreshed.has(id)) {
          refreshed.add(id);
          needsRefresh = true;
        } else if (rejection) {
          failed.push({ seq: row.seq!, table: row.table, op: row.op, payload: row.payload, reason: rejection.reason, detail: rejection.detail, failed_at: now().toISOString() });
          done.push(row.seq!);
        }
      });
      if (done.length === 0 && !needsRefresh) return 'stop'; // the server answered for none of them: do not spin
      await db.transaction('rw', db.outbox, db.outbox_failed, async () => {
        await db.outbox_failed.bulkPut(failed);
        await db.outbox.bulkDelete(done);
      });
      if (needsRefresh) await api.refreshSession();
    }
  }

  async function pull(db: HouseholdDb): Promise<Step> {
    for (;;) {
      const since = await readCursor(db);
      const result = await api.syncPull(householdId, since, PAGE);
      if (!result.ok) return result.error.code === 'network' ? 'network' : 'stop';
      if (!db.isOpen()) return 'stop'; // the household was switched while the page was in flight
      await applyPage(db, result.value);
      if (!result.value.has_more) return 'ok';
    }
  }

  async function applyPage(db: HouseholdDb, page: SyncPullResponse): Promise<void> {
    const known = db.tables.map((t) => t.name);
    // A table the client does not know yet is skipped; its rows arrive after the client learns it (cursor reset rule in db.ts).
    const names = Object.keys(page.changes).filter((n) => known.includes(n) && !LOCAL_ONLY.has(n));
    await db.transaction('rw', [...names.map((n) => db.table(n)), db.outbox, db.outbox_failed, db.meta], async () => {
      // A row with a change still queued or failed keeps its local value; the server's copy arrives after the push lands.
      const queued = new Set<string>();
      for (const row of [...(await db.outbox.toArray()), ...(await db.outbox_failed.toArray())]) {
        queued.add(`${row.table}:${mutationOf(row.payload).row_id}`);
      }
      for (const name of names) {
        const rows = (page.changes[name as keyof typeof page.changes] ?? []) as Array<{ id?: string }>;
        await db.table(name).bulkPut(rows.filter((r) => !(r.id && queued.has(`${name}:${r.id}`))));
      }
      await writeCursor(db, page.cursor);
    });
  }

  async function run(): Promise<void> {
    const db = getDb();
    if (!db || !db.isOpen()) return;
    if (!isOnline()) return setPhase('offline');
    setPhase('syncing');
    try {
      let step = await push(db);
      // A pull after a push that never reached the server would only hide what is still queued.
      if (step === 'ok') step = await pull(db);
      setPhase(step === 'network' ? 'offline' : 'idle');
    } catch {
      // A closed database (household switch) or an unexpected fetch error ends this run; the next trigger starts a new one.
      setPhase('idle');
    }
  }

  function kick(): Promise<void> {
    if (inflight) {
      again = true;
      return inflight;
    }
    inflight = (async () => {
      do {
        again = false;
        await run();
      } while (again);
    })().finally(() => {
      inflight = null;
    });
    return inflight;
  }

  async function retry(seq: number): Promise<void> {
    const db = getDb();
    if (!db || !db.isOpen()) return;
    await db.transaction('rw', db.outbox, db.outbox_failed, async () => {
      const row = await db.outbox_failed.get(seq);
      if (!row) return;
      // Same mutation id: the server does not remember rejected ids, so the retry is a fresh attempt.
      const entry: OutboxRow = { seq, table: row.table, op: row.op, payload: row.payload };
      await db.outbox.put(entry);
      await db.outbox_failed.delete(seq);
      refreshed.delete(mutationOf(row.payload).id);
    });
    await kick();
  }

  async function discard(seq: number): Promise<void> {
    const db = getDb();
    if (!db || !db.isOpen()) return;
    await db.transaction('rw', [...db.tables], async () => {
      const row = await db.outbox_failed.get(seq);
      if (!row) return;
      await db.outbox_failed.delete(seq);
      const { row_id } = mutationOf(row.payload);
      const others = [...(await db.outbox.toArray()), ...(await db.outbox_failed.toArray())];
      if (others.some((o) => o.table === row.table && mutationOf(o.payload).row_id === row_id)) return;
      // Nothing else targets the row, so the local copy only exists because of the rejected change: drop it and
      // pull everything again so the server's version, if there is one, comes back.
      if (PUSHABLE.has(row.table)) await db.table(row.table).delete(row_id);
      await writeCursor(db, 0);
    });
    await kick();
  }

  return {
    kick,
    retry,
    discard,
    getPhase: () => phase,
    subscribe(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
  };
}

export const NOOP_SYNC: SyncEngine = {
  kick: () => Promise.resolve(),
  retry: () => Promise.resolve(),
  discard: () => Promise.resolve(),
  getPhase: () => 'idle',
  subscribe: () => () => undefined,
};
const SyncContext = createContext<SyncEngine>(NOOP_SYNC);

export function SyncProvider({ engine, children }: { engine: SyncEngine; children: ReactNode }) {
  return createElement(SyncContext.Provider, { value: engine }, children);
}

export function useSync(): SyncEngine {
  return useContext(SyncContext);
}
