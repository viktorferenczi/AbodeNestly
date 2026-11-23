import { expect, test } from '@playwright/test';
import { adminClient, createUser, deleteUser, signInWithMagicLink, type E2eUser } from './helpers/auth.ts';
import { outboxCount } from './helpers/outbox.ts';

// TESTING §6: act offline through the UI, see the row and one outbox entry, reconnect, check the push body and an empty outbox.
test.describe('SHOP-05: stores offline', () => {
  let user: E2eUser;
  let householdId: string;

  test.beforeAll(async () => {
    user = await createUser();
    const admin = adminClient();
    const { data, error } = await admin.from('household').insert({ name: 'Stores Offline Home', created_by: user.id }).select('id').single();
    if (error) throw error;
    householdId = data.id as string;
    const { error: memberError } = await admin.from('member').insert({ household_id: householdId, user_id: user.id, role: 'owner' });
    if (memberError) throw memberError;
  });

  test.afterAll(async () => {
    await adminClient().from('household').delete().eq('id', householdId);
    await deleteUser(user);
  });

  test('a store created offline shows at once and is pushed on reconnect', async ({ page, context }) => {
    await signInWithMagicLink(page, user);
    await page.goto('/shopping/stores');
    await expect(page.getByRole('heading', { name: 'Stores' })).toBeVisible();

    const pushes: string[] = [];
    await page.route('**/sync', (route) => {
      if (route.request().method() === 'POST') pushes.push(route.request().postData() ?? '');
      return route.continue();
    });

    await context.setOffline(true);
    await page.getByRole('button', { name: 'New store' }).first().click();
    await page.getByLabel('Name').fill('Corner Shop');
    await page.getByRole('button', { name: 'Save' }).click();
    await expect(page.getByRole('button', { name: /^Corner Shop/ })).toBeVisible();
    await expect.poll(() => outboxCount(page, householdId)).toBe(1);

    await context.setOffline(false);
    await expect.poll(() => pushes.length).toBeGreaterThan(0);
    const mutations = pushes.flatMap((p) => (JSON.parse(p) as { mutations: { table: string; op: string; data: { name?: string } }[] }).mutations);
    expect(mutations).toContainEqual(expect.objectContaining({ table: 'store', op: 'upsert', data: expect.objectContaining({ name: 'Corner Shop' }) }));
    await expect.poll(() => outboxCount(page, householdId)).toBe(0);
  });
});
