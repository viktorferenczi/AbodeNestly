import { expect, test } from '@playwright/test';
import { adminClient, createUser, deleteUser, signInWithMagicLink, type E2eUser } from './helpers/auth.ts';
import { outboxCount } from './helpers/outbox.ts';

// TESTING §6. Headless Chromium has no microphone, so a fake SpeechRecognition stands in for the browser's. It reports
// local processing (the `processLocally` property), which is what lets the sheet listen while offline.
test.describe('SHOP-14: voice add, offline', () => {
  let user: E2eUser;
  let householdId: string;
  let listId: string;

  test.beforeAll(async () => {
    user = await createUser();
    const admin = adminClient();
    const { data, error } = await admin.from('household').insert({ name: 'Voice Offline', created_by: user.id }).select('id').single();
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

  test('"2 litres milk" said offline is added as Milk, 2 l and pushed on reconnect', async ({ page, context }) => {
    await page.addInitScript(() => {
      class FakeSpeechRecognition {
        lang = '';
        interimResults = false;
        continuous = false;
        maxAlternatives = 1;
        #local = false;
        // On the prototype, as in Chromium, so the app's `'processLocally' in prototype` check sees it.
        get processLocally() {
          return this.#local;
        }
        set processLocally(v: boolean) {
          this.#local = v;
        }
        onresult: ((e: unknown) => void) | null = null;
        onerror: ((e: unknown) => void) | null = null;
        onend: (() => void) | null = null;
        start() {
          setTimeout(() => {
            this.onresult?.({ results: [Object.assign([{ transcript: '2 litres milk' }], { isFinal: true })] });
            this.onend?.();
          }, 50);
        }
        abort() {}
      }
      Object.assign(window, { SpeechRecognition: FakeSpeechRecognition });
    });
    await signInWithMagicLink(page, user);
    await page.goto(`/shopping/${listId}`);
    await expect(page.getByRole('combobox', { name: 'Add an item' })).toBeVisible();

    const pushes: string[] = [];
    await page.route('**/sync', (route) => {
      if (route.request().method() === 'POST') pushes.push(route.request().postData() ?? '');
      return route.continue();
    });

    // Offline inside the SPA: no page.goto from here on.
    await context.setOffline(true);
    await page.getByRole('button', { name: 'Voice add' }).click();
    const chips = page.getByRole('list', { name: 'Parsed item' });
    await expect(chips.getByRole('listitem')).toHaveText(['2', 'l', 'Milk']);
    await page.getByRole('dialog').getByRole('button', { name: 'Add' }).click();
    await expect(page.getByRole('dialog')).toBeHidden();
    await expect(page.getByRole('button', { name: /Milk.*2 l/ })).toBeVisible();
    await expect.poll(() => outboxCount(page, householdId)).toBe(1);

    await context.setOffline(false);
    await expect
      .poll(() =>
        pushes
          .flatMap((p) => (JSON.parse(p) as { mutations: { table: string; op: string; data: { name?: string; quantity?: number; unit?: string } }[] }).mutations)
          .some((m) => m.table === 'list_item' && m.op === 'upsert' && m.data.name === 'Milk' && m.data.quantity === 2 && m.data.unit === 'l'),
      )
      .toBe(true);
    await expect.poll(() => outboxCount(page, householdId)).toBe(0);
  });
});
