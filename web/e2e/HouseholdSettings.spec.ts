import { expect, test } from '@playwright/test';
import { adminClient, createUser, deleteUser, signInWithMagicLink, type E2eUser } from './helpers/auth.ts';

// E2E-CORE-3. The "list has no pantry badges" step needs the shopping list and X7, so it is a fixme.
test.describe('E2E-CORE-3: household module toggles', () => {
  let owner: E2eUser;
  let member: E2eUser;
  let householdId: string;

  test.beforeAll(async () => {
    owner = await createUser();
    member = await createUser();
    const admin = adminClient();
    const { data, error } = await admin.from('household').insert({ name: 'Toggle Home', created_by: owner.id }).select('id').single();
    if (error) throw error;
    householdId = data.id;
    // `create_household` adds the owner row; inserting directly as service role skips it, so add both.
    const { error: memberError } = await admin.from('member').insert([
      { household_id: householdId, user_id: owner.id, role: 'owner' },
      { household_id: householdId, user_id: member.id, role: 'member' },
    ]);
    if (memberError) throw memberError;
  });

  test.afterAll(async () => {
    await adminClient().from('household').delete().eq('id', householdId);
    await deleteUser(owner);
    await deleteUser(member);
  });

  test('CORE-05: owner turns Pantry off and the tab disappears without a reload', async ({ page }) => {
    await signInWithMagicLink(page, owner);
    await expect(page).toHaveURL(/\/(shopping|home)$/);
    await page.goto('/household');
    await expect(page.getByRole('link', { name: 'Pantry' })).toBeVisible();
    await page.getByRole('switch', { name: 'Pantry' }).click();
    await expect(page.getByRole('link', { name: 'Pantry' })).toHaveCount(0);
    await expect(page.getByRole('switch', { name: 'Pantry' })).not.toBeChecked();
    await expect.poll(async () => {
      const { data } = await adminClient().from('household').select('module_pantry').eq('id', householdId).single();
      return data?.module_pantry;
    }).toBe(false);
  });

  test.fixme('CORE-05: the shopping list shows no pantry badges while Pantry is off', async () => {
    // Needs SHOP list UI and X7 cross-module badges.
  });

  test('CORE-05: a member leaves and lands on /welcome', async ({ page }) => {
    await signInWithMagicLink(page, member);
    // Landing must pick the household (and store it) before /household can read it.
    await expect(page).toHaveURL(/\/(shopping|pantry|maintenance|home)$/);
    await page.goto('/household');
    await expect(page.getByLabel('Household name')).toHaveValue('Toggle Home');
    await expect(page.getByRole('switch', { name: 'Shopping' })).toBeDisabled();
    await page.getByRole('button', { name: 'Leave household' }).click();
    await page.getByRole('dialog', { name: 'Leave Toggle Home?' }).getByRole('button', { name: 'Leave' }).click();
    await expect(page).toHaveURL(/\/welcome$/);
    const { data } = await adminClient().from('member').select('left_at').eq('household_id', householdId).eq('user_id', member.id).single();
    expect(data?.left_at).not.toBeNull();
  });
});
