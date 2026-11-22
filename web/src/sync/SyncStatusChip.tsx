import { useState } from 'react';
import { SyncSheet } from './SyncSheet.tsx';
import { useSyncStatus } from './useSyncStatus.ts';
import styles from './SyncStatusChip.module.css';

// DESIGN A6: hidden when idle; tapping opens the sync sheet (B12).
export function SyncStatusChip() {
  const { chip } = useSyncStatus();
  const [open, setOpen] = useState(false);
  const label = chip.kind === 'syncing' ? 'Syncing' : chip.kind === 'offline' ? 'Offline' : chip.kind === 'pending' ? `${chip.count} to sync` : null;
  return (
    <>
      <span aria-live="polite" className={styles.live}>
        {label ? (
          <button type="button" className={styles.chip} onClick={() => setOpen(true)} aria-haspopup="dialog">
            {chip.kind === 'syncing' ? <span className={styles.spinner} aria-hidden="true" /> : <span aria-hidden="true">{chip.kind === 'offline' ? '☁︎✕' : '☁︎↑'}</span>}
            {label}
          </button>
        ) : null}
      </span>
      {open ? <SyncSheet onClose={() => setOpen(false)} /> : null}
    </>
  );
}
