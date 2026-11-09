import type { Profile } from '@abodenestly/contract';
import type { SupabaseClient } from '@supabase/supabase-js';
import type { Membership } from './landing.ts';

export type AuthError = { code: 'invalid_email' | 'rate_limited' | 'network' | 'unknown' };
export type Result<T> = { ok: true; value: T } | { ok: false; error: AuthError };
export type OAuthProvider = 'apple' | 'google';

/** The slice of a Supabase session the app reads. */
export type AuthSession = { user: { id: string; email?: string | undefined } };

/** The calls this app makes to auth/profile storage; tests use a hand-written fake. */
export interface AuthClient {
  getSession(): Promise<AuthSession | null>;
  /** Returns the unsubscribe function. */
  onAuthStateChange(callback: (session: AuthSession | null) => void): () => void;
  signInWithOtp(email: string, redirectTo: string): Promise<AuthError | null>;
  signInWithOAuth(provider: OAuthProvider, redirectTo: string): Promise<AuthError | null>;
  signOut(): Promise<void>;
  /** `null` value: the server trigger has not created the row yet. */
  fetchProfile(userId: string): Promise<Result<Profile | null>>;
  fetchMemberships(userId: string): Promise<Result<Membership[]>>;
}

type ErrorLike = { name?: string; code?: string; status?: number };

export function toAuthError(error: ErrorLike): AuthError {
  if (error.code === 'over_email_send_rate_limit' || error.code === 'over_request_rate_limit' || error.status === 429) {
    return { code: 'rate_limited' };
  }
  if (error.code === 'email_address_invalid' || error.code === 'validation_failed') {
    return { code: 'invalid_email' };
  }
  if (error.name === 'AuthRetryableFetchError' || error.status === 0) return { code: 'network' };
  return { code: 'unknown' };
}

export function createSupabaseAuthClient(sb: SupabaseClient): AuthClient {
  return {
    async getSession() {
      const { data } = await sb.auth.getSession();
      return data.session;
    },
    onAuthStateChange(callback) {
      const { data } = sb.auth.onAuthStateChange((_event, session) => callback(session));
      return () => data.subscription.unsubscribe();
    },
    async signInWithOtp(email, redirectTo) {
      const { error } = await sb.auth.signInWithOtp({ email, options: { emailRedirectTo: redirectTo } });
      return error ? toAuthError(error) : null;
    },
    async signInWithOAuth(provider, redirectTo) {
      const { error } = await sb.auth.signInWithOAuth({ provider, options: { redirectTo } });
      return error ? toAuthError(error) : null;
    },
    async signOut() {
      await sb.auth.signOut();
    },
    async fetchProfile(userId) {
      const { data, error } = await sb.from('profile').select('*').eq('id', userId).maybeSingle();
      if (error) return { ok: false, error: { code: 'unknown' } };
      return { ok: true, value: data as Profile | null };
    },
    async fetchMemberships(userId) {
      const { data, error } = await sb
        .from('member')
        .select(
          'household_id, joined_at, household(id, module_shopping, module_pantry, module_maintenance, deleted_at)',
        )
        .eq('user_id', userId)
        .is('left_at', null);
      if (error) return { ok: false, error: { code: 'unknown' } };
      const rows = (data ?? []) as unknown as Array<{
        household_id: string;
        joined_at: string;
        household: (Membership['household'] & { deleted_at: string | null }) | null;
      }>;
      const memberships: Membership[] = [];
      for (const row of rows) {
        if (!row.household || row.household.deleted_at) continue;
        const { id, module_shopping, module_pantry, module_maintenance } = row.household;
        memberships.push({
          household_id: row.household_id,
          joined_at: row.joined_at,
          household: { id, module_shopping, module_pantry, module_maintenance },
        });
      }
      return { ok: true, value: memberships };
    },
  };
}
