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
  /** Interim direct write used to drain the profile outbox until web sync exists (SHOP-08). */
  updateProfile(userId: string, data: Partial<Pick<Profile, 'display_name' | 'avatar_path'>>): Promise<Result<void>>;
  /** `path` is `profiles/<uid>/<file>` in the private `households` bucket. */
  uploadAvatar(path: string, file: File): Promise<Result<void>>;
  /** A short-lived signed URL, or `null` when it cannot be created (offline, object missing). */
  avatarUrl(path: string): Promise<string | null>;
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

// supabase-js reports a failed fetch as an error whose message mentions the fetch, not as a status.
function toWriteError(error: ErrorLike & { message?: string }): AuthError {
  const mapped = toAuthError(error);
  if (mapped.code !== 'unknown') return mapped;
  return /failed to fetch|network|load failed/i.test(error.message ?? '') ? { code: 'network' } : mapped;
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
    async updateProfile(userId, data) {
      const { error } = await sb.from('profile').update(data).eq('id', userId);
      return error ? { ok: false, error: toWriteError(error) } : { ok: true, value: undefined };
    },
    async uploadAvatar(path, file) {
      try {
        const { error } = await sb.storage.from('households').upload(path, file, { contentType: file.type });
        return error ? { ok: false, error: toWriteError(error) } : { ok: true, value: undefined };
      } catch (e) {
        return { ok: false, error: toWriteError(e as ErrorLike & { message?: string }) };
      }
    },
    async avatarUrl(path) {
      const { data } = await sb.storage.from('households').createSignedUrl(path, 3600);
      return data?.signedUrl ?? null;
    },
  };
}
