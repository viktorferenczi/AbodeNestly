import { expect, test } from '@playwright/test';
import { adminClient, createUser, deleteUser, signInWithMagicLink, type E2eUser } from './helpers/auth.ts';

// E2E-SHOP-4: a share link opens read-only without sign-in.
test.describe('SHOP-13: share link', () => {
  let user: E2eUser;
  let householdId: string;

  test.beforeAll(async () => {
    user = await createUser();
    const admin = adminClient();
    const { data, error } = await admin.from('household').insert({ name: 'Share Home', created_by: user.id }).select('id').single();
    if (error) throw error;
    householdId = data.id as string;
    const { error: memberError } = await admin.from('member').insert({ household_id: householdId, user_id: user.id, role: 'owner' });
    if (memberError) throw memberError;
    const { data: list, error: listError } = await admin.from('shopping_list').insert({ household_id: householdId, name: 'Groceries' }).select('id').single();
    if (listError) throw listError;
    const { error: itemError } = await admin.from('list_item').insert([
      { household_id: householdId, list_id: list.id, name: 'Milk', name_key: 'milk', quantity: 2 },
      { household_id: householdId, list_id: list.id, name: 'Bread', name_key: 'bread' },
      { household_id: householdId, list_id: list.id, name: 'Secret eggs', name_key: 'secret eggs', checked: true },
    ]);
    if (itemError) throw itemError;
  });

  test.afterAll(async () => {
    await adminClient().from('household').delete().eq('id', householdId);
    await deleteUser(user);
  });

  test('a signed-out visitor sees the unchecked items; Stop sharing expires the link', async ({ page, browser }) => {
    await signInWithMagicLink(page, user);
    await page.goto('/shopping');
    await page.getByRole('button', { name: /^Groceries/ }).first().click();
    await page.getByRole('button', { name: 'List actions' }).click();
    await page.getByRole('menuitem', { name: 'Share' }).click();

    const dialog = page.getByRole('dialog', { name: 'Share list' });
    await expect(dialog.getByRole('switch', { name: 'Share with a link' })).not.toBeChecked();
    await dialog.getByRole('switch', { name: 'Share with a link' }).check();
    const link = dialog.getByRole('textbox', { name: 'Share link' });
    await expect(link).toBeVisible();
    const url = await link.inputValue();
    expect(url).toContain('/shared/');

    const visitor = await browser.newContext();
    const anonymous = await visitor.newPage();
    await anonymous.goto(url);
    await expect(anonymous.getByRole('heading', { name: 'Groceries' })).toBeVisible();
    await expect(anonymous.getByText('Milk · 2')).toBeVisible();
    await expect(anonymous.getByText('Bread')).toBeVisible();
    await expect(anonymous.getByText('Secret eggs')).toHaveCount(0);
    await expect(anonymous.getByRole('button')).toHaveCount(0);

    await dialog.getByRole('button', { name: 'Stop sharing' }).click();
    await expect(dialog.getByRole('switch', { name: 'Share with a link' })).not.toBeChecked();
    await anonymous.reload();
    await expect(anonymous.getByRole('heading', { name: 'This link has expired' })).toBeVisible();
    await visitor.close();
  });
});
