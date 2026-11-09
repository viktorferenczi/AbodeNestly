import type { Profile } from '@abodenestly/contract';
import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import type { AuthClient, AuthSession } from './authClient.ts';

export type AuthState = {
  status: 'loading' | 'signedIn' | 'signedOut';
  session: AuthSession | null;
  profile: Profile | null;
  client: AuthClient;
};

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ client, children }: { client: AuthClient; children: ReactNode }) {
  const [status, setStatus] = useState<AuthState['status']>('loading');
  const [session, setSession] = useState<AuthSession | null>(null);
  const [profile, setProfile] = useState<Profile | null>(null);

  useEffect(() => {
    let active = true;
    const apply = (next: AuthSession | null) => {
      if (!active) return;
      setSession(next);
      setStatus(next ? 'signedIn' : 'signedOut');
      if (!next) setProfile(null);
    };
    const unsubscribe = client.onAuthStateChange(apply);
    // getSession resolves after the SDK has handled any callback code in the URL.
    void client.getSession().then(apply);
    return () => {
      active = false;
      unsubscribe();
    };
  }, [client]);

  const userId = session?.user.id;
  useEffect(() => {
    if (!userId) return;
    let active = true;
    void client.fetchProfile(userId).then((result) => {
      // A missing row (trigger not run yet) or a failed read leaves the name empty.
      if (active) setProfile(result.ok ? result.value : null);
    });
    return () => {
      active = false;
    };
  }, [client, userId]);

  const value = useMemo(() => ({ status, session, profile, client }), [status, session, profile, client]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const value = useContext(AuthContext);
  if (!value) throw new Error('useAuth must be used inside <AuthProvider>');
  return value;
}
