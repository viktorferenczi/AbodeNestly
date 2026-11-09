import { expect, test } from '@playwright/test';
import { createUser, deleteUser, signInWithMagicLink, type E2eUser } from './helpers/auth.ts';

// E2E-CORE-1, first half: sign in. (Create household / invite arrive with CORE-02 and CORE-03.)
test.describe('E2E-CORE-1: sign in', () => {
  let user: E2eUser;

  test.beforeAll(async () => {
    user = await createUser();
  });

  test.afterAll(async () => {
    await deleteUser(user);
  });

  test('CORE-01: magic link lands on /welcome with the display name, and survives a reload', async ({ page }) => {
    await signInWithMagicLink(page, user);

    // AC4: no household yet → /welcome. AC8: profile trigger set display_name to the email local part.
    await expect(page).toHaveURL(/\/welcome$/);
    await expect(page.getByRole('banner')).toContainText(user.localPart);

    // AC6: the SDK persisted the session.
    await page.reload();
    await expect(page).toHaveURL(/\/welcome$/);
    await expect(page.getByRole('banner')).toContainText(user.localPart);
  });
});
