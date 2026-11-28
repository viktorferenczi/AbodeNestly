import type { ItemPrice, Store } from '@abodenestly/contract';
import { nameKey } from '../lib/nameKey.ts';
import type { HouseholdDb } from '../sync/db.ts';
import { mutate, type MutateDeps } from '../sync/outbox.ts';

export { nameKey };

export type PriceParse = { ok: true; value: number | null } | { ok: false };

/** Blank is "no price". Otherwise 0 or more with at most 2 decimals; a comma works as the decimal mark. */
export function parsePrice(text: string): PriceParse {
  const t = text.trim();
  if (t === '') return { ok: true, value: null };
  if (!/^\d+([.,]\d{1,2})?$/.test(t)) return { ok: false };
  return { ok: true, value: Number(t.replace(',', '.')) };
}

/** The newest live observation of `key` at `storeId`: newest `seen_at`, ties to the newest `created_at`, then id. */
export function lastPrice(prices: ItemPrice[], storeId: string, key: string): ItemPrice | null {
  return newest(prices.filter((p) => p.store_id === storeId && p.name_key === key));
}

/** The newest live observation of `key` at any store. */
export function lastPriceAnywhere(prices: ItemPrice[], key: string): ItemPrice | null {
  return newest(prices.filter((p) => p.name_key === key));
}

function newest(rows: ItemPrice[]): ItemPrice | null {
  let best: ItemPrice | null = null;
  for (const p of rows) {
    if (p.deleted_at != null) continue;
    if (!best || cmp(p, best) > 0) best = p;
  }
  return best;
}

const cmp = (a: ItemPrice, b: ItemPrice) => a.seen_at.localeCompare(b.seen_at) || a.created_at.localeCompare(b.created_at) || a.id.localeCompare(b.id);

/** What the row caption and the sheet line show: the observation, and the store's name when that store is still live. */
export type LastBought = { price: number; seen_at: string; storeName: string | null };

/**
 * C2 rule: with a list store, the last price at that store; with none, the newest price at any store.
 * Returns a lookup by item name, so the sheet can ask for a name typed a moment ago.
 */
export function lastBoughtLookup(prices: ItemPrice[], stores: Store[], listStoreId: string | null): (name: string) => LastBought | null {
  const names = new Map(stores.filter((s) => s.deleted_at == null).map((s) => [s.id, s.name]));
  return (name) => {
    const key = nameKey(name);
    if (!key) return null;
    const found = listStoreId ? lastPrice(prices, listStoreId, key) : lastPriceAnywhere(prices, key);
    return found ? { price: found.price, seen_at: found.seen_at, storeName: names.get(found.store_id) ?? null } : null;
  };
}

export const formatMoney = (amount: number, currency: string, locale?: string): string =>
  new Intl.NumberFormat(locale, { style: 'currency', currency }).format(amount);

/** C2 caption: "€1.20 · Test Market"; a deleted store leaves just the price. */
export const priceCaption = (last: LastBought, currency: string, locale?: string): string =>
  last.storeName ? `${formatMoney(last.price, currency, locale)} · ${last.storeName}` : formatMoney(last.price, currency, locale);

/** C3 line: "Last bought: 3 Nov, €1.20 at Test Market". */
export function lastBoughtLine(last: LastBought, currency: string, locale?: string): string {
  const day = new Intl.DateTimeFormat(locale, { day: 'numeric', month: 'short' }).format(new Date(last.seen_at));
  const money = formatMoney(last.price, currency, locale);
  return `Last bought: ${day}, ${money}${last.storeName ? ` at ${last.storeName}` : ''}`;
}

export type PriceInput = { householdId: string; storeId: string; name: string; price: number; now: string };

/** Stores one new observation (each price is kept, SPEC §8 ItemPrice) and queues it through the outbox. */
export async function recordPrice(db: HouseholdDb, deps: Partial<MutateDeps>, input: PriceInput): Promise<string> {
  const id = (deps.uuid ?? (() => crypto.randomUUID()))();
  const row: ItemPrice = {
    id,
    household_id: input.householdId,
    store_id: input.storeId,
    name_key: nameKey(input.name),
    price: input.price,
    seen_at: input.now,
    deleted_at: null,
    created_at: input.now,
    updated_at: input.now,
    sync_seq: 0,
  };
  await mutate(db, deps).upsert('item_price', row);
  return id;
}

/**
 * Runs an item write and its optional price in one local transaction. `write` gets deps without the sync kick,
 * which fires once after the commit so the push never runs ahead of the rows.
 */
export async function saveWithPrice(
  db: HouseholdDb,
  deps: Partial<MutateDeps>,
  price: PriceInput | null,
  write: (quiet: Partial<MutateDeps>) => Promise<unknown>,
): Promise<void> {
  const quiet: Partial<MutateDeps> = { ...deps, onQueued: undefined };
  await db.transaction('rw', db.list_item, db.item_price, db.outbox, async () => {
    await write(quiet);
    if (price) await recordPrice(db, quiet, price);
  });
  deps.onQueued?.();
}
