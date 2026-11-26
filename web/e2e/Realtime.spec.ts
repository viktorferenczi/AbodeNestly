import { expect, test } from '@playwright/test';
import { adminClient, createUser, deleteUser, signInWithMagicLink, type E2eUser } from './helpers/auth.ts';

// E2E-SHOP-2, the realtime part: B checks off Milk and A sees it under Checked without a reload.
test.describe('SHOP-09: realtime check-offs', () => {
  let a: E2eUser;
  let b: E2eUser;
  let householdId: string;
  let listId: string;

  test.beforeAll(async () => {
    a = await createUser();
    b = await createUser();
    const admin = adminClient();
    const { data, error } = await admin.from('household').insert({ name: 'Realtime Home', created_by: a.id }).select('id').single();
    if (error) throw error;
    householdId = data.id as string;
    const { error: memberError } = await admin.from('member').insert([
      { household_id: householdId, user_id: a.id, role: 'owner' },
      { household_id: householdId, user_id: b.id, role: 'member' },
    ]);
    if (memberError) throw memberError;
    const { data: list, error: listError } = await admin.from('shopping_list').insert({ household_id: householdId, name: 'Groceries' }).select('id').single();
    if (listError) throw listError;
    listId = list.id as string;
    const { error: itemError } = await admin.from('list_item').insert({ household_id: householdId, list_id: listId, name: 'Milk' });
    if (itemError) throw itemError;
  });

  test.afterAll(async () => {
    await adminClient().from('household').delete().eq('id', householdId);
    await deleteUser(a);
    await deleteUser(b);
  });

  test('another member checks off Milk and it moves to Checked here without a reload', async ({ page, browser }, testInfo) => {
    await signInWithMagicLink(page, a);
    await page.goto(`/shopping/${listId}`);
    await expect(page.getByRole('checkbox', { name: 'Check off Milk' })).toBeVisible();

    const context = await browser.newContext({ ...testInfo.project.use, baseURL: testInfo.project.use.baseURL! });
    try {
      const other = await context.newPage();
      await signInWithMagicLink(other, b);
      await other.goto(`/shopping/${listId}`);
      await other.getByRole('checkbox', { name: 'Check off Milk' }).click();
      await expect(other.getByText('Checked (1)')).toBeVisible({ timeout: 10_000 });
    } finally {
      await context.close();
    }

    await expect(page.getByText('Checked (1)')).toBeVisible({ timeout: 10_000 });
    await expect(page.getByRole('checkbox', { name: 'Uncheck Milk' })).toBeChecked();
  });
});
