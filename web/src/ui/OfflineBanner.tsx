import styles from './OfflineBanner.module.css';

export function OfflineBanner({ message }: { message: string }) {
  return (
    <div role="status" className={styles.banner}>
      {message}
    </div>
  );
}
