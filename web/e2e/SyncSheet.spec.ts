import { expect, test } from '@playwright/test';
import { adminClient, createUser, deleteUser, signInWithMagicLink, type E2eUser } from './helpers/auth.ts';
import { seedOutbox } from './helpers/outbox.ts';

// SHOP-08-web: the chip and the sync sheet, driven by a change seeded into the outbox. The full add/check
// journey (E2E-SHOP-2) moves to SHOP-02/04-web, which bring the add-item UI.
test.describe('SHOP-08: sync sheet', () => {
  let user: E2eUser;
  let householdId: string;

  test.beforeAll(async () => {
    user = await createUser();
    const admin = adminClient();
    const { data, error } = await admin.from('household').insert({ name: 'Sync Home', created_by: user.id }).select('id').single();
    if (error) throw error;
    householdId = data.id as string;
    const { error: memberError } = await admin.from('member').insert({ household_id: householdId, user_id: user.id, role: 'owner' });
    if (memberError) throw memberError;
  });

  test.afterAll(async () => {
    await adminClient().from('household').delete().eq('id', householdId);
    await deleteUser(user);
  });

  test('chip shows the queued change, Sync now pushes it and the chip hides', async ({ page }) => {
    await signInWithMagicLink(page, user);
    await page.goto('/home');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Sync Home ▾');

    // Hold the push back so the change stays queued across the reload that makes the app see the seeded row.
    await page.route('**/sync', (route) => (route.request().method() === 'POST' ? route.fulfill({ status: 500, body: '{}' }) : route.continue()));
    const listId = crypto.randomUUID();
    await seedOutbox(page, householdId, listId, 'Seeded list');
    await page.reload();
    await expect(page.getByRole('button', { name: '1 to sync' })).toBeVisible();

    await page.unroute('**/sync');
    await page.getByRole('button', { name: '1 to sync' }).click();
    const sheet = page.getByRole('dialog', { name: 'Sync' });
    await expect(sheet.getByText('Pending changes: 1')).toBeVisible();
    await sheet.getByRole('button', { name: 'Sync now' }).click();
    await expect(sheet.getByText('Pending changes: 0')).toBeVisible();
    await sheet.getByRole('button', { name: 'Close' }).click();
    await expect(page.getByRole('button', { name: /to sync|Syncing|Offline/ })).toBeHidden();

    // The server has the list; a second pull brings it back.
    const { data } = await adminClient().from('shopping_list').select('name').eq('id', listId).single();
    expect(data?.name).toBe('Seeded list');
  });
});
