import { useEffect, useReducer, useRef, useState } from 'react';
import { Navigate, useNavigate } from 'react-router';
import { OfflineBanner } from '../ui/OfflineBanner.tsx';
import { UndoToast } from '../ui/UndoToast.tsx';
import { useOnline } from '../ui/useOnline.ts';
import { useApi } from './api/ApiProvider.tsx';
import type { ApiFailure, MemberRow } from './api/apiClient.ts';
import { useAuth } from './auth/AuthProvider.tsx';
import { clearLastHousehold, readLastHousehold } from './auth/lastHousehold.ts';
import { InviteSheet } from './InviteSheet.tsx';
import {
  canRemove,
  createUndoScheduler,
  initialMembersState,
  initials,
  isMe,
  leaveDecision,
  memberName,
  membersReducer,
  viewerRole,
  visibleRows,
} from './Members.ts';
import styles from './Members.module.css';
import { readMembersCache, writeMembersCache } from './membersCache.ts';

const LAST_OWNER_MESSAGE = "You're the last owner. Make someone else an owner before leaving.";

function removeMessage(code: ApiFailure['code'], target: string, leaving: boolean): string {
  if (code === 'last_owner') return LAST_OWNER_MESSAGE;
  if (code === 'forbidden') return `You don't have permission to remove ${target}.`;
  return leaving ? "Couldn't leave the household. Try again." : `Couldn't remove ${target}. Try again.`;
}

// DESIGN B6. The Shell's TopBar owns the h1.
export function Members() {
  const householdId = readLastHousehold();
  if (!householdId) return <Navigate to="/" replace />;
  return <MembersScreen householdId={householdId} />;
}

