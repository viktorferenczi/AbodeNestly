import { createClient } from '@supabase/supabase-js';
import { expect, type Page } from '@playwright/test';

const SUPABASE_URL = process.env.E2E_SUPABASE_URL ?? 'http://127.0.0.1:54321';
const MAILPIT_URL = process.env.E2E_MAILPIT_URL ?? 'http://127.0.0.1:54324';

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
  const { data, error } = await adminClient().auth.admin.createUser({ email, email_confirm: true });
  if (error) throw error;
  return { id: data.user.id, email, localPart };
}

export async function deleteUser(user: E2eUser): Promise<void> {
  const { error } = await adminClient().auth.admin.deleteUser(user.id);
  if (error) throw error;
}

async function magicLinkFromMailpit(email: string): Promise<string> {
  let link: string | undefined;
  await expect
    .poll(
      async () => {
        const search = await fetch(`${MAILPIT_URL}/api/v1/search?query=${encodeURIComponent(`to:${email}`)}`);
        const { messages } = (await search.json()) as { messages: Array<{ ID: string }> };
        const id = messages[0]?.ID;
        if (!id) return false;
        const message = (await (await fetch(`${MAILPIT_URL}/api/v1/message/${id}`)).json()) as { HTML: string };
        link = /href="([^"]*\/auth\/v1\/verify[^"]*)"/.exec(message.HTML)?.[1]?.replaceAll('&amp;', '&');
        return Boolean(link);
      },
      { message: `magic link email for ${email}`, timeout: 15_000 },
    )
    .toBe(true);
  return link!;
}

/**
 * Real PKCE magic-link sign-in: request the link from `/signin` (this stores the code verifier in the
 * page's browser context), read the email from local Mailpit, and open the link in the same page so
 * `detectSessionInUrl` performs the code exchange. The admin `generateLink` URL cannot be used: it is
 * implicit-flow and supabase-js rejects it under PKCE.
 */
export async function signInWithMagicLink(page: Page, user: E2eUser): Promise<void> {
  await page.goto('/signin');
  await page.getByLabel('Email').fill(user.email);
  await page.getByRole('button', { name: 'Send magic link' }).click();
  await expect(page.getByRole('heading', { name: 'Check your email' })).toBeVisible();
  await page.goto(await magicLinkFromMailpit(user.email));
}
