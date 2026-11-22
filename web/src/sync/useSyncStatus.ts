import { useSyncExternalStore } from 'react';
import { useHousehold } from '../core/household/HouseholdProvider.tsx';
import { useLiveQuery } from '../ui/useLiveQuery.ts';
import { useOnline } from '../ui/useOnline.ts';
import { PUSHABLE, useSync, type SyncPhase } from './SyncEngine.ts';

export type ChipState = { kind: 'idle' } | { kind: 'syncing' } | { kind: 'offline' } | { kind: 'pending'; count: number };
export type SyncStatus = { phase: SyncPhase; online: boolean; pending: number; failed: number; chip: ChipState };

/** DESIGN A6 precedence: syncing, then offline, then "N to sync" (queued plus failed), else hidden. */
export function chipState(phase: SyncPhase, online: boolean, pending: number, failed: number): ChipState {
  if (phase === 'syncing') return { kind: 'syncing' };
  if (!online || phase === 'offline') return { kind: 'offline' };
  if (pending + failed > 0) return { kind: 'pending', count: pending + failed };
  return { kind: 'idle' };
}

export function useSyncStatus(): SyncStatus {
  const sync = useSync();
  const { db } = useHousehold();
  const phase = useSyncExternalStore(sync.subscribe, sync.getPhase, () => 'idle' as const);
  const online = useOnline();
  // Only rows the loop will send count: rows held back for tables the server does not accept yet are not sync debt.
  const pending = useLiveQuery(async () => (db ? db.outbox.filter((r) => PUSHABLE.has(r.table)).count() : 0), [db]) ?? 0;
  const failed = useLiveQuery(async () => (db ? db.outbox_failed.count() : 0), [db]) ?? 0;
  return { phase, online, pending, failed, chip: chipState(phase, online, pending, failed) };
}
