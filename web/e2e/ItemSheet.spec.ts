import { expect, test } from '@playwright/test';
import { adminClient, createUser, deleteUser, signInWithMagicLink, type E2eUser } from './helpers/auth.ts';

// E2E-SHOP-1, the "item details" part: quantity, unit and note through the item sheet, synced to the server.
test.describe('SHOP-03: item sheet', () => {
  let user: E2eUser;
  let householdId: string;
  let listId: string;

  test.beforeAll(async () => {
    user = await createUser();
    const admin = adminClient();
    const { data, error } = await admin.from('household').insert({ name: 'Item Sheet Home', created_by: user.id }).select('id').single();
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

  const serverRow = async () => {
    const { data } = await adminClient().from('list_item').select('quantity, unit, note').eq('list_id', listId).eq('name', 'Milk').maybeSingle();
    return data;
  };

  test('set quantity, unit and note, then clear the note', async ({ page }) => {
    await signInWithMagicLink(page, user);
    await page.goto(`/shopping/${listId}`);
    const items = page.getByRole('list', { name: 'Items' });
    const bar = page.getByRole('combobox', { name: 'Add an item' });

    await bar.fill('Milk');
    await bar.press('Enter');
    await expect(items.getByText('Milk')).toBeVisible();

    await page.getByRole('button', { name: /Milk/ }).click();
    const sheet = page.getByRole('dialog');
    await sheet.getByRole('button', { name: 'Increase quantity' }).click();
    await sheet.getByRole('button', { name: 'Increase quantity' }).click();
    await sheet.getByLabel('Unit').fill('l');
    await sheet.getByLabel('Note').fill('lactose free');
    await sheet.getByRole('button', { name: 'Save' }).click();
    await expect(items.getByText('2 l')).toBeVisible();
    await expect(items.getByText('lactose free')).toBeVisible();

    await page.getByRole('button', { name: /Milk/ }).click();
    await page.getByRole('dialog').getByLabel('Note').fill('');
    await page.getByRole('dialog').getByRole('button', { name: 'Save' }).click();
    await expect(items.getByText('lactose free')).toHaveCount(0);
    await expect(items.getByText('2 l')).toBeVisible();

    await expect.poll(serverRow).toEqual({ quantity: 2, unit: 'l', note: null });
  });
});
