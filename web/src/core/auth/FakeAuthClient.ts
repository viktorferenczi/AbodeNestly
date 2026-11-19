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
  membershipsFail = false;
  signOutFail = false;
  updateCalls: Array<{ userId: string; data: Partial<Pick<Profile, 'display_name' | 'avatar_path'>> }> = [];
  uploadCalls: Array<{ path: string; file: File }> = [];
  avatarUrlCalls: string[] = [];
  attachmentUrlCalls: string[] = [];
  updateError: AuthError | null = null;
  uploadError: AuthError | null = null;
  /** Set to a pending promise to hold `uploadAvatar` in flight; resolve it to let the upload finish. */
  uploadGate: Promise<void> | null = null;
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
    if (this.signOutFail) return Promise.reject(new Error('network'));
    this.session = null;
    this.listeners.forEach((l) => l(null));
    return Promise.resolve();
  }

  fetchProfile(): Promise<Result<Profile | null>> {
    return Promise.resolve({ ok: true, value: this.profile });
  }

  fetchMemberships(): Promise<Result<Membership[]>> {
    if (this.membershipsFail) return Promise.resolve({ ok: false, error: { code: 'network' } });
    return Promise.resolve({ ok: true, value: this.memberships });
  }

  updateProfile(
    userId: string,
    data: Partial<Pick<Profile, 'display_name' | 'avatar_path'>>,
  ): Promise<Result<void>> {
    this.updateCalls.push({ userId, data });
    if (this.updateError) return Promise.resolve({ ok: false, error: this.updateError });
    return Promise.resolve({ ok: true, value: undefined });
  }

  async uploadAvatar(path: string, file: File): Promise<Result<void>> {
    this.uploadCalls.push({ path, file });
    if (this.uploadGate) await this.uploadGate;
    if (this.uploadError) return { ok: false, error: this.uploadError };
    return { ok: true, value: undefined };
  }

  avatarUrl(path: string): Promise<string | null> {
    this.avatarUrlCalls.push(path);
    return Promise.resolve(`https://signed.test/${path}`);
  }

  attachmentUrl(path: string): Promise<string | null> {
    this.attachmentUrlCalls.push(path);
    return Promise.resolve(`https://signed.test/${path}`);
  }
}
