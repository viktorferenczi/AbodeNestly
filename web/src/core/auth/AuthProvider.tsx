import type { Profile } from '@abodenestly/contract';
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { useOnline } from '../../ui/useOnline.ts';
import {
  avatarStoragePath,
  isAvatarType,
  defaultDeps,
  drainProfileOutbox,
  editDisplayName,
  persistEdit,
  queueProfileEdit,
  readCachedProfile,
  readOutbox,
  withPending,
  writeCachedProfile,
} from '../Profile.ts';
import type { AuthClient, AuthSession } from './authClient.ts';

export type AvatarState = {
  status: 'idle' | 'uploading' | 'queued';
  /** Object URL of the photo the user just picked; kept after the upload so the avatar does not flicker. */
  preview: string | null;
};

export type AuthState = {
  status: 'loading' | 'signedIn' | 'signedOut';
  session: AuthSession | null;
  profile: Profile | null;
  client: AuthClient;
  setLocalProfile: (profile: Profile) => void;
  avatar: AvatarState;
  /** `false` when the file type is not one we store; nothing is queued then. */
  changeAvatar: (file: File) => boolean;
  editName: (draft: string) => void;
  /** A queued edit or photo was rejected by the server and dropped. */
  syncProblem: boolean;
};

const AuthContext = createContext<AuthState | null>(null);

const RETRY_MS = 30_000;

const makeObjectUrl = (file: File) => (typeof URL.createObjectURL === 'function' ? URL.createObjectURL(file) : null);

