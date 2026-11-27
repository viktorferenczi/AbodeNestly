import { expect, test } from '@playwright/test';
import { adminClient, createUser, deleteUser, signInWithMagicLink, type E2eUser } from './helpers/auth.ts';
import { outboxCount } from './helpers/outbox.ts';

// TESTING §6.
test.describe('SHOP-11: template lists, offline', () => {
  let user: E2eUser;
  let householdId: string;
  let listId: string;

  test.beforeAll(async () => {
    user = await createUser();
    const admin = adminClient();
    const { data, error } = await admin.from('household').insert({ name: 'Templates Offline', created_by: user.id }).select('id').single();
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

  test('a template with two items is added to Groceries offline and pushed on reconnect', async ({ page, context }) => {
    await signInWithMagicLink(page, user);
    await page.goto('/shopping');
    await expect(page.getByRole('heading', { name: 'Lists' })).toBeVisible();

    const pushes: string[] = [];
    await page.route('**/sync', (route) => {
      if (route.request().method() === 'POST') pushes.push(route.request().postData() ?? '');
      return route.continue();
    });

    await context.setOffline(true);
    await page.getByRole('button', { name: 'New list' }).first().click();
    await page.getByLabel('Name').fill('Weekly');
    await page.getByRole('switch', { name: 'Template' }).check();
    await page.getByRole('button', { name: 'Save' }).click();
    await expect(page.getByText('Template', { exact: true })).toBeVisible();

    const add = page.getByRole('combobox', { name: 'Add an item' });
    for (const name of ['Milk', 'Bread']) {
      await add.fill(name);
      await add.press('Enter');
      await expect(page.getByRole('list', { name: 'Items' }).getByText(name)).toBeVisible();
    }
    // The template row and its two items are queued.
    await expect.poll(() => outboxCount(page, householdId)).toBe(3);

    await page.getByRole('button', { name: 'List actions' }).click();
    await page.getByRole('menuitem', { name: 'Add all to list' }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Groceries' }).click();
    await expect(page.getByText('Added 2 items to Groceries')).toBeVisible();
    await expect.poll(() => outboxCount(page, householdId)).toBe(5);

    // Stay in the SPA: an offline page.goto is a new document request and fails (there is no service worker).
    await page.getByRole('link', { name: 'Shopping' }).first().click();
    await page.getByRole('button', { name: /^Groceries/ }).first().click();
    const rows = page.getByRole('list', { name: 'Items' });
    await expect(rows.getByText('Milk')).toBeVisible();
    await expect(rows.getByText('Bread')).toBeVisible();

    await context.setOffline(false);
    await expect.poll(() => outboxCount(page, householdId)).toBe(0);
    const mutations = pushes.flatMap((p) => (JSON.parse(p) as { mutations: { table: string; op: string; row_id: string; data: { list_id?: string } }[] }).mutations);
    expect(mutations.filter((m) => m.table === 'list_item' && m.op === 'upsert' && m.data.list_id === listId)).toHaveLength(2);
  });
});
