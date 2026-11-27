import { expect, test } from '@playwright/test';
import { adminClient, createUser, deleteUser, signInWithMagicLink, type E2eUser } from './helpers/auth.ts';

// TESTING §6 for SHOP-09: offline shows the local rows with no realtime error; back online, a change made elsewhere arrives.
test.describe('SHOP-09: realtime offline', () => {
  let user: E2eUser;
  let householdId: string;
  let listId: string;

  test.beforeAll(async () => {
    user = await createUser();
    const admin = adminClient();
    const { data, error } = await admin.from('household').insert({ name: 'Realtime Offline Home', created_by: user.id }).select('id').single();
    if (error) throw error;
    householdId = data.id as string;
    const { error: memberError } = await admin.from('member').insert({ household_id: householdId, user_id: user.id, role: 'owner' });
    if (memberError) throw memberError;
    const { data: list, error: listError } = await admin.from('shopping_list').insert({ household_id: householdId, name: 'Groceries' }).select('id').single();
    if (listError) throw listError;
    listId = list.id as string;
    const { error: itemError } = await admin.from('list_item').insert({ household_id: householdId, list_id: listId, name: 'Milk' });
    if (itemError) throw itemError;
  });

  test.afterAll(async () => {
    await adminClient().from('household').delete().eq('id', householdId);
    await deleteUser(user);
  });

  test('offline keeps the local list without errors; reconnect receives a remote change', async ({ page, context }) => {
    const errors: string[] = [];
    page.on('pageerror', (e) => errors.push(e.message));
    await signInWithMagicLink(page, user);
    await page.goto(`/shopping/${listId}`);
    await expect(page.getByRole('checkbox', { name: 'Check off Milk' })).toBeVisible();

    await context.setOffline(true);
    await expect(page.getByRole('button', { name: 'Offline' })).toBeVisible();
    await expect(page.getByRole('checkbox', { name: 'Check off Milk' })).toBeVisible();
    await expect(page.getByRole('alert')).toHaveCount(0);

    const { error } = await adminClient().from('list_item').update({ checked: true }).eq('list_id', listId).eq('name', 'Milk');
    if (error) throw error;
    await context.setOffline(false);
    await expect(page.getByText('Checked (1)')).toBeVisible({ timeout: 15_000 });
    expect(errors).toEqual([]);
  });
});
