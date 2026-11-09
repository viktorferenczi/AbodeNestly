import type { ReactNode } from 'react';
import styles from './TopBar.module.css';

export function TopBar({ title, trailing }: { title: string; trailing?: ReactNode }) {
  return (
    <header className={styles.bar}>
      <h1 className={styles.title}>{title}</h1>
      {trailing ? <div className={styles.trailing}>{trailing}</div> : null}
    </header>
  );
}
