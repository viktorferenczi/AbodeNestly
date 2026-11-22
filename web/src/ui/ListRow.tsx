import { useRef, useState, type PointerEvent, type ReactNode } from 'react';
import styles from './ListRow.module.css';

/** Leftward drag, in px, that counts as a swipe. */
export const SWIPE_THRESHOLD = 80;

type ListRowProps = {
  title: string;
  subtitle?: string;
  /** Small chip next to the title (e.g. the store). */
  chip?: string;
  /** Right-aligned text such as a quantity. */
  trailing?: string;
  /** Greyed out, e.g. a checked item. */
  muted?: boolean;
  onOpen: () => void;
  /** A checkbox before the title. A separate control, because a button cannot nest inside the open button. */
  check?: { checked: boolean; onToggle: () => void; label: string };
  /** Fired by a swipe left past the threshold. Keyboard users get the same action from `actions`. */
  onSwipeLeft?: () => void;
  /** Always-reachable buttons (DESIGN A6): the swipe action must also exist as a button. */
  actions?: ReactNode;
};

// DESIGN A6 ListRow. Swipe uses plain pointer events; no gesture library.
export function ListRow({ title, subtitle, chip, trailing, muted, onOpen, check, onSwipeLeft, actions }: ListRowProps) {
  const [dx, setDx] = useState(0);
  // The latest delta lives in a ref: pointerup can run before React commits the last pointermove's state.
  const latest = useRef(0);
  const start = useRef<number | null>(null);
  const dragged = useRef(false);

  const onDown = (e: PointerEvent) => {
    start.current = e.clientX;
    latest.current = 0;
    dragged.current = false;
    // Keeps pointerup coming to the row when the finger drifts off it.
    e.currentTarget.setPointerCapture?.(e.pointerId);
  };
  const onMove = (e: PointerEvent) => {
    if (start.current == null) return;
    const delta = Math.min(0, e.clientX - start.current);
    if (delta < -4) dragged.current = true;
    latest.current = delta;
    setDx(delta);
  };
  const end = () => {
    if (start.current == null) return;
    start.current = null;
    const swiped = latest.current <= -SWIPE_THRESHOLD;
    latest.current = 0;
    setDx(0);
    if (swiped) onSwipeLeft?.();
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
        {check ? (
          <input type="checkbox" className={styles.check} checked={check.checked} aria-label={check.label} onChange={check.onToggle} />
        ) : null}
        <button
          type="button"
          className={[styles.main, muted ? styles.muted : '', check?.checked ? styles.struck : ''].filter(Boolean).join(' ')}
          onClick={() => {
            // A drag that ends over the button is not a tap.
            if (!dragged.current) onOpen();
            dragged.current = false;
          }}
        >
          <span className={styles.title}>{title}</span>
          {chip ? <span className={styles.chip}>{chip}</span> : null}
          {trailing ? <span className={styles.trailing}>{trailing}</span> : null}
          {subtitle ? <span className={styles.subtitle}>{subtitle}</span> : null}
        </button>
        {actions ? <div className={styles.actions}>{actions}</div> : null}
      </div>
    </li>
  );
}
