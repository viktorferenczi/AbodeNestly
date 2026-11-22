import { useRef, useState, type PointerEvent, type ReactNode } from 'react';
import styles from './ListRow.module.css';

/** Leftward drag, in px, that counts as a swipe. */
export const SWIPE_THRESHOLD = 80;

type ListRowProps = {
  title: string;
  subtitle?: string;
  /** Small chip next to the title (e.g. the store). */
  chip?: string;
  onOpen: () => void;
  /** Fired by a swipe left past the threshold. Keyboard users get the same action from `actions`. */
  onSwipeLeft?: () => void;
  /** Always-reachable buttons (DESIGN A6): the swipe action must also exist as a button. */
  actions?: ReactNode;
};

// DESIGN A6 ListRow. Swipe uses plain pointer events; no gesture library.
export function ListRow({ title, subtitle, chip, onOpen, onSwipeLeft, actions }: ListRowProps) {
  const [dx, setDx] = useState(0);
  const start = useRef<number | null>(null);
  const dragged = useRef(false);

  const onDown = (e: PointerEvent) => {
    start.current = e.clientX;
    dragged.current = false;
  };
  const onMove = (e: PointerEvent) => {
    if (start.current == null) return;
    const delta = Math.min(0, e.clientX - start.current);
    if (delta < -4) dragged.current = true;
    setDx(delta);
  };
  const end = () => {
    if (start.current == null) return;
    start.current = null;
    if (dx <= -SWIPE_THRESHOLD) onSwipeLeft?.();
    setDx(0);
  };

  return (
    <li className={styles.row}>
      <div
        className={styles.surface}
        style={dx ? { transform: `translateX(${dx}px)` } : undefined}
        onPointerDown={onSwipeLeft ? onDown : undefined}
        onPointerMove={onSwipeLeft ? onMove : undefined}
        onPointerUp={end}
        onPointerCancel={end}
      >
        <button
          type="button"
          className={styles.main}
          onClick={() => {
            // A drag that ends over the button is not a tap.
            if (!dragged.current) onOpen();
            dragged.current = false;
          }}
        >
          <span className={styles.title}>{title}</span>
          {chip ? <span className={styles.chip}>{chip}</span> : null}
          {subtitle ? <span className={styles.subtitle}>{subtitle}</span> : null}
        </button>
        {actions ? <div className={styles.actions}>{actions}</div> : null}
      </div>
    </li>
  );
}
