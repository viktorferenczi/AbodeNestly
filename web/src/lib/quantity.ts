// Quantity text <-> number, shared by the Stepper and the item sheets.

export type QtyParse = { ok: true; value: number | null } | { ok: false };

/** Blank is "no quantity". Otherwise a number above 0 with at most 3 decimals; a comma works as the decimal mark. */
export function parseQuantity(text: string): QtyParse {
  const t = text.trim();
  if (t === '') return { ok: true, value: null };
  if (!/^\d+([.,]\d{1,3})?$/.test(t)) return { ok: false };
  const value = Number(t.replace(',', '.'));
  return value > 0 ? { ok: true, value } : { ok: false };
}

/** The text a quantity is shown as in the field (no float noise, no trailing zeros). */
export const quantityText = (q: number | null): string => (q == null ? '' : String(Math.round(q * 1000) / 1000));

/** Stepper arithmetic on the field text: + on empty gives 1, − below 1 clears. Unparseable text stays as it is. */
export function stepQuantity(text: string, dir: 1 | -1): string {
  const parsed = parseQuantity(text);
  if (!parsed.ok) return text;
  const current = parsed.value;
  if (dir === 1) return quantityText((current ?? 0) + 1);
  if (current == null || current - 1 <= 0) return '';
  return quantityText(current - 1);
}
