import styles from './Shell.module.css';

// Empty app shell. Tabs, household switcher and sync status arrive with CORE stories.
export function Shell() {
  return (
    <div className={styles.shell}>
      <header className={styles.header}>
        <h1 className={styles.title}>AbodeNestly</h1>
      </header>
      <main className={styles.main} />
    </div>
  );
}
