import type { Page } from '@playwright/test';

/** Queues a shopping_list upsert straight into the household's IndexedDB outbox (no add-item UI exists before SHOP-02-web). */
export async function seedOutbox(page: Page, householdId: string, listId: string, name: string): Promise<void> {
  await page.evaluate(
    ([hid, id, listName]) =>
      new Promise<void>((resolve, reject) => {
        const open = indexedDB.open(`abodenestly-${hid}`);
        open.onerror = () => reject(open.error);
        open.onsuccess = () => {
          const tx = open.result.transaction('outbox', 'readwrite');
          tx.objectStore('outbox').add({
            table: 'shopping_list',
            op: 'upsert',
            payload: {
              id: crypto.randomUUID(),
              table: 'shopping_list',
              op: 'upsert',
              row_id: id,
              data: { name: listName },
              client_ts: new Date().toISOString(),
            },
          });
          tx.oncomplete = () => (open.result.close(), resolve());
          tx.onerror = () => reject(tx.error);
        };
      }),
    [householdId, listId, name] as const,
  );
}

export async function outboxCount(page: Page, householdId: string): Promise<number> {
  return page.evaluate(
    (hid) =>
      new Promise<number>((resolve, reject) => {
        const open = indexedDB.open(`abodenestly-${hid}`);
        open.onerror = () => reject(open.error);
        open.onsuccess = () => {
          const req = open.result.transaction('outbox').objectStore('outbox').count();
          req.onsuccess = () => (open.result.close(), resolve(req.result));
        };
      }),
    householdId,
  );
}
