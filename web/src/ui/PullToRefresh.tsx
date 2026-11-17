import { useRef, useState, type PointerEvent, type ReactNode } from 'react';
import styles from './PullToRefresh.module.css';

// Damped pull distance (px) past which a release refreshes.
const THRESHOLD = 32;
const MAX_PULL = 96;

type Props = { onRefresh: () => void | Promise<void>; children: ReactNode };

// Pointer-event pull gesture: starts only at the top of the page, calls `onRefresh` when released past the threshold.
export function PullToRefresh({ onRefresh, children }: Props) {
  const startY = useRef<number | null>(null);
  const [pull, setPull] = useState(0);
  const [refreshing, setRefreshing] = useState(false);

  const reset = () => {
    startY.current = null;
    setPull(0);
  };

  function down(e: PointerEvent) {
    if (refreshing || window.scrollY > 0) return;
    startY.current = e.clientY;
  }

  function move(e: PointerEvent) {
    if (startY.current === null) return;
    const dy = e.clientY - startY.current;
    // Damped so the indicator trails the finger.
    setPull(dy > 0 ? Math.min(MAX_PULL, dy / 2) : 0);
  }

  async function up() {
    const pulled = pull >= THRESHOLD;
    reset();
    if (!pulled) return;
    setRefreshing(true);
    try {
      await onRefresh();
    } finally {
      setRefreshing(false);
    }
  }

  return (
    <div className={styles.area} onPointerDown={down} onPointerMove={move} onPointerUp={up} onPointerCancel={reset}>
      <div className={styles.indicator} style={{ height: refreshing ? 32 : pull }} aria-live="polite" role="status">
        {refreshing ? 'Refreshing…' : pull >= THRESHOLD ? 'Release to refresh' : ''}
      </div>
      {children}
    </div>
  );
}
