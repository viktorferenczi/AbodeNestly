import styles from './UndoToast.module.css';

// DESIGN A6 undo toast. `index` stacks several pending toasts, newest at 0. Timing lives with the caller; this only shows the message and the Undo button.
export function UndoToast({ message, onUndo, index = 0 }: { message: string; onUndo: () => void; index?: number }) {
  return (
    <div role="status" aria-live="polite" className={styles.toast} style={index ? { bottom: `calc(var(--space-4) + ${index * 60}px)` } : undefined}>
      <span className={styles.message}>{message}</span>
      <button type="button" className={styles.undo} onClick={onUndo}>
        Undo
      </button>
    </div>
  );
}
