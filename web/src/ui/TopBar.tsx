import type { ReactNode } from 'react';
import styles from './TopBar.module.css';

type TopBarProps = { title: string; trailing?: ReactNode; onTitleClick?: () => void };

export function TopBar({ title, trailing, onTitleClick }: TopBarProps) {
  return (
    <header className={styles.bar}>
      <h1 className={styles.title}>
        {onTitleClick ? (
          <button type="button" className={styles.titleButton} onClick={onTitleClick}>
            {title} ▾
          </button>
        ) : (
          title
        )}
      </h1>
      {trailing ? <div className={styles.trailing}>{trailing}</div> : null}
    </header>
  );
}
