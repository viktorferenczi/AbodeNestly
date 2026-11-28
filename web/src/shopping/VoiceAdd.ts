import { UNIT_PRESETS } from './ItemSheet.ts';

export type SpokenItem = { quantity: number | null; unit: string | null; name: string };

// SHOP-14: spoken unit forms, each mapped to a UNIT_PRESETS value. The canonical value is what gets stored, so a
// voice item lists the same as a typed one ("litres" is stored as `l`).
const UNIT_WORDS: Record<string, string> = {
  litre: 'l', litres: 'l', liter: 'l', liters: 'l', l: 'l',
  millilitre: 'ml', millilitres: 'ml', milliliter: 'ml', milliliters: 'ml', ml: 'ml',
  kilo: 'kg', kilos: 'kg', kilogram: 'kg', kilograms: 'kg', kg: 'kg',
  gram: 'g', grams: 'g', g: 'g',
  piece: 'pcs', pieces: 'pcs', pcs: 'pcs',
  pack: 'pack', packs: 'pack', packet: 'pack', packets: 'pack',
  bottle: 'bottle', bottles: 'bottle',
  can: 'can', cans: 'can', tin: 'can', tins: 'can',
};

// One to twelve; "a" and "an" are one. "half" is handled apart because "half a kilo" has a filler word.
const NUMBER_WORDS: Record<string, number> = {
  a: 1, an: 1, one: 1, two: 2, three: 3, four: 4, five: 5, six: 6, seven: 7, eight: 8, nine: 9, ten: 10, eleven: 11, twelve: 12,
};

const DIGITS = /^(\d+(?:[.,]\d+)?)\s*(.*)$/;

/**
 * Parses what the person said ("2 litres of milk") into quantity, unit and name. Pure; the iOS and Android twins copy
 * these rules. Returns null when no name is left. Forms outside the rules ("one and a half") stay in the name.
 */
export function parseSpoken(text: string, locale?: string): SpokenItem | null {
  const spoken = text.trim().toLocaleLowerCase(locale).replace(/\s+/g, ' ');
  let quantity: number | null = null;
  let rest = spoken;

  const digits = DIGITS.exec(spoken);
  if (digits) {
    const n = Number(digits[1]!.replace(',', '.'));
    if (n > 0) {
      quantity = n;
      rest = digits[2]!;
    }
  } else {
    const words = spoken.split(' ');
    const [first, second] = words;
    if (first === 'half' || (first === 'a' && second === 'half')) {
      quantity = 0.5;
      words.splice(0, first === 'half' ? 1 : 2);
      // "half a kilo", "half a lemon": the filler article goes with the quantity.
      if ((words[0] === 'a' || words[0] === 'an') && words.length > 1) words.shift();
      rest = words.join(' ');
    } else if (first !== undefined && Object.hasOwn(NUMBER_WORDS, first) && words.length > 1) {
      quantity = NUMBER_WORDS[first]!;
      rest = words.slice(1).join(' ');
    }
  }

  let unit: string | null = null;
  if (quantity !== null) {
    const words = rest.split(' ').filter(Boolean);
    const mapped = words[0] !== undefined && Object.hasOwn(UNIT_WORDS, words[0]) ? UNIT_WORDS[words[0]] : undefined;
    if (mapped !== undefined && UNIT_PRESETS.includes(mapped)) {
      unit = mapped;
      words.shift();
      if (words[0] === 'of') words.shift();
    }
    rest = words.join(' ');
  }

  if (!rest) return null;
  return { quantity, unit, name: rest.charAt(0).toLocaleUpperCase(locale) + rest.slice(1) };
}
