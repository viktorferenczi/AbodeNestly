import type { Profile } from '@abodenestly/contract';
import type { AuthClient, AuthError, AuthSession, OAuthProvider, Result } from './authClient.ts';
import type { Membership } from './landing.ts';

// Hand-written fake (TESTING §2 forbids mocking libraries). Records calls, returns what the test sets.
export class FakeAuthClient implements AuthClient {
  otpCalls: Array<{ email: string; redirectTo: string }> = [];
  oauthCalls: Array<{ provider: OAuthProvider; redirectTo: string }> = [];
  signOutCalls = 0;
  otpError: AuthError | null = null;
  oauthError: AuthError | null = null;
  profile: Profile | null = null;
  memberships: Membership[] = [];
  private listeners = new Set<(session: AuthSession | null) => void>();

  constructor(public session: AuthSession | null = null) {}

  getSession() {
    return Promise.resolve(this.session);
  }

  onAuthStateChange(callback: (session: AuthSession | null) => void) {
    this.listeners.add(callback);
    return () => void this.listeners.delete(callback);
  }

  signInWithOtp(email: string, redirectTo: string) {
    this.otpCalls.push({ email, redirectTo });
    return Promise.resolve(this.otpError);
  }

  signInWithOAuth(provider: OAuthProvider, redirectTo: string) {
    this.oauthCalls.push({ provider, redirectTo });
    return Promise.resolve(this.oauthError);
  }

  signOut() {
    this.signOutCalls += 1;
    this.session = null;
    this.listeners.forEach((l) => l(null));
    return Promise.resolve();
  }

  fetchProfile(): Promise<Result<Profile | null>> {
    return Promise.resolve({ ok: true, value: this.profile });
  }

  fetchMemberships(): Promise<Result<Membership[]>> {
    return Promise.resolve({ ok: true, value: this.memberships });
  }
}
