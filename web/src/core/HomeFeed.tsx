import type { Activity } from '@abodenestly/contract';
import { Link } from 'react-router';
import { PullToRefresh } from '../ui/PullToRefresh.tsx';
import { useLiveQuery } from '../ui/useLiveQuery.ts';
import { useOnline } from '../ui/useOnline.ts';
import { useSync } from '../sync/SyncEngine.ts';
import { homeFeed, homeState } from './HomeFeed.ts';
import { useHousehold, type Modules } from './household/HouseholdProvider.tsx';
import styles from './HomeFeed.module.css';

// DESIGN B4. Counts stay 0 until the modules exist; the cards only carry the tap-through.
const TODAY: Array<{ module: keyof Modules; to: string; title: string; counts: string[] }> = [
  { module: 'shopping', to: '/shopping', title: 'Shopping', counts: ['On the list: 0'] },
  { module: 'pantry', to: '/pantry', title: 'Pantry', counts: ['Eat soon: 0', 'Low: 0'] },
  { module: 'maintenance', to: '/maintenance', title: 'Maintenance', counts: ['Due tasks: 0'] },
];

const whenFormat = new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' });

export function HomeFeed() {
  const { status, db, household, modules } = useHousehold();
  const sync = useSync();
  // Offline the pull is a no-op that keeps the cached rows; the screen reads the local database either way.
  const online = useOnline();
  const householdId = household?.id;
  const rows = useLiveQuery(
    () => (db && householdId ? db.activity.where('household_id').equals(householdId).toArray() : []),
    [db, householdId],
  );

  if (status !== 'ready' || rows === undefined) return null;

  const feed = homeFeed(rows, new Date());
  const state = homeState(feed, modules);

  return (
    <PullToRefresh onRefresh={() => sync.kick()}>
      <div className={styles.home} data-testid="home" data-online={online}>
        {state === 'allModulesOff' ? (
          <section className={styles.prompt} data-testid="home.allOff">
            <p>Every module is off. Turn one on in Settings.</p>
            <Link to="/household" className={styles.primary}>
              Settings
            </Link>
          </section>
        ) : (
          <section className={styles.today} aria-label="Today">
            {TODAY.filter((c) => modules[c.module]).map((c) => (
              <Link key={c.module} to={c.to} className={styles.card} data-testid={`home.today.${c.module}`}>
                <span className={styles.cardTitle}>{c.title}</span>
                {c.counts.map((t) => (
                  <span key={t} className={styles.count}>
                    {t}
                  </span>
                ))}
              </Link>
            ))}
          </section>
        )}

        <h2 className={styles.heading}>Activity</h2>
        {feed.length === 0 ? (
          state === 'empty' ? (
            <section className={styles.empty} data-testid="home.empty">
              <p>Things your household does show up here.</p>
              <Link to="/household/members" className={styles.primary}>
                Invite a member
              </Link>
            </section>
          ) : null
        ) : (
          <ul className={styles.feed} data-testid="home.feed">
            {feed.map((a) => (
              <FeedItem key={a.id} activity={a} />
            ))}
          </ul>
        )}

        <nav className={styles.footer} aria-label="Household">
          <Link to="/household/members">Members</Link>
          <Link to="/household">Settings</Link>
          <Link to="/profile">Profile</Link>
        </nav>
      </div>
    </PullToRefresh>
  );
}

function FeedItem({ activity }: { activity: Activity }) {
  return (
    <li className={styles.item}>
      <span>{activity.summary}</span>
      <time className={styles.when} dateTime={activity.created_at}>
        {whenFormat.format(new Date(activity.created_at))}
      </time>
    </li>
  );
}
