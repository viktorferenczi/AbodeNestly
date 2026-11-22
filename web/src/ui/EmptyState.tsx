import type { ReactNode } from 'react';
import styles from './EmptyState.module.css';

// DESIGN A6: one sentence on what the module does, plus the first action.
export function EmptyState({ title, text, action }: { title: string; text: string; action?: ReactNode }) {
  return (
    <div className={styles.empty} data-testid="empty-state">
      <h3 className={styles.title}>{title}</h3>
      <p className={styles.text}>{text}</p>
      {action}
    </div>
  );
}
