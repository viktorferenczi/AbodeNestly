import { useEffect, useReducer, useState } from 'react';
import { OfflineBanner } from '../ui/OfflineBanner.tsx';
import { Sheet } from '../ui/Sheet.tsx';
import { useOnline } from '../ui/useOnline.ts';
import { useApi } from './api/ApiProvider.tsx';
import { formatExpiry, inviteLink, inviteSheetReducer, shownInvite, usesLeft } from './InviteSheet.ts';
import styles from './InviteSheet.module.css';

async function copyText(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    return false;
  }
}

// DESIGN B6 invite sheet. Role-independent: owners and members see the same thing (SPEC §4 CORE-03).
export function InviteSheet({ householdId, onClose }: { householdId: string; onClose: () => void }) {
  const api = useApi();
  const online = useOnline();
  const [state, dispatch] = useReducer(inviteSheetReducer, { kind: 'loading' });
  const [notice, setNotice] = useState<string | null>(null);
  const [oldCodeMayWork, setOldCodeMayWork] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const invite = shownInvite(state);
  const hasInvite = invite !== null;
  const rotating = state.kind === 'rotating';

  // Loads (or mints) the household's invite once online; reruns on reconnect or "Try again".
  useEffect(() => {
    if (!online || hasInvite) return;
    let active = true;
    dispatch({ type: 'load' });
    void (async () => {
      const found = await api.fetchLiveInvite(householdId);
      if (!active) return;
      if (!found.ok) return dispatch({ type: 'failed', code: found.error.code });
      if (found.value) return dispatch({ type: 'loaded', invite: found.value });
      const created = await api.createInvite(householdId);
      if (!active) return;
      if (!created.ok) return dispatch({ type: 'failed', code: created.error.code });
      dispatch({ type: 'loaded', invite: created.value });
    })();
    return () => {
      active = false;
    };
  }, [api, householdId, online, hasInvite, attempt]);

  async function onNewCode() {
    if (!invite || !online || rotating) return;
    setNotice(null);
    setOldCodeMayWork(false);
    dispatch({ type: 'rotate' });
    const created = await api.createInvite(householdId);
    if (!created.ok) return dispatch({ type: 'failed', code: created.error.code });
    // The API never retires older codes; the client does (SPEC §14).
    const retired = await api.retireInvite(invite.id);
    if (!retired.ok) setOldCodeMayWork(true);
    dispatch({ type: 'loaded', invite: created.value });
  }

  async function onCopyCode() {
    if (!invite || rotating) return;
    setNotice((await copyText(invite.code)) ? 'Code copied' : "Couldn't copy. Select the code instead.");
  }

  async function copyLink(url: string) {
    setNotice((await copyText(url)) ? 'Link copied' : "Couldn't copy the link.");
  }

  async function onShare() {
    if (!invite || !online || rotating) return;
    const url = inviteLink(location.origin, invite.code);
    if (typeof navigator.share !== 'function') return copyLink(url);
    try {
      await navigator.share({ title: 'Join my household', url });
    } catch (e) {
      // The user dismissing the system sheet is not an error.
      if ((e as { name?: string }).name !== 'AbortError') await copyLink(url);
    }
  }

  const failed = state.kind === 'error';
  const sharing = (
    <button type="button" className={styles.primary} disabled={!online || !invite || rotating} onClick={() => void onShare()}>
      Share link
    </button>
  );

  return (
    <Sheet title="Invite to household" onClose={onClose} footer={sharing}>
      <div className={styles.content}>
        {!online ? <OfflineBanner message="You're offline. Connect to change household settings." /> : null}

        {invite ? (
          <>
            <div className={styles.codeRow}>
              <code className={styles.code} aria-label={`Invite code ${invite.code}`}>
                {invite.code}
              </code>
              <button type="button" className={styles.secondary} disabled={rotating} onClick={() => void onCopyCode()}>
                Copy
              </button>
            </div>
            <p className={styles.meta}>
              {usesLeft(invite) === 1 ? '1 use left' : `${usesLeft(invite)} uses left`} · Expires{' '}
              {formatExpiry(invite.expires_at)}
            </p>
            <button
              type="button"
              className={styles.secondary}
              disabled={!online || rotating}
              aria-busy={rotating}
              onClick={() => void onNewCode()}
            >
              New code
            </button>
          </>
        ) : !online ? (
          <p className={styles.meta}>Connect to see the invite code.</p>
        ) : failed ? (
          <>
            <p role="alert" className={styles.error}>
              Couldn't load the invite code. Try again.
            </p>
            <button type="button" className={styles.secondary} onClick={() => setAttempt((n) => n + 1)}>
              Try again
            </button>
          </>
        ) : (
          <p className={styles.meta}>Loading…</p>
        )}

        {invite && failed ? (
          <p role="alert" className={styles.error}>
            Couldn't create a new code. The current code still works.
          </p>
        ) : null}
        {oldCodeMayWork ? (
          <p role="alert" className={styles.error}>
            The new code is ready, but the old code may still work.
          </p>
        ) : null}
        <p aria-live="polite" className={styles.meta}>
          {notice}
        </p>
      </div>
    </Sheet>
  );
}
