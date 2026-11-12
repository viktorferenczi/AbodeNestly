import { expect, test } from '@playwright/test';
import { adminClient, createUser, deleteUser, signInWithMagicLink, type E2eUser } from './helpers/auth.ts';

// E2E-CORE-1, invite half. "Both see each other" in the UI waits for CORE-04's member rows, so the
// join is asserted on the `member` row through the admin client instead.
test.describe('E2E-CORE-1: invite by link', () => {
  let userA: E2eUser;
  let userB: E2eUser;

  test.beforeAll(async () => {
    userA = await createUser();
    userB = await createUser();
  });

  test.afterAll(async () => {
    // Household rows cascade from household; delete it before the user that created it.
    await adminClient().from('household').delete().eq('created_by', userA.id);
    await deleteUser(userA);
    await deleteUser(userB);
  });

  test('CORE-03: new code retires the old link; user B joins through the new one', async ({ page, browser }, testInfo) => {
    // Records the URL handed to the system share sheet.
    await page.addInitScript(() => {
      (window as unknown as { __shared: string[] }).__shared = [];
      Object.defineProperty(navigator, 'share', {
        configurable: true,
        value: (data: { url: string }) => {
          (window as unknown as { __shared: string[] }).__shared.push(data.url);
          return Promise.resolve();
        },
      });
    });

    await signInWithMagicLink(page, userA);
    await expect(page).toHaveURL(/\/welcome$/);
    await page.getByLabel('Household name').fill('Invite Home');
    await page.getByRole('button', { name: 'Create household' }).click();
    await expect(page).toHaveURL(/\/home$/);

    await page.goto('/household/members');
    await page.getByRole('button', { name: 'Invite' }).click();
    const dialog = page.getByRole('dialog', { name: 'Invite to household' });
    const codeBox = dialog.getByLabel('Invite code');
    await expect(codeBox).toHaveText(/^[0-9a-f]{12}$/);
    const oldCode = (await codeBox.textContent())!;
    await expect(dialog).toContainText('10 uses left');

    await dialog.getByRole('button', { name: 'New code' }).click();
    await expect(codeBox).not.toHaveText(oldCode);
    const newCode = (await codeBox.textContent())!;
    expect(newCode).toMatch(/^[0-9a-f]{12}$/);

    await dialog.getByRole('button', { name: 'Share link' }).click();
    const base = testInfo.project.use.baseURL!;
    await expect.poll(() => page.evaluate(() => (window as unknown as { __shared: string[] }).__shared)).toEqual([
      `${base}/join/${newCode}`,
    ]);

    // User B, fresh context: the retired link signs in, lands on the join card with the code filled in,
    // and fails.
    const context = await browser.newContext({ ...testInfo.project.use, baseURL: base });
    try {
      const b = await context.newPage();
      await b.goto(`/join/${oldCode}`);
      await expect(b).toHaveURL(/\/signin\?next=/);
      await signInWithMagicLink(b, userB, `/join/${oldCode}`);
      await expect(b).toHaveURL(new RegExp(`/welcome\\?code=${oldCode}$`));
      await expect(b.getByLabel('Invite code')).toHaveValue(oldCode);
      await b.getByRole('button', { name: 'Join household' }).click();
      await expect(b.getByRole('alert')).toContainText("isn't valid");

      // The new link joins.
      await b.goto(`/join/${newCode}`);
      await expect(b).toHaveURL(new RegExp(`/welcome\\?code=${newCode}$`));
      await b.getByRole('button', { name: 'Join household' }).click();
      await expect(b).toHaveURL(/\/home$/);
    } finally {
      await context.close();
    }

    const { data: households } = await adminClient().from('household').select('id').eq('created_by', userA.id);
    const { data: members } = await adminClient()
      .from('member')
      .select('user_id, role, left_at')
      .eq('household_id', households![0]!.id);
    expect(members?.find((m) => m.user_id === userB.id)).toMatchObject({ role: 'member', left_at: null });
    expect(members).toHaveLength(2);
  });
});
