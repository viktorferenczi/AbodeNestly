import { expect, test } from '@playwright/test';
import { adminClient, createUser, deleteUser, signInWithMagicLink, type E2eUser } from './helpers/auth.ts';

// E2E-SHOP-1, the "add item" part. SHOP-04 extends it with check off, undo and clear checked.
test.describe('SHOP-02: quick add with autocomplete', () => {
  let user: E2eUser;
  let householdId: string;
  let listId: string;

  test.beforeAll(async () => {
    user = await createUser();
    const admin = adminClient();
    const { data, error } = await admin.from('household').insert({ name: 'Quick Home', created_by: user.id }).select('id').single();
    if (error) throw error;
    householdId = data.id as string;
    const { error: memberError } = await admin.from('member').insert({ household_id: householdId, user_id: user.id, role: 'owner' });
    if (memberError) throw memberError;
    const { data: list, error: listError } = await admin.from('shopping_list').insert({ household_id: householdId, name: 'Groceries' }).select('id').single();
    if (listError) throw listError;
    listId = list.id as string;
    const { error: itemError } = await admin.from('list_item').insert({ household_id: householdId, list_id: listId, name: 'Milk', checked: true });
    if (itemError) throw itemError;
  });

  test.afterAll(async () => {
    await adminClient().from('household').delete().eq('id', householdId);
    await deleteUser(user);
  });

  test('pick a suggestion, then add a new name; both reach the server', async ({ page }) => {
    await signInWithMagicLink(page, user);
    await page.goto(`/shopping/${listId}`);
    const items = page.getByRole('list', { name: 'Items' });
    const bar = page.getByRole('combobox', { name: 'Add an item' });

    await bar.fill('mi');
    await page.getByRole('option', { name: 'Milk' }).click();
    await expect(items.getByText('Milk')).toBeVisible();

    await bar.fill('Lightbulbs');
    await bar.press('Enter');
    await expect(items.getByText('Lightbulbs')).toBeVisible();

    await expect
      .poll(async () => {
        const { data } = await adminClient().from('list_item').select('name, checked').eq('list_id', listId).eq('checked', false);
        return (data ?? []).map((r) => r.name as string).sort();
      })
      .toEqual(['Lightbulbs', 'Milk']);
  });
});
