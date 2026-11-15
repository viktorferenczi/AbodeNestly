import type { ReactNode } from 'react';
import styles from './TopBar.module.css';

type TopBarProps = { title: string; trailing?: ReactNode; onTitleClick?: () => void; titleExpanded?: boolean };

export function TopBar({ title, trailing, onTitleClick, titleExpanded }: TopBarProps) {
  return (
    <header className={styles.bar}>
      <h1 className={styles.title}>
        {onTitleClick ? (
          <button type="button" className={styles.titleButton}
            aria-haspopup="dialog"
            aria-expanded={titleExpanded ?? false}
            onClick={onTitleClick}
          >
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
