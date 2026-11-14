import { expect, test } from '@playwright/test';
import { adminClient, createUser, deleteUser, signInWithMagicLink, type E2eUser } from './helpers/auth.ts';

// E2E-CORE-1, members half: both users see each other, the last owner cannot leave, and an owner's
// removal of a member is undoable for 5 s and then sticks.
test.describe('E2E-CORE-1: members', () => {
  let userA: E2eUser;
  let userB: E2eUser;

  test.beforeAll(async () => {
    userA = await createUser();
    userB = await createUser();
  });

  test.afterAll(async () => {
    await adminClient().from('household').delete().eq('created_by', userA.id);
    await deleteUser(userA);
    await deleteUser(userB);
  });

  test('CORE-04: both see each other; last owner is blocked; owner removes B after the undo window', async ({ page, browser }, testInfo) => {
    await signInWithMagicLink(page, userA);
    await expect(page).toHaveURL(/\/welcome$/);
    await page.getByLabel('Household name').fill('Members Home');
    await page.getByRole('button', { name: 'Create household' }).click();
    await expect(page).toHaveURL(/\/home$/);

    await page.goto('/household/members');
    await page.getByRole('button', { name: 'Invite' }).click();
    const dialog = page.getByRole('dialog', { name: 'Invite to household' });
    const codeBox = dialog.getByLabel('Invite code');
    await expect(codeBox).toHaveText(/^[0-9a-f]{12}$/);
    const code = (await codeBox.textContent())!;
    await dialog.getByRole('button', { name: 'Close' }).click();

    const base = testInfo.project.use.baseURL!;
    const context = await browser.newContext({ ...testInfo.project.use, baseURL: base });
    try {
      const b = await context.newPage();
      await signInWithMagicLink(b, userB, `/join/${code}`);
      await expect(b).toHaveURL(new RegExp(`/welcome\\?code=${code}$`));
      await b.getByRole('button', { name: 'Join household' }).click();
      await expect(b).toHaveURL(/\/home$/);

      // B is a plain member: both rows, no overflow menus, Leave available.
      await b.goto('/household/members');
      await expect(b.getByRole('listitem')).toHaveCount(2);
      await expect(b.getByText('You', { exact: true })).toHaveCount(1);
      await expect(b.getByRole('button', { name: /^More actions/ })).toHaveCount(0);
      await expect(b.getByRole('button', { name: 'Leave household' })).toBeEnabled();
    } finally {
      await context.close();
    }

    // A is the only owner: both users are listed, and Leave is refused before any request.
    await page.goto('/household/members');
    await expect(page.getByRole('listitem')).toHaveCount(2);
    await page.getByRole('button', { name: 'Leave household' }).click();
    await expect(page.getByRole('alert')).toContainText("You're the last owner");
    await expect(page.getByRole('status')).toHaveCount(0);

    // Remove B: the toast appears, the row is hidden, and after 5 s the member row has left_at set.
    const { data: households } = await adminClient().from('household').select('id').eq('created_by', userA.id);
    const householdId = households![0]!.id;
    await page.getByRole('button', { name: /^More actions for / }).click();
    await page.getByRole('menuitem', { name: 'Remove' }).click();
    await expect(page.getByRole('status')).toContainText('Removed');
    await expect(page.getByRole('listitem')).toHaveCount(1);
    const { data: before } = await adminClient()
      .from('member')
      .select('left_at')
      .eq('household_id', householdId)
      .eq('user_id', userB.id)
      .single();
    expect(before?.left_at).toBeNull();

    await expect
      .poll(
        async () => {
          const { data } = await adminClient()
            .from('member')
            .select('left_at')
            .eq('household_id', householdId)
            .eq('user_id', userB.id)
            .single();
          return data?.left_at ?? null;
        },
        { timeout: 15_000 },
      )
      .not.toBeNull();
    await expect(page.getByRole('listitem')).toHaveCount(1);
  });
});
