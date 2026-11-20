import { useEffect, useState } from 'react';
import { useSync } from '../sync/SyncEngine.ts';
import { useLiveQuery } from '../ui/useLiveQuery.ts';
import { useAuth } from './auth/AuthProvider.tsx';
import { useHousehold } from './household/HouseholdProvider.tsx';
import { enabled, normalizeId, setPref, visibleTypes } from './NotificationPrefs.ts';
import type { NotificationPref, NotificationType } from '@abodenestly/contract';
import styles from './NotificationPrefs.module.css';

const deps = { uuid: () => crypto.randomUUID(), now: () => new Date().toISOString() };

type Permission = NotificationPermission | 'unsupported';
const readPermission = (): Permission => (typeof Notification === 'undefined' ? 'unsupported' : Notification.permission);

// DESIGN B9. Reads and writes only the local database; the change reaches the server through the outbox.
export function NotificationPrefs() {
  const { status, db, household, modules } = useHousehold();
  const { session } = useAuth();
  const sync = useSync();
  const householdId = household?.id;
  const userId = session?.user.id;
  const [permission, setPermission] = useState<Permission>(readPermission);
  // Shown as the result of pressing "System notification settings" once the browser will not ask again.
  const [helpShown, setHelpShown] = useState(false);

  // The person may change the site setting in the browser and come back (iOS re-reads on active, Android on resume).
  useEffect(() => {
    const reread = () => setPermission(readPermission());
    const onVisible = () => {
      if (document.visibilityState === 'visible') reread();
    };
    window.addEventListener('focus', reread);
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      window.removeEventListener('focus', reread);
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, []);

  // `null` while there is nothing to read: a stale `[]` from before the database opened would otherwise render the
  // switches for one frame and then unmount them again when the subscription restarts.
  const rows = useLiveQuery<NotificationPref[] | null>(async () => {
    if (!db || !householdId || !userId) return null;
    const user = normalizeId(userId);
    const all = await db.notification_pref.where('household_id').equals(normalizeId(householdId)).toArray();
    return all.filter((r) => r.user_id === user);
  }, [db, householdId, userId]);

  if (status !== 'ready' || !rows || !db || !householdId || !userId) return null;

  const onToggle = async (type: NotificationType, on: boolean) => {
    await setPref(db, { userId, householdId, type }, on, deps);
    void sync.kick();
  };

  // Browsers have no deep link to their notification settings (platform deviation, DESIGN B9): ask when we still can,
  // otherwise explain where the user changes it.
  const onSettings = async () => {
    if (permission === 'default') setPermission(await Notification.requestPermission());
    setHelpShown(true);
  };

  return (
    <div className={styles.prefs} data-testid="notif">
      <h2 className={styles.heading}>Notification preferences</h2>
      {permission === 'denied' ? (
        <p className={styles.banner} role="status" data-testid="notif.denied">
          Notifications are blocked for this site. Allow them in your browser's site settings to receive these notices.
        </p>
      ) : null}
      <ul className={styles.list}>
        {visibleTypes(modules).map(({ type, label }) => (
          <li key={type} className={styles.row}>
            <label className={styles.label} htmlFor={`notif-${type}`}>
              {label}
            </label>
            <input
              id={`notif-${type}`}
              type="checkbox"
              role="switch"
              className={styles.switch}
              data-testid={`notif.switch.${type}`}
              checked={enabled(rows, type)}
              onChange={(e) => void onToggle(type, e.target.checked)}
            />
          </li>
        ))}
      </ul>
      <button type="button" className={styles.link} data-testid="notif.settings" onClick={() => void onSettings()}>
        System notification settings
      </button>
      {helpShown && permission !== 'default' ? (
        <p className={styles.hint} data-testid="notif.settings.help">
          {permission === 'unsupported'
            ? 'This browser does not support notifications.'
            : "Change this in your browser's site settings (the lock or tune icon next to the address)."}
        </p>
      ) : null}
    </div>
  );
}
