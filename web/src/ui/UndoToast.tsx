import styles from './UndoToast.module.css';

// DESIGN A6 undo toast. `index` counts up from the bottom: the oldest toast is 0, so the newest sits on top. Timing lives with the caller; this only shows the message and the Undo button.
export function UndoToast({ message, onUndo, index = 0 }: { message: string; onUndo: () => void; index?: number }) {
  return (
    <div role="status" aria-live="polite" className={styles.toast} style={index ? { bottom: `calc(var(--touch-min) + var(--space-4) + ${index * 60}px)` } : undefined}>
      <span className={styles.message}>{message}</span>
      <button type="button" className={styles.undo} onClick={onUndo}>
        Undo
      </button>
    </div>
  );
}
