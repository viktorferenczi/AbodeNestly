import { expect, test } from '@playwright/test';
import { adminClient, createUser, deleteUser, signInWithMagicLink, type E2eUser } from './helpers/auth.ts';
import { outboxCount, seedOutbox } from './helpers/outbox.ts';

// TESTING §6 steps 2 and 5-6 (offline, reconnect, push body, empty outbox). Steps 3-4 (act through the UI, assert the local row and outbox row) need the add-item UI of SHOP-02-web, so the outbox row is seeded through IndexedDB here and the unit tests cover the one-transaction write (AC1).
test.describe('SHOP-08: offline outbox', () => {
  let user: E2eUser;
  let householdId: string;

  test.beforeAll(async () => {
    user = await createUser();
    const admin = adminClient();
    const { data, error } = await admin.from('household').insert({ name: 'Offline Home', created_by: user.id }).select('id').single();
    if (error) throw error;
    householdId = data.id as string;
    const { error: memberError } = await admin.from('member').insert({ household_id: householdId, user_id: user.id, role: 'owner' });
    if (memberError) throw memberError;
  });

  test.afterAll(async () => {
    await adminClient().from('household').delete().eq('id', householdId);
    await deleteUser(user);
  });

  test('offline chip, then reconnect pushes the queued mutation and empties the outbox', async ({ page, context }) => {
    await signInWithMagicLink(page, user);
    await page.goto('/home');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Offline Home ▾');

    const pushes: string[] = [];
    await page.route('**/sync', (route) => {
      if (route.request().method() === 'POST') pushes.push(route.request().postData() ?? '');
      return route.continue();
    });

    await context.setOffline(true);
    await expect(page.getByRole('button', { name: 'Offline' })).toBeVisible();
    const listId = crypto.randomUUID();
    await seedOutbox(page, householdId, listId, 'Made offline');

    await context.setOffline(false);
    await expect.poll(() => pushes.length).toBe(1);
    expect(JSON.parse(pushes[0]!).mutations[0]).toMatchObject({ table: 'shopping_list', op: 'upsert', row_id: listId });
    await expect.poll(() => outboxCount(page, householdId)).toBe(0);
    await expect(page.getByRole('button', { name: /to sync|Syncing|Offline/ })).toBeHidden();
  });
});
