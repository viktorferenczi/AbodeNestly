import { expect, test } from '@playwright/test';
import { adminClient, createUser, deleteUser, signInWithMagicLink, type E2eUser } from './helpers/auth.ts';

// E2E-CORE-2: one user, two households, each on its own local database; switching works offline.
test.describe('E2E-CORE-2: household switcher', () => {
  let user: E2eUser;
  const ids: string[] = [];

  test.beforeAll(async () => {
    user = await createUser();
    const admin = adminClient();
    for (const [name, pantry] of [['Switch Alpha', true], ['Switch Beta', false]] as const) {
      const { data, error } = await admin
        .from('household')
        .insert({ name, created_by: user.id, module_pantry: pantry })
        .select('id')
        .single();
      if (error) throw error;
      ids.push(data.id as string);
      const { error: memberError } = await admin.from('member').insert({ household_id: data.id, user_id: user.id, role: 'owner' });
      if (memberError) throw memberError;
    }
  });

  test.afterAll(async () => {
    await adminClient().from('household').delete().in('id', ids);
    await deleteUser(user);
  });

  test('CORE-07: switch keeps the tab and the data stays per household, online and offline', async ({ page, context }) => {
    await signInWithMagicLink(page, user);
    await expect(page).toHaveURL(/\/(shopping|home)$/);
    await page.goto('/shopping');
    const title = page.getByRole('heading', { level: 1 });
    // The heading reads "AbodeNestly" until the household has loaded.
    await expect(title).toHaveText(/Switch (Alpha|Beta) ▾/);
    const current = (await title.textContent())!.replace(' ▾', '');
    const [here, other] = current === 'Switch Alpha' ? ['Switch Alpha', 'Switch Beta'] : ['Switch Beta', 'Switch Alpha'];
    const idOf = (name: string) => ids[name === 'Switch Alpha' ? 0 : 1]!;

    const put = (id: string, name: string) =>
      page.evaluate(
        ([dbId, listName]) =>
          new Promise<void>((resolve, reject) => {
            const open = indexedDB.open(`abodenestly-${dbId}`);
            open.onerror = () => reject(open.error);
            open.onsuccess = () => {
              const tx = open.result.transaction('shopping_list', 'readwrite');
              tx.objectStore('shopping_list').put({ id: listName, household_id: dbId, name: listName, deleted_at: null });
              tx.oncomplete = () => (open.result.close(), resolve());
            };
          }),
        [id, name] as const,
      );
    const names = (id: string) =>
      page.evaluate(
        (dbId) =>
          new Promise<string[]>((resolve, reject) => {
            const open = indexedDB.open(`abodenestly-${dbId}`);
            open.onerror = () => reject(open.error);
            open.onsuccess = () => {
              const req = open.result.transaction('shopping_list').objectStore('shopping_list').getAll();
              req.onsuccess = () => (open.result.close(), resolve((req.result as Array<{ name: string }>).map((r) => r.name)));
            };
          }),
        id,
      );

    // Switch to the other household from the Shopping tab.
    await title.click();
    const sheet = page.getByRole('dialog', { name: 'Households' });
    await expect(sheet.getByRole('button', { name: new RegExp(here) })).toHaveAttribute('aria-current', 'true');
    await sheet.getByRole('button', { name: new RegExp(other) }).click();
    await expect(title).toHaveText(`${other} ▾`);
    await expect(page).toHaveURL(/\/shopping$/);

    // Both local databases exist; each holds only its own list.
    const dbs = (await page.evaluate(() => indexedDB.databases())).map((d) => d.name);
    expect(dbs).toEqual(expect.arrayContaining(ids.map((id) => `abodenestly-${id}`)));
    await put(idOf(other), 'list-in-other');
    await put(idOf(here), 'list-in-here');
    expect(await names(idOf(other))).toEqual(['list-in-other']);
    expect(await names(idOf(here))).toEqual(['list-in-here']);

    // B5 shows the household that is now current.
    await page.goto('/household');
    await expect(page.getByLabel('Household name')).toHaveValue(other);

    // Offline: the switch back still completes from the local databases.
    await context.setOffline(true);
    await page.getByRole('heading', { level: 1 }).click();
    await page.getByRole('dialog', { name: 'Households' }).getByRole('button', { name: new RegExp(here) }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(`${here} ▾`);
    await expect(page).toHaveURL(/\/household$/);
    await context.setOffline(false);
  });
});
