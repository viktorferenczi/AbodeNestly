import { expect, test } from '@playwright/test';
import { adminClient, createUser, deleteUser, signInWithMagicLink, type E2eUser } from './helpers/auth.ts';
import { outboxCount } from './helpers/outbox.ts';

// TESTING §6: edit offline through the UI, see the row and one outbox entry, reconnect, check the push body and an empty outbox.
test.describe('SHOP-03: item sheet offline', () => {
  let user: E2eUser;
  let householdId: string;
  let listId: string;

  test.beforeAll(async () => {
    user = await createUser();
    const admin = adminClient();
    const { data, error } = await admin.from('household').insert({ name: 'Item Offline', created_by: user.id }).select('id').single();
    if (error) throw error;
    householdId = data.id as string;
    const { error: memberError } = await admin.from('member').insert({ household_id: householdId, user_id: user.id, role: 'owner' });
    if (memberError) throw memberError;
    const { data: list, error: listError } = await admin.from('shopping_list').insert({ household_id: householdId, name: 'Groceries' }).select('id').single();
    if (listError) throw listError;
    listId = list.id as string;
    const { error: itemError } = await admin.from('list_item').insert({ household_id: householdId, list_id: listId, name: 'Milk', checked: false });
    if (itemError) throw itemError;
  });

  test.afterAll(async () => {
    await adminClient().from('household').delete().eq('id', householdId);
    await deleteUser(user);
  });

  test('a quantity edited offline shows at once and is pushed as one column on reconnect', async ({ page, context }) => {
    await signInWithMagicLink(page, user);
    await page.goto(`/shopping/${listId}`);
    await expect(page.getByRole('list', { name: 'Items' }).getByText('Milk')).toBeVisible();

    const pushes: string[] = [];
    await page.route('**/sync', (route) => {
      if (route.request().method() === 'POST') pushes.push(route.request().postData() ?? '');
      return route.continue();
    });

    await context.setOffline(true);
    await page.getByRole('button', { name: /Milk/ }).click();
    await page.getByRole('textbox', { name: 'Quantity' }).fill('3');
    await page.getByRole('button', { name: 'Save' }).click();
    await expect(page.getByRole('list', { name: 'Items' }).getByText('3', { exact: true })).toBeVisible();
    await expect.poll(() => outboxCount(page, householdId)).toBe(1);

    await context.setOffline(false);
    await expect.poll(() => pushes.length).toBeGreaterThan(0);
    const mutations = pushes.flatMap((p) => (JSON.parse(p) as { mutations: { table: string; op: string; data: Record<string, unknown> }[] }).mutations);
    expect(mutations).toContainEqual(expect.objectContaining({ table: 'list_item', op: 'upsert', data: { quantity: 3 } }));
    await expect.poll(() => outboxCount(page, householdId)).toBe(0);
  });
});
