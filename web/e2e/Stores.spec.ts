import { expect, test } from '@playwright/test';
import { adminClient, createUser, deleteUser, signInWithMagicLink, type E2eUser } from './helpers/auth.ts';

// E2E-SHOP-3, first part: create a store and assign it to a list. SHOP-06-web extends this journey with the re-sort.
test.describe('SHOP-05: stores', () => {
  let user: E2eUser;
  let householdId: string;
  let listId: string;

  test.beforeAll(async () => {
    user = await createUser();
    const admin = adminClient();
    const { data, error } = await admin.from('household').insert({ name: 'Stores Home', created_by: user.id }).select('id').single();
    if (error) throw error;
    householdId = data.id as string;
    const { error: memberError } = await admin.from('member').insert({ household_id: householdId, user_id: user.id, role: 'owner' });
    if (memberError) throw memberError;
    const { data: list, error: listError } = await admin.from('shopping_list').insert({ household_id: householdId, name: 'Groceries' }).select('id').single();
    if (listError) throw listError;
    listId = list.id as string;
  });

  test.afterAll(async () => {
    await adminClient().from('household').delete().eq('id', householdId);
    await deleteUser(user);
  });

  test('create a store, assign it to a list', async ({ page }) => {
    await signInWithMagicLink(page, user);
    await page.goto('/shopping');
    await page.getByRole('link', { name: 'Stores' }).click();
    await expect(page.getByText('No stores yet')).toBeVisible();

    await page.getByRole('button', { name: 'New store' }).first().click();
    await page.getByLabel('Name').fill('Test Market');
    await page.getByRole('button', { name: 'Save' }).click();
    await expect(page.getByRole('button', { name: /^Test Market/ })).toBeVisible();

    await page.goto(`/shopping/${listId}`);
    await page.getByRole('button', { name: 'Store: none' }).click();
    await page.getByRole('radio', { name: 'Test Market' }).check();
    await page.getByRole('button', { name: 'Save' }).click();
    await expect(page.getByRole('button', { name: 'Store: Test Market' })).toBeVisible();

    await page.goto('/shopping');
    const row = page.getByRole('button', { name: /^Groceries/ });
    await expect(row).toContainText('Test Market');

    const admin = adminClient();
    await expect
      .poll(async () => {
        const { data: store } = await admin.from('store').select('id').eq('household_id', householdId).eq('name', 'Test Market').maybeSingle();
        const { data: l } = await admin.from('shopping_list').select('store_id').eq('id', listId).single();
        return store && l ? l.store_id === store.id : false;
      })
      .toBe(true);
  });
});