function MembersScreen({ householdId }: { householdId: string }) {
  const api = useApi();
  const online = useOnline();
  const navigate = useNavigate();
  const { session } = useAuth();
  const meId = session?.user.id ?? '';
  const [state, dispatch] = useReducer(membersReducer, initialMembersState);
  const [inviting, setInviting] = useState(false);
  const [menuFor, setMenuFor] = useState<string | null>(null);
  const [toast, setToast] = useState<{ message: string; undo: () => void } | null>(null);
  const [reload, setReload] = useState(0);
  const scheduler = useRef(createUndoScheduler());
  const mounted = useRef(true);
  // Bumped when a removal lands; a fetch that started earlier must not bring the member back.
  const generation = useRef(0);

  // Live list when online; the last cached list when offline or the fetch fails (web has no sync DB yet).
  useEffect(() => {
    let active = true;
    const started = generation.current;
    const fromCache = () => {
      const cached = readMembersCache(householdId);
      if (cached) dispatch({ type: 'loaded', rows: cached, source: 'cache' });
      else dispatch({ type: 'failed' });
    };
    if (!online) {
      fromCache();
      return;
    }
    void api.fetchMembers(householdId).then((result) => {
      if (!active || started !== generation.current) return;
      if (result.ok) dispatch({ type: 'loaded', rows: result.value, source: 'live' });
      else fromCache();
    });
    return () => {
      active = false;
    };
  }, [api, householdId, online, reload]);

  const { view } = state;
  useEffect(() => {
    if (view.kind === 'ready') writeMembersCache(householdId, view.rows);
  }, [householdId, view]);

  // The toast said "Removed", so leaving the screen does not cancel it: Undo is the only cancel.
  useEffect(() => {
    const current = scheduler.current;
    mounted.current = true;
    return () => {
      mounted.current = false;
      current.flushAll();
    };
  }, []);

  const rows = visibleRows(state);
  const role = view.kind === 'ready' ? viewerRole(view.rows, meId) : null;
  const busy = state.pendingRemoval !== null;

  function startRemoval(userId: string, toastMessage: string, leaving: boolean, targetName: string) {
    setMenuFor(null);
    dispatch({ type: 'removeStarted', userId });
    const handle = scheduler.current.schedule('removal', () => {
      setToast(null);
      void (async () => {
        const result = await api.removeMember(householdId, userId);
        // Already gone counts as done: the row should disappear either way.
        if (result.ok || result.error.code === 'not_found') {
          generation.current += 1;
          if (leaving) {
            clearLastHousehold();
            // Already navigated away: do not pull the user back to the landing route.
            if (mounted.current) navigate('/', { replace: true });
          } else dispatch({ type: 'removeDone', userId });
          return;
        }
        dispatch({ type: 'removeFailed', message: removeMessage(result.error.code, targetName, leaving) });
      })();
    });
    setToast({
      message: toastMessage,
      undo: () => {
        handle.undo();
        setToast(null);
        dispatch({ type: 'removeUndone' });
      },
    });
  }

  function onRemove(row: MemberRow) {
    const name = memberName(row);
    startRemoval(row.member.user_id, `Removed ${name}`, false, name);
  }

  function onLeave() {
    if (view.kind !== 'ready') return;
    const decision = leaveDecision(view.rows, meId);
    setMenuFor(null);
    if (!decision.ok) return dispatch({ type: 'messageShown', message: LAST_OWNER_MESSAGE });
    startRemoval(meId, 'Leaving household', true, 'yourself');
  }

  async function onMakeOwner(row: MemberRow) {
    setMenuFor(null);
    const result = await api.makeOwner(householdId, row.member.user_id);
    if (result.ok) {
      dispatch({ type: 'messageCleared' });
      return setReload((n) => n + 1);
    }
    dispatch({ type: 'messageShown', message: `Couldn't make ${memberName(row)} an owner. Try again.` });
  }

  return (
    <>
      <div className={styles.heading}>
        <h2 className={styles.title}>Members</h2>
        <button type="button" className={styles.invite} disabled={!online} onClick={() => setInviting(true)}>
          Invite
        </button>
      </div>

      <div className={styles.body}>
        {!online ? <OfflineBanner message="You're offline. Connect to change household settings." /> : null}
        {state.message ? (
          <p role="alert" className={styles.error}>
            {state.message}
          </p>
        ) : null}

        {view.kind === 'loading' ? <p className={styles.meta}>Loading…</p> : null}
        {view.kind === 'error' ? (
          online ? (
            <>
              <p role="alert" className={styles.error}>
                Couldn't load members. Try again.
              </p>
              <button type="button" className={styles.secondary} onClick={() => setReload((n) => n + 1)}>
                Try again
              </button>
            </>
          ) : (
            <p className={styles.meta}>Connect to see the members.</p>
          )
        ) : null}

        {view.kind === 'ready' ? (
          <>
            <ul className={styles.list}>
              {rows.map((row) => {
                const name = memberName(row);
                const me = isMe(row, meId);
                // Items are not rendered offline or while a removal is pending, so a stale open menu cannot act.
                const menuOpen = menuFor === row.member.user_id && online && !busy;
                const showMenu = canRemove(role, row, meId);
                return (
                  <li key={row.member.user_id} className={styles.row}>
                    <span className={styles.avatar} aria-hidden="true">
                      {initials(name)}
                    </span>
                    <span className={styles.who}>
                      <span className={styles.name}>{name}</span>
                      <span className={styles.meta}>{row.member.role === 'owner' ? 'Owner' : 'Member'}</span>
                    </span>
                    {me ? <span className={styles.badge}>You</span> : null}
                    {showMenu ? (
                      <div
                        className={styles.menuWrap}
                        onKeyDown={(e) => {
                          if (e.key === 'Escape') setMenuFor(null);
                        }}
                        onBlur={(e) => {
                          if (!e.currentTarget.contains(e.relatedTarget)) setMenuFor(null);
                        }}
                      >
                        <button
                          type="button"
                          className={styles.more}
                          aria-label={`More actions for ${name}`}
                          aria-haspopup="menu"
                          aria-expanded={menuOpen}
                          disabled={!online || busy}
                          onClick={() => setMenuFor(menuOpen ? null : row.member.user_id)}
                        >
                          ⋯
                        </button>
                        {menuOpen ? (
                          <div className={styles.menu} role="menu">
                            {row.member.role !== 'owner' ? (
                              <button type="button" role="menuitem" className={styles.menuItem} onClick={() => void onMakeOwner(row)}>
                                Make owner
                              </button>
                            ) : null}
                            <button type="button" role="menuitem" className={styles.menuItem} onClick={() => onRemove(row)}>
                              Remove
                            </button>
                          </div>
                        ) : null}
                      </div>
                    ) : null}
                  </li>
                );
              })}
            </ul>
            <button type="button" className={styles.leave} disabled={!online || busy} onClick={onLeave}>
              Leave household
            </button>
          </>
        ) : null}
      </div>

      {toast ? <UndoToast message={toast.message} onUndo={toast.undo} /> : null}
      {inviting ? <InviteSheet householdId={householdId} onClose={() => setInviting(false)} /> : null}
    </>
  );
}
