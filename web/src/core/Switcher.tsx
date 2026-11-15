import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router';
import { pendingCount } from '../sync/db.ts';
import { Sheet } from '../ui/Sheet.tsx';
import { useOnline } from '../ui/useOnline.ts';
import { useApi } from './api/ApiProvider.tsx';
import { useAuth } from './auth/AuthProvider.tsx';
import { useHousehold } from './household/HouseholdProvider.tsx';
import { readHouseholds, writeHouseholds, type HouseholdEntry } from './householdsCache.ts';
import { switcherState } from './Switcher.ts';
import styles from './Switcher.module.css';

const LONG_PRESS_MS = 500;
// A held finger or mouse drifts a few pixels; only a real drag (a scroll) cancels the press.
const MOVE_TOLERANCE_PX = 10;

// DESIGN B3. Rows come from the cached list so the sheet works offline; the network only refreshes it.
export function Switcher({ onClose }: { onClose: () => void }) {
  const api = useApi();
  const online = useOnline();
  const navigate = useNavigate();
  const { session } = useAuth();
  const { household: current, role, db, switching, switchTo } = useHousehold();
  const userId = session?.user.id;

  const [fetched, setFetched] = useState<HouseholdEntry[]>(() => (userId ? readHouseholds(userId) : []));
  const [pending, setPending] = useState<Record<string, number>>({});
  const [target, setTarget] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);

  // The current household is always listed, even before any list was cached.
  const entries = useMemo(() => {
    if (!current || !role || fetched.some((e) => e.household.id === current.id)) return fetched;
    return [{ household: current, role }, ...fetched];
  }, [fetched, current, role]);

  useEffect(() => {
    if (!online || !userId) return;
    let active = true;
    void api.fetchMyHouseholds(userId).then((result) => {
      if (!active || !result.ok) return;
      writeHouseholds(userId, result.value);
      setFetched(result.value);
    });
    return () => {
      active = false;
    };
  }, [api, online, userId]);

  // A tap waits for these: closing a count's short-lived connection mid-switch can fail the target's open.
  const counting = useRef<Promise<void>>(Promise.resolve());
  const ids = entries.map((e) => e.household.id).join(',');
  useEffect(() => {
    let active = true;
    const run = Promise.all(
      ids
        .split(',')
        .filter(Boolean)
        .map(async (id) => [id, await pendingCount(id, db).catch(() => 0)] as const),
    ).then((pairs) => {
      if (active) setPending(Object.fromEntries(pairs));
    });
    counting.current = run;
    return () => {
      active = false;
    };
  }, [ids, db]);

  const longPress = useRef<{ timer?: ReturnType<typeof setTimeout>; fired: boolean; x: number; y: number }>({
    fired: false,
    x: 0,
    y: 0,
  });
  useEffect(() => () => clearTimeout(longPress.current.timer), []);

  const state = switcherState(entries, switching);
  const targetName = entries.find((e) => e.household.id === target)?.household.name ?? '';

  async function select(id: string, then?: () => void) {
    setFailed(false);
    if (id !== current?.id) {
      setTarget(id);
      try {
        await counting.current;
        await switchTo(id);
      } catch {
        setFailed(true);
        return;
      }
    }
    then?.();
    onClose();
  }

  // Long-press and the context menu key both open B5 for that household.
  const openSettings = (id: string) => void select(id, () => void navigate('/household'));

  function cancelPress() {
    clearTimeout(longPress.current.timer);
  }

  return (
    <Sheet title="Households" onClose={onClose}>
      {state === 'switching' ? (
        <p className={styles.progress} role="status" aria-busy="true" aria-live="polite">
          Switching to {targetName}…
        </p>
      ) : null}
      {failed ? <p role="alert">Couldn't switch households. Try again.</p> : null}
      <ul className={styles.list}>
        {entries.map(({ household, role: r }) => {
          const isCurrent = household.id === current?.id;
          const count = pending[household.id] ?? 0;
          return (
            <li key={household.id}>
              <button
                type="button"
                className={styles.row}
                disabled={state === 'switching'}
                aria-current={isCurrent ? 'true' : undefined}
                onPointerDown={(e) => {
                  longPress.current.fired = false;
                  longPress.current.x = e.clientX;
                  longPress.current.y = e.clientY;
                  longPress.current.timer = setTimeout(() => {
                    // On touch the same hold also raises contextmenu; the flag lets only one of them act.
                    if (longPress.current.fired) return;
                    longPress.current.fired = true;
                    openSettings(household.id);
                  }, LONG_PRESS_MS);
                }}
                onPointerUp={cancelPress}
                onPointerLeave={cancelPress}
                onPointerMove={(e) => {
                  const { x, y } = longPress.current;
                  if (Math.hypot(e.clientX - x, e.clientY - y) > MOVE_TOLERANCE_PX) cancelPress();
                }}
                onPointerCancel={cancelPress}
                onContextMenu={(e) => {
                  e.preventDefault();
                  cancelPress();
                  if (longPress.current.fired) return;
                  longPress.current.fired = true;
                  openSettings(household.id);
                }}
                onClick={() => {
                  if (longPress.current.fired) return void (longPress.current.fired = false);
                  void select(household.id);
                }}
              >
                <span className={styles.check} aria-hidden="true">
                  {isCurrent ? '✓' : ''}
                </span>
                <span className={styles.text}>
                  <span className={styles.name}>{household.name}</span>
                  <span className={styles.meta}>
                    {r === 'owner' ? 'Owner' : 'Member'}
                    {count > 0 ? ` · ${count} to sync` : ''}
                  </span>
                </span>
              </button>
            </li>
          );
        })}
        <li>
          <button
            type="button"
            className={styles.row}
            disabled={state === 'switching'}
            onClick={() => {
              void navigate('/welcome');
              onClose();
            }}
          >
            <span className={styles.check} aria-hidden="true" />
            <span className={styles.name}>Create or join another</span>
          </button>
        </li>
      </ul>
    </Sheet>
  );
}
