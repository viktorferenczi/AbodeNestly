import styles from './UndoToast.module.css';

// DESIGN A6 undo toast. Timing lives with the caller; this only shows the message and the Undo button.
export function UndoToast({ message, onUndo }: { message: string; onUndo: () => void }) {
  return (
    <div role="status" aria-live="polite" className={styles.toast}>
      <span className={styles.message}>{message}</span>
      <button type="button" className={styles.undo} onClick={onUndo}>
        Undo
      </button>
    </div>
  );
}
