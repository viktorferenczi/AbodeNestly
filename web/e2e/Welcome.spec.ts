import { expect, test } from '@playwright/test';
import { adminClient, createUser, deleteUser, signInWithMagicLink, type E2eUser } from './helpers/auth.ts';

// E2E-CORE-1, create half. (Invite/join arrives with CORE-03.)
test.describe('E2E-CORE-1: create a household', () => {
  let user: E2eUser;

  test.beforeAll(async () => {
    user = await createUser();
  });

  test.afterAll(async () => {
    // Household rows cascade from household; delete it before the user that created it.
    await adminClient().from('household').delete().eq('created_by', user.id);
    await deleteUser(user);
  });

  test('CORE-02: create from /welcome lands on /home with defaults seeded', async ({ page }) => {
    await signInWithMagicLink(page, user);
    await expect(page).toHaveURL(/\/welcome$/);
    await expect(page.getByRole('heading', { level: 1 })).toContainText(user.localPart);

    await page.getByLabel('Household name').fill('Test Home');
    await page.getByRole('button', { name: 'Create household' }).click();
    await expect(page).toHaveURL(/\/home$/);

    // Web has no local DB yet, so defaults (AC2) are checked straight in Postgres.
    const admin = adminClient();
    const { data: households } = await admin.from('household').select('id').eq('created_by', user.id).eq('name', 'Test Home');
    expect(households).toHaveLength(1);
    const householdId = households![0]!.id as string;

    const { data: lists } = await admin.from('shopping_list').select('name').eq('household_id', householdId);
    expect(lists?.map((l) => l.name)).toEqual(['Groceries']);

    const { data: locations } = await admin.from('location').select('name').eq('household_id', householdId);
    expect(locations?.map((l) => l.name).sort()).toEqual(['Freezer', 'Fridge', 'Pantry']);
  });
});
