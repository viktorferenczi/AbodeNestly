import { createClient } from '@supabase/supabase-js';
import type { Page } from '@playwright/test';

const SUPABASE_URL = process.env.E2E_SUPABASE_URL ?? 'http://127.0.0.1:54321';
const BASE_URL = process.env.E2E_BASE_URL ?? 'http://localhost:5173';

function adminClient() {
  // Service role key is read from the process env only; it is never VITE_-prefixed, so it cannot reach the bundle.
  const key = process.env.SUPABASE_SERVICE_ROLE_KEY;
  if (!key) throw new Error('SUPABASE_SERVICE_ROLE_KEY is required for e2e (see web/README.md)');
  return createClient(SUPABASE_URL, key, { auth: { persistSession: false, autoRefreshToken: false } });
}

export type E2eUser = { id: string; email: string; localPart: string };

/** Creates a unique user (TESTING §5: no shared mutable state). Delete it with `deleteUser`. */
export async function createUser(): Promise<E2eUser> {
  const localPart = `e2e-${crypto.randomUUID()}`;
  const email = `${localPart}@test.local`;
  const { data, error } = await adminClient().auth.admin.generateLink({
    type: 'magiclink',
    email,
    options: { redirectTo: `${BASE_URL}/auth/callback` },
  });
  if (error) throw error;
  return { id: data.user.id, email, localPart };
}

export async function deleteUser(user: E2eUser): Promise<void> {
  const { error } = await adminClient().auth.admin.deleteUser(user.id);
  if (error) throw error;
}

/**
 * Signs `user` in through a magic link, then opens the callback.
 *
 * The app uses the PKCE flow, and supabase-js rejects the implicit-flow `action_link` that the admin
 * `generateLink` API returns ("Not a valid PKCE flow url"). So the link's single-use token hash is
 * redeemed here and the resulting session is stored where the SDK reads it; the page then loads
 * `/auth/callback` exactly as it would after a real link.
 */
export async function signInWithMagicLink(page: Page, user: E2eUser): Promise<void> {
  const admin = adminClient();
  const { data: link, error: linkError } = await admin.auth.admin.generateLink({
    type: 'magiclink',
    email: user.email,
  });
  if (linkError) throw linkError;
  const { data, error } = await admin.auth.verifyOtp({
    type: 'magiclink',
    token_hash: link.properties.hashed_token,
  });
  if (error || !data.session) throw error ?? new Error('verifyOtp returned no session');

  const storageKey = `sb-${new URL(SUPABASE_URL).hostname.split('.')[0]}-auth-token`;
  await page.addInitScript(
    ([key, value]) => {
      // Only seed once so a later sign-out is not undone by the init script on navigation.
      if (!sessionStorage.getItem('e2e-seeded')) {
        localStorage.setItem(key!, value!);
        sessionStorage.setItem('e2e-seeded', '1');
      }
    },
    [storageKey, JSON.stringify(data.session)],
  );
  await page.goto('/auth/callback');
}
