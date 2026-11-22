import type { SyncRejection } from '@abodenestly/contract';
import { useHousehold } from '../core/household/HouseholdProvider.tsx';
import { Sheet } from '../ui/Sheet.tsx';
import { useLiveQuery } from '../ui/useLiveQuery.ts';
import { useSync } from './SyncEngine.ts';
import { useSyncStatus } from './useSyncStatus.ts';
import styles from './SyncSheet.module.css';

const REASON: Record<SyncRejection['reason'], string> = {
  rls_denied: 'not allowed',
  validation: 'invalid',
  unknown_table: 'unknown',
  module_disabled: 'module turned off',
};
const ACTION: Record<string, string> = { upsert: 'save', delete: 'delete', delta: 'adjust' };

// DESIGN B12. Household-wide (it also shows core writes), so it lives in sync/ rather than shopping/.
export function SyncSheet({ onClose }: { onClose: () => void }) {
  const sync = useSync();
  const { db } = useHousehold();
  const { phase, online, pending, failed: failedCount } = useSyncStatus();
  const failed = useLiveQuery(async () => (db ? db.outbox_failed.orderBy('seq').toArray() : []), [db]) ?? [];
  const status =
    phase === 'syncing'
      ? 'Syncing…'
      : !online || phase === 'offline'
        ? "You're offline. Changes sync when you reconnect."
        : pending + failedCount > 0
          ? 'Changes are waiting to sync.'
          : 'Everything is synced.';
  return (
    <Sheet
      title="Sync"
      onClose={onClose}
      footer={
        <button type="button" className={styles.primary} onClick={() => void sync.kick()}>
          Sync now
        </button>
      }
    >
      <p className={styles.status}>{status}</p>
      <p>Pending changes: {pending}</p>
      <p>Failed changes: {failed.length}</p>
      {failed.length > 0 ? (
        <ul className={styles.list} aria-label="Failed changes">
          {failed.map((row) => {
            const what = `${row.table} ${ACTION[row.op] ?? row.op}`;
            return (
              <li key={row.seq} className={styles.row}>
                <span className={styles.what}>
                  {row.table} · {ACTION[row.op] ?? row.op} · Couldn’t save: {REASON[row.reason]}
                </span>
                <span className={styles.actions}>
                  <button type="button" aria-label={`Retry ${what}`} onClick={() => void sync.retry(row.seq)}>
                    Retry
                  </button>
                  <button type="button" aria-label={`Discard ${what}`} onClick={() => void sync.discard(row.seq)}>
                    Discard
                  </button>
                </span>
              </li>
            );
          })}
        </ul>
      ) : null}
    </Sheet>
  );
}
