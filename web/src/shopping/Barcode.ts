import type { Product } from '@abodenestly/contract';
import { nameKey } from '../lib/nameKey.ts';
import type { ApiClient } from '../core/api/apiClient.ts';
import type { HouseholdDb } from '../sync/db.ts';
import { mutate, type MutateDeps } from '../sync/outbox.ts';
import { addItem } from './QuickAdd.ts';

// SHOP-10: barcode scan to add. Pure logic; the camera and the screen live in BarcodeScanner.tsx.

const CODE_RE = /^\d{8,14}$/;
export const CODE_ERROR = 'Enter 8 to 14 digits';
export const NAMES_KEY = 'abodenestly.barcodeNames';
export const LOOKUPS_KEY = 'abodenestly.barcodeLookups';
export const NAMES_CAP = 200;

/** Same rule as the API (api/_lib/products.ts): 8 to 14 digits, no check-digit test. */
export const isValidCode = (code: string): boolean => CODE_RE.test(code);

function readJson<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(key);
    return raw ? (JSON.parse(raw) as T) : fallback;
  } catch {
    return fallback;
  }
}
function writeJson(key: string, value: unknown): void {
  try {
    localStorage.setItem(key, JSON.stringify(value));
  } catch {
    // Storage full or blocked: the cache and queue are conveniences, the list item itself is already stored.
  }
}

// ---------- names typed for codes the API does not know (DESIGN C7 not-found) ----------

type NameEntry = { code: string; name: string };

// An array, not an object: 8-digit codes are integer-like keys, which an object orders numerically, not by insertion.
// The old object format fails the array check and is ignored.
function readNames(): NameEntry[] {
  const raw = readJson<unknown>(NAMES_KEY, []);
  return Array.isArray(raw) ? (raw as NameEntry[]).filter((e) => typeof e?.code === 'string' && typeof e?.name === 'string') : [];
}

export const cachedName = (code: string): string | null => readNames().find((e) => e.code === code)?.name ?? null;

/** Remembers `code → name` on this device; the oldest entries beyond NAMES_CAP are dropped. */
export function cacheName(code: string, name: string): void {
  const list = readNames().filter((e) => e.code !== code);
  list.push({ code, name });
  writeJson(NAMES_KEY, list.slice(-NAMES_CAP));
}

// ---------- adding ----------

type AddInput = { householdId: string; listId: string; userId: string | null };

/** A found product: its name and barcode (the product row exists now, so the foreign key holds). */
export const addProduct = (db: HouseholdDb, deps: Partial<MutateDeps>, input: AddInput, product: Product): Promise<string> =>
  addItem(db, deps, { ...input, name: product.name, barcode: product.barcode });

/** Not found: the typed name, with no barcode column (product(barcode) has no row), remembered for next time. */
export async function addNamed(db: HouseholdDb, deps: Partial<MutateDeps>, input: AddInput, code: string, name: string): Promise<string> {
  const id = await addItem(db, deps, { ...input, name: name.trim() });
  cacheName(code, name.trim());
  return id;
}

export type PendingLookup = { householdId: string; itemId: string; code: string };

export const pendingLookups = (): PendingLookup[] => readJson<PendingLookup[]>(LOOKUPS_KEY, []);
const savePending = (list: PendingLookup[]) => writeJson(LOOKUPS_KEY, list);

/** Offline: the code becomes the name, no barcode, and the lookup is queued for later. */
export async function addOffline(db: HouseholdDb, deps: Partial<MutateDeps>, input: AddInput, code: string): Promise<string> {
  const id = await addItem(db, deps, { ...input, name: code });
  savePending([...pendingLookups(), { householdId: input.householdId, itemId: id, code }]);
  return id;
}

// ---------- lookup ----------

export type LookupResult = { kind: 'found'; product: Product } | { kind: 'not-found' } | { kind: 'offline' } | { kind: 'error' };

/** Only a missing network or a 502 can pass by itself, so only those count as offline; 401 or 500 are `error`. */
export const isTransient = (code: string): boolean => code === 'network' || code === 'retryable';

export async function lookup(api: ApiClient, code: string, online: boolean): Promise<LookupResult> {
  if (!online) return { kind: 'offline' };
  const res = await api.getProduct(code);
  if (res.ok) return { kind: 'found', product: res.value };
  if (res.error.code === 'not_found') return { kind: 'not-found' };
  return isTransient(res.error.code) ? { kind: 'offline' } : { kind: 'error' };
}

// ---------- queued lookups ----------

let draining = false;

/**
 * Resolves the household's queued lookups. A product renames the item and sets its barcode, but only while the item
 * still exists and is still named after the code, so a rename by the person wins. A 404 drops the entry; a network
 * or 502 failure keeps it for the next drain; any other failure drops it. Entries of other households wait until that household is open.
 */
export async function drainLookups(db: HouseholdDb, deps: Partial<MutateDeps>, api: ApiClient, householdId: string): Promise<void> {
  if (draining || !navigator.onLine) return;
  const mine = pendingLookups().filter((e) => e.householdId === householdId);
  if (mine.length === 0) return;
  draining = true;
  try {
    for (const entry of mine) {
      const res = await api.getProduct(entry.code);
      // Keep the entry only for failures that can pass; anything else would be retried forever.
      if (!res.ok && isTransient(res.error.code)) continue;
      if (res.ok) {
        const row = await db.list_item.get(entry.itemId);
        if (row && row.deleted_at == null && row.name === entry.code) {
          await mutate(db, deps).patch('list_item', row.id, { name: res.value.name, barcode: res.value.barcode }, { name_key: nameKey(res.value.name) });
        }
      }
      // Re-read: the queue may have grown while the lookup was in flight.
      savePending(pendingLookups().filter((e) => !(e.householdId === entry.householdId && e.itemId === entry.itemId)));
    }
  } finally {
    draining = false;
  }
}
