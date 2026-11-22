import { describe, expect, it } from 'vitest';
import { nameKey } from './nameKey.ts';

describe('nameKey (parity with the SQL name_key)', () => {
  it.each([
    ['  Oat   Milk ', 'oat milk'],
    ['MILK', 'milk'],
    ['Oat\t\nMilk', 'oat milk'],
    ['milk', 'milk'],
    ['   ', ''],
  ])('%j → %j', (input, expected) => {
    expect(nameKey(input)).toBe(expected);
  });
});
