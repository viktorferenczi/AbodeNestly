import { describe, expect, it } from 'vitest';
import { parseSpoken } from './VoiceAdd.ts';

describe('SHOP-14: parseSpoken', () => {
  it.each([
    ['2 litres milk', 2, 'l', 'Milk'],
    ['2 litres of milk', 2, 'l', 'Milk'],
    ['  2   Liters   of  Milk ', 2, 'l', 'Milk'],
    ['2 l milk', 2, 'l', 'Milk'],
    ['half a kilo of flour', 0.5, 'kg', 'Flour'],
    ['a half kilo flour', 0.5, 'kg', 'Flour'],
    ['half a lemon', 0.5, null, 'Lemon'],
    ['1,5 l oat milk', 1.5, 'l', 'Oat milk'],
    ['1.5 kg potatoes', 1.5, 'kg', 'Potatoes'],
    ['three bottles of water', 3, 'bottle', 'Water'],
    ['a can of tomatoes', 1, 'can', 'Tomatoes'],
    ['two tins of beans', 2, 'can', 'Beans'],
    ['500 grams of rice', 500, 'g', 'Rice'],
    ['250ml cream', 250, 'ml', 'Cream'],
    ['twelve pieces of toast', 12, 'pcs', 'Toast'],
    ['milk', null, null, 'Milk'],
    ['constructor milk', null, null, 'Constructor milk'],
    ['toString milk', null, null, 'Tostring milk'],
    ['2 constructor milk', 2, null, 'Constructor milk'],
    ['can opener', null, null, 'Can opener'],
    ['3 apples', 3, null, 'Apples'],
    ['one and a half litres of milk', 1, null, 'And a half litres of milk'],
  ])('%j -> %j %j %j', (text, quantity, unit, name) => {
    expect(parseSpoken(text)).toEqual({ quantity, unit, name });
  });

  it.each([[''], ['   '], ['2 litres'], ['half a kilo']])('%j is a failure', (text) => {
    expect(parseSpoken(text)).toBeNull();
  });
});
