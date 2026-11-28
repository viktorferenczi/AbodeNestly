import type { SharedListResponse } from '@abodenestly/contract';
import { describe, expect, it } from 'vitest';
import { mapPublicList } from './mapPublicList.ts';

describe('SHOP-13: mapPublicList', () => {
  it('keeps the name and each item name and quantity in order, including a null quantity', () => {
    const response: SharedListResponse = {
      list: { name: 'Groceries' },
      items: [
        { name: 'Milk', quantity: 2 },
        { name: 'Bread', quantity: null },
        { name: 'Eggs', quantity: 12 },
      ],
    };
    expect(mapPublicList(response)).toEqual({
      name: 'Groceries',
      items: [
        { name: 'Milk', quantity: 2 },
        { name: 'Bread', quantity: null },
        { name: 'Eggs', quantity: 12 },
      ],
    });
  });

  it('drops checked items and any household or extra fields', () => {
    const response = {
      list: { name: 'Groceries', id: 'l1', household_id: 'h1', share_token: 't' },
      household: { name: 'Home' },
      items: [
        { name: 'Milk', quantity: 2, checked: false, id: 'i1', household_id: 'h1', note: 'oat' },
        { name: 'Bread', quantity: 1, checked: true, id: 'i2' },
      ],
    } as unknown as SharedListResponse;
    const mapped = mapPublicList(response);
    expect(mapped).toEqual({ name: 'Groceries', items: [{ name: 'Milk', quantity: 2 }] });
    expect(JSON.stringify(mapped)).not.toMatch(/h1|share_token|household|oat|Bread/);
  });
});
