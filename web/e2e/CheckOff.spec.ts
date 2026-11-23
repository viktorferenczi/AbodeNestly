import { expect, test } from '@playwright/test';
import { adminClient, createUser, deleteUser, signInWithMagicLink, type E2eUser } from './helpers/auth.ts';

// E2E-SHOP-1, the "check off, undo, clear checked" part: add items, check one off (undo and keep), clear the rest, server rows end up soft-deleted.
test.describe('SHOP-04: check off and clear checked', () => {
  let user: E2eUser;
  let householdId: string;
  let listId: string;

  test.beforeAll(async () => {
    user = await createUser();
    const admin = adminClient();
    const { data, error } = await admin.from('household').insert({ name: 'Check Off Home', created_by: user.id }).select('id').single();
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

  const serverRows = async () => {
    const { data } = await adminClient().from('list_item').select('name, checked, deleted_at').eq('list_id', listId);
    return data ?? [];
  };

  test('check off, undo, check again, clear checked', async ({ page }) => {
    await signInWithMagicLink(page, user);
    await page.goto(`/shopping/${listId}`);
    const items = page.getByRole('list', { name: 'Items' });
    const bar = page.getByRole('combobox', { name: 'Add an item' });
    for (const name of ['Milk', 'Bread']) {
      await bar.fill(name);
      await bar.press('Enter');
      await expect(items.getByText(name)).toBeVisible();
    }

    await page.getByRole('checkbox', { name: 'Check off Milk' }).click();
    await page.getByRole('button', { name: 'Undo' }).click();
    await expect(page.getByRole('checkbox', { name: 'Check off Milk' })).not.toBeChecked();

    await page.getByRole('checkbox', { name: 'Check off Milk' }).click();
    await expect(page.getByText('Checked (1)')).toBeVisible({ timeout: 10_000 });

    await page.getByRole('checkbox', { name: 'Check off Bread' }).click();
    await expect(page.getByText('All done')).toBeVisible({ timeout: 10_000 });

    await page.getByRole('button', { name: 'Clear checked' }).click();
    await expect(page.getByText('Cleared 2 items')).toBeVisible();
    await expect(page.getByText('Add your first item')).toBeVisible({ timeout: 10_000 });

    await expect
      .poll(async () => {
        const rows = await serverRows();
        return rows.length === 2 && rows.every((r) => r.deleted_at != null && r.checked);
      })
      .toBe(true);
  });
});
