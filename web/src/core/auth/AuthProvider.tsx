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

  // A queued photo belongs to the signed-in user only.
  useEffect(() => {
    if (!userId) return;
    return () => {
      pendingFile.current = null;
      setAvatar((a) => {
        if (a.preview) URL.revokeObjectURL?.(a.preview);
        return { status: 'idle', preview: null };
      });
      setSyncProblem(false);
    };
  }, [userId]);

  // Interim drain (until SHOP-08): upload the queued photo, queue its path, then apply queued edits.
  useEffect(() => {
    if (!userId || !online || draining.current) return;
    if (!pendingFile.current && readOutbox(userId).length === 0) return;
    draining.current = true;
    dirty.current = false;
    void (async () => {
      let settled = true;
      let problem = false;
      try {
        const file = pendingFile.current;
        if (file) {
          const path = avatarStoragePath(userId, file, defaultDeps.uuid());
          setAvatar((a) => ({ ...a, status: 'uploading' }));
          const uploaded = path ? await client.uploadAvatar(path, file) : null;
          if (!path || (uploaded && !uploaded.ok && uploaded.error.code !== 'network')) {
            problem = true;
            if (pendingFile.current === file) pendingFile.current = null;
          } else if (uploaded?.ok) {
            if (pendingFile.current === file) pendingFile.current = null;
            if (profileRef.current) {
              setLocalProfile(queueProfileEdit(userId, profileRef.current, { avatar_path: path }, defaultDeps));
            }
          } else {
            settled = false; // offline mid-upload: keep the file queued
          }
          setAvatar((a) => ({ ...a, status: pendingFile.current ? 'queued' : 'idle' }));
          if (!settled) return;
        }
        const drained = await drainProfileOutbox(userId, client);
        if (drained.status === 'network') settled = false;
        if (drained.rejected.length > 0) {
          problem = true;
          // Show the server's truth again instead of the edit that was refused.
          await loadProfile(userId, () => true);
        }
      } finally {
        draining.current = false;
        if (problem) setSyncProblem(true);
        // Something was queued while this run was in flight; run again.
        if (settled && (dirty.current || pendingFile.current)) setTick((t) => t + 1);
      }
    })();
  }, [client, userId, online, tick, loadProfile, setLocalProfile]);

  const changeAvatar = useCallback((file: File) => {
    if (!avatarStoragePath('', file, '')) return false;
    pendingFile.current = file;
    dirty.current = true;
    setSyncProblem(false);
    setAvatar((a) => {
      if (a.preview) URL.revokeObjectURL?.(a.preview);
      return { status: a.status === 'uploading' ? 'uploading' : 'queued', preview: makeObjectUrl(file) };
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
