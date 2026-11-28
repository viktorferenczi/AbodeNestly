import { describe, expect, it } from 'vitest';
import { sendableFields } from './outbox.ts';

describe('sendableFields (SHOP-12: name_key is client-sent for item_price only)', () => {
  const row = { id: 'p1', household_id: 'h1', store_id: 's1', name_key: 'milk', price: 1.2, updated_at: 'x', sync_seq: 3 };

  it('an item_price upsert keeps name_key and still drops the other server-owned columns', () => {
    expect(sendableFields('item_price', row)).toEqual({ store_id: 's1', name_key: 'milk', price: 1.2 });
  });

  it('a list_item upsert still drops name_key', () => {
    expect(sendableFields('list_item', { ...row, list_id: 'l1' })).toEqual({ store_id: 's1', price: 1.2, list_id: 'l1' });
  });
});
