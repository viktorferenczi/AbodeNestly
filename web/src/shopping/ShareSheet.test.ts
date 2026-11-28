import type { ShoppingList } from '@abodenestly/contract';
import Dexie from 'dexie';
import { beforeEach, describe, expect, it } from 'vitest';
import { openHouseholdDb } from '../sync/db.ts';
import { recordShare } from './ShareSheet.ts';

const list = { id: 'l1', household_id: 'h1', name: 'Groceries', store_id: null, is_template: false, share_token: null, archived_at: null, deleted_at: null, created_at: '2026-01-01T00:00:00Z' } as unknown as ShoppingList;

beforeEach(async () => {
  await Dexie.delete('abodenestly-h1');
});

describe('SHOP-13: the share token is written locally after the API call', () => {
  it('a successful share sets share_token on the row and queues nothing', async () => {
    const db = await openHouseholdDb('h1');
    await db.shopping_list.put(list);
    await recordShare(db, 'l1', 'N'.repeat(43));
    expect((await db.shopping_list.get('l1'))?.share_token).toBe('N'.repeat(43));
    expect(await db.outbox.count()).toBe(0);
    db.close();
  });

  it('stopping clears share_token, so a reopened sheet shows sharing off', async () => {
    const db = await openHouseholdDb('h1');
    await db.shopping_list.put({ ...list, share_token: 'T'.repeat(43) });
    await recordShare(db, 'l1', null);
    expect((await db.shopping_list.get('l1'))?.share_token).toBeNull();
    expect(await db.outbox.count()).toBe(0);
    db.close();
  });
});
