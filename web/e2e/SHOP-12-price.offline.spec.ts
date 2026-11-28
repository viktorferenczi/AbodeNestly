import { expect, test } from '@playwright/test';
import { adminClient, createUser, deleteUser, signInWithMagicLink, type E2eUser } from './helpers/auth.ts';
import { outboxCount } from './helpers/outbox.ts';

// TESTING §6: act offline through the UI, see the caption and one outbox entry, reconnect, check the push body and an empty outbox.
// Offline the page stays inside the SPA (a page.goto would fail), so the list is reached through the Shopping link.
test.describe('SHOP-12: price memory offline', () => {
  let user: E2eUser;
  let householdId: string;

  test.beforeAll(async () => {
    user = await createUser();
    const admin = adminClient();
    const { data, error } = await admin.from('household').insert({ name: 'Price Offline', created_by: user.id }).select('id').single();
    if (error) throw error;
    householdId = data.id as string;
    const { error: memberError } = await admin.from('member').insert({ household_id: householdId, user_id: user.id, role: 'owner' });
    if (memberError) throw memberError;
    const { data: store, error: storeError } = await admin.from('store').insert({ household_id: householdId, name: 'Test Market' }).select('id').single();
    if (storeError) throw storeError;
    const { data: list, error: listError } = await admin.from('shopping_list').insert({ household_id: householdId, name: 'Groceries', store_id: store.id }).select('id').single();
    if (listError) throw listError;
    const { error: itemError } = await admin.from('list_item').insert({ household_id: householdId, list_id: list.id, name: 'Milk', checked: false });
    if (itemError) throw itemError;
  });

  test.afterAll(async () => {
    await adminClient().from('household').delete().eq('id', householdId);
    await deleteUser(user);
  });

  test('a price entered offline shows in the caption at once and is pushed on reconnect', async ({ page, context }) => {
    await signInWithMagicLink(page, user);
    await page.getByRole('link', { name: 'Shopping' }).first().click();
    await page.getByRole('button', { name: /^Groceries/ }).click();
    await expect(page.getByRole('list', { name: 'Items' }).getByText('Milk')).toBeVisible();

    const pushes: string[] = [];
    await page.route('**/sync', (route) => {
      if (route.request().method() === 'POST') pushes.push(route.request().postData() ?? '');
      return route.continue();
    });

    await context.setOffline(true);
    await page.getByRole('button', { name: /Milk/ }).click();
    await page.getByLabel('Price at Test Market').fill('1,20');
    await page.getByRole('button', { name: 'Save' }).click();
    await expect(page.getByRole('list', { name: 'Items' }).getByText(/1[.,]20.*Test Market/)).toBeVisible();
    await expect.poll(() => outboxCount(page, householdId)).toBe(1);

    await context.setOffline(false);
    await expect.poll(() => pushes.length).toBeGreaterThan(0);
    const mutations = pushes.flatMap((p) => (JSON.parse(p) as { mutations: { table: string; op: string; data: Record<string, unknown> }[] }).mutations);
    expect(mutations).toContainEqual(expect.objectContaining({ table: 'item_price', op: 'upsert', data: expect.objectContaining({ name_key: 'milk', price: 1.2 }) }));
    await expect.poll(() => outboxCount(page, householdId)).toBe(0);
  });
});
