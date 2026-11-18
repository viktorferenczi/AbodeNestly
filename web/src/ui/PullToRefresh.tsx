import { useEffect, useRef, useState, type PointerEvent, type ReactNode } from 'react';
import styles from './PullToRefresh.module.css';

// Damped pull distance (px) past which a release refreshes.
const THRESHOLD = 32;
const MAX_PULL = 96;

type Props = { onRefresh: () => void | Promise<void>; children: ReactNode };

// Pointer-event pull gesture: starts only at the top of the page, calls `onRefresh` when released past the threshold.
export function PullToRefresh({ onRefresh, children }: Props) {
  const startY = useRef<number | null>(null);
  // The latest distance, read on release: state from the render that built the handler can be one move behind.
  const pullRef = useRef(0);
  const [pull, setPull] = useState(0);
  const [refreshing, setRefreshing] = useState(false);

  // The document is the scroller, so that is where the browser's own overscroll reload has to be held off.
  useEffect(() => {
    const root = document.documentElement;
    const before = root.style.overscrollBehaviorY;
    root.style.overscrollBehaviorY = 'contain';
    return () => {
      root.style.overscrollBehaviorY = before;
    };
  }, []);

  const reset = () => {
    startY.current = null;
    pullRef.current = 0;
    setPull(0);
  };

  function down(e: PointerEvent<HTMLDivElement>) {
    if (refreshing || window.scrollY > 0) return;
    startY.current = e.clientY;
    // Keeps move/up/cancel coming here when the finger leaves the element.
    e.currentTarget.setPointerCapture?.(e.pointerId);
  }

  function move(e: PointerEvent) {
    if (startY.current === null) return;
    const dy = e.clientY - startY.current;
    // Damped so the indicator trails the finger.
    pullRef.current = dy > 0 ? Math.min(MAX_PULL, dy / 2) : 0;
    setPull(pullRef.current);
  }

  async function up() {
    const pulled = startY.current !== null && pullRef.current >= THRESHOLD;
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