export function AuthProvider({ client, children }: { client: AuthClient; children: ReactNode }) {
  const online = useOnline();
  const [status, setStatus] = useState<AuthState['status']>('loading');
  const [session, setSession] = useState<AuthSession | null>(null);
  const [profile, setProfile] = useState<Profile | null>(null);
  const [avatar, setAvatar] = useState<AvatarState>({ status: 'idle', preview: null });
  const [syncProblem, setSyncProblem] = useState(false);
  const [tick, setTick] = useState(0);
  // Refs mirror what async code needs to read synchronously (a blur right after Enter must see the Enter's edit).
  const profileRef = useRef<Profile | null>(null);
  // Interim (until SHOP-08): a queued photo is held in memory only, so a reload while offline loses it.
  const pendingFile = useRef<File | null>(null);
  const draining = useRef(false);
  const dirty = useRef(false);
  const activeUser = useRef<string | undefined>(undefined);
  const retryTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  const setLocalProfile = useCallback((next: Profile | null) => {
    profileRef.current = next;
    setProfile(next);
  }, []);

  useEffect(() => {
    let active = true;
    const apply = (next: AuthSession | null) => {
      if (!active) return;
      setSession(next);
      setStatus(next ? 'signedIn' : 'signedOut');
      if (!next) setLocalProfile(null);
    };
    const unsubscribe = client.onAuthStateChange(apply);
    // getSession resolves after the SDK has handled any callback code in the URL.
    void client.getSession().then(apply);
    return () => {
      active = false;
      unsubscribe();
    };
  }, [client, setLocalProfile]);

  const userId = session?.user.id;
  const hasProfile = profile !== null;

  const loadProfile = useCallback(
    async (uid: string, isActive: () => boolean) => {
      const result = await client.fetchProfile(uid);
      if (!isActive()) return;
      if (result.ok) {
        // A missing row (trigger not run yet) leaves the name empty.
        const merged = result.value ? withPending(result.value, readOutbox(uid)) : null;
        setLocalProfile(merged);
        if (merged) writeCachedProfile(uid, merged);
      } else {
        // Offline or failed read: fall back to the last good profile so the greeting still has a name.
        const cached = readCachedProfile(uid);
        setLocalProfile(cached ? withPending(cached, readOutbox(uid)) : null);
      }
    },
    [client, setLocalProfile],
  );

  useEffect(() => {
    if (!userId) return;
    let active = true;
    void loadProfile(userId, () => active);
    return () => {
      active = false;
    };
  }, [userId, loadProfile]);

  // A queued photo belongs to the signed-in user only; async drain work checks `activeUser` after each await.
  useEffect(() => {
    activeUser.current = userId;
    if (!userId) return;
    return () => {
      activeUser.current = undefined;
      clearTimeout(retryTimer.current);
      pendingFile.current = null;
      setAvatar((a) => {
        if (a.preview) URL.revokeObjectURL?.(a.preview);
        return { status: 'idle', preview: null };
      });
      setSyncProblem(false);
    };
  }, [userId]);

  // Coming back to the tab is the retry trigger while the browser still reports online (SPEC §6.4).
  useEffect(() => {
    if (!userId) return;
    const onVisible = () => {
      if (document.visibilityState === 'visible') setTick((t) => t + 1);
    };
    document.addEventListener('visibilitychange', onVisible);
    return () => document.removeEventListener('visibilitychange', onVisible);
  }, [userId]);

  // Interim drain (until SHOP-08): apply queued edits, upload the queued photo, queue its path, apply again.
  useEffect(() => {
    if (!userId || !online || draining.current) return;
    const file = pendingFile.current;
    if (!file && readOutbox(userId).length === 0) return;
    // A photo waits for the profile row: its path can only be saved onto an existing row.
    if (file && !hasProfile && readOutbox(userId).length === 0) return;
    draining.current = true;
    dirty.current = false;
    const live = () => activeUser.current === userId;
    void (async () => {
      let settled = true;
      let problem = false;
      // Returns false when the run must stop: signed out, or the network failed (entries stay queued).
      const drain = async () => {
        const drained = await drainProfileOutbox(userId, client);
        if (!live()) return false;
        if (drained.rejected.length > 0) {
          problem = true;
          // Show the server's truth again instead of the edit that was refused.
          await loadProfile(userId, live);
        }
        if (drained.status === 'network') settled = false;
        return live() && drained.status === 'done';
      };
      try {
        if (!(await drain())) return;
        const next = pendingFile.current;
        if (!next || !profileRef.current) return;
        const path = avatarStoragePath(userId, next, defaultDeps.uuid());
        if (!path) {
          problem = true;
          pendingFile.current = null;
          return;
        }
        setAvatar((a) => ({ ...a, status: 'uploading' }));
        const uploaded = await client.uploadAvatar(path, next);
        if (!live()) return;
        if (uploaded.ok) {
          const current = profileRef.current;
          if (current) {
            setLocalProfile(queueProfileEdit(userId, current, { avatar_path: path }, defaultDeps));
            if (pendingFile.current === next) pendingFile.current = null;
            await drain();
          } else {
            problem = true; // no row to save the path on; keep the file rather than lose it silently
          }
        } else if (uploaded.error.code === 'network') {
          settled = false;
        } else {
          problem = true;
          if (pendingFile.current === next) pendingFile.current = null;
        }
      } finally {
        draining.current = false;
        if (live()) {
          const waiting = pendingFile.current ? (navigator.onLine && settled && !problem ? 'uploading' : 'queued') : 'idle';
          setAvatar((a) => ({ ...a, status: waiting }));
          if (problem) setSyncProblem(true);
          clearTimeout(retryTimer.current);
          if (dirty.current) {
            // The user queued something while this run was in flight; it has not been tried yet.
            setTick((t) => t + 1);
          } else if (!settled) {
            // The browser says online but a request failed: try again later without waiting for an event.
            retryTimer.current = setTimeout(() => setTick((t) => t + 1), RETRY_MS);
          } else if (!problem && pendingFile.current && profileRef.current) {
            setTick((t) => t + 1);
          }
        }
      }
    })();
  }, [client, userId, online, tick, hasProfile, loadProfile, setLocalProfile]);

  const changeAvatar = useCallback((file: File) => {
    if (!isAvatarType(file.type)) return false;
    pendingFile.current = file;
    dirty.current = true;
    setSyncProblem(false);
    setAvatar((a) => {
      if (a.preview) URL.revokeObjectURL?.(a.preview);
      // Online picks go straight to uploading so "will upload when you're online" is never announced.
      const next = a.status === 'uploading' || (navigator.onLine && profileRef.current) ? 'uploading' : 'queued';
      return { status: next, preview: makeObjectUrl(file) };
    });
    setTick((t) => t + 1);
    return true;
  }, []);

  const editName = useCallback(
    (draft: string) => {
      const current = profileRef.current;
      if (!userId || !current) return;
      const edit = editDisplayName(current, draft, defaultDeps);
      if (!edit) return;
      setLocalProfile(persistEdit(userId, edit));
      dirty.current = true;
      setSyncProblem(false);
      setTick((t) => t + 1);
    },
    [userId, setLocalProfile],
  );

  const value = useMemo(
    () => ({ status, session, profile, client, setLocalProfile, avatar, changeAvatar, editName, syncProblem }),
    [status, session, profile, client, setLocalProfile, avatar, changeAvatar, editName, syncProblem],
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const value = useContext(AuthContext);
  if (!value) throw new Error('useAuth must be used inside <AuthProvider>');
  return value;
}
