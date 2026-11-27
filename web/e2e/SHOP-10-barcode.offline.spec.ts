import { expect, test } from '@playwright/test';
import { adminClient, createUser, deleteUser, signInWithMagicLink, type E2eUser } from './helpers/auth.ts';
import { outboxCount } from './helpers/outbox.ts';

const CODE = '3017620422003';

// TESTING §6. Headless Chromium has BarcodeDetector but no camera, so getUserMedia fails and the scanner shows its manual code field.
test.describe('SHOP-10: barcode scan to add, offline', () => {
  let user: E2eUser;
  let householdId: string;
  let listId: string;

  test.beforeAll(async () => {
    user = await createUser();
    const admin = adminClient();
    const { data, error } = await admin.from('household').insert({ name: 'Barcode Offline', created_by: user.id }).select('id').single();
    if (error) throw error;
    householdId = data.id as string;
    const { error: memberError } = await admin.from('member').insert({ household_id: householdId, user_id: user.id, role: 'owner' });
    if (memberError) throw memberError;
    const { data: list, error: listError } = await admin.from('shopping_list').insert({ household_id: householdId, name: 'Groceries' }).select('id').single();
    if (listError) throw listError;
    listId = list.id as string;
    // The API writes this row when a lookup succeeds; the later rename pushes barcode, which has a foreign key to it.
    const { error: productError } = await admin.from('product').upsert({ barcode: CODE, name: 'Nutella', brand: 'Ferrero', source: 'openfoodfacts' });
    if (productError) throw productError;
  });

  test.afterAll(async () => {
    const admin = adminClient();
    await admin.from('household').delete().eq('id', householdId);
    await admin.from('product').delete().eq('barcode', CODE);
    await deleteUser(user);
  });

  test('a code entered offline is added by its number and renamed once the lookup works', async ({ page, context }) => {
    await signInWithMagicLink(page, user);
    await page.goto(`/shopping/${listId}`);
    await expect(page.getByRole('combobox', { name: 'Add an item' })).toBeVisible();

    const pushes: string[] = [];
    await page.route('**/sync', (route) => {
      if (route.request().method() === 'POST') pushes.push(route.request().postData() ?? '');
      return route.continue();
    });

    await context.setOffline(true);
    await page.getByRole('button', { name: 'Scan barcode' }).click();
    await page.getByLabel('Barcode').fill(CODE);
    await page.getByRole('button', { name: 'Look up' }).click();
    await expect(page.getByText(`Added ${CODE}. We'll look it up when you're back online`)).toBeVisible();
    await page.getByRole('button', { name: 'Done' }).click();
    await expect(page.getByRole('list', { name: 'Items' }).getByText(CODE)).toBeVisible();
    await expect.poll(() => outboxCount(page, householdId)).toBe(1);

    // The lookup answers with the product once the network is back.
    await page.route('**/products/*', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ barcode: CODE, name: 'Nutella', brand: 'Ferrero', category: null, image_url: null, default_unit: null }),
      }),
    );
    await context.setOffline(false);
    await expect(page.getByRole('list', { name: 'Items' }).getByText('Nutella')).toBeVisible();
    await expect
      .poll(() =>
        pushes
          .flatMap((p) => (JSON.parse(p) as { mutations: { table: string; op: string; data: { name?: string; barcode?: string } }[] }).mutations)
          .some((m) => m.table === 'list_item' && m.op === 'upsert' && m.data.name === 'Nutella' && m.data.barcode === CODE),
      )
      .toBe(true);
    await expect.poll(() => outboxCount(page, householdId)).toBe(0);
  });
});
