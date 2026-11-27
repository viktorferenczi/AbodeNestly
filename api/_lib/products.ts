import pg from 'pg';
import type { JWTPayload } from 'jose';
import type { Product } from '@abodenestly/contract';
import { withUser } from './db.ts';
import { ApiError } from './errors.ts';
import type { Queryable } from './queryable.ts';

// EAN-8, UPC-A, EAN-13 and GTIN-14 are 8 to 14 digits. No checksum test: Open Food Facts holds
// codes with wrong check digits and the user scanned what is printed.
const BARCODE_RE = /^\d{8,14}$/;
export const isBarcode = (s: string): boolean => BARCODE_RE.test(s);

const DEFAULT_OFF_BASE_URL = 'https://world.openfoodfacts.org';
const DEFAULT_USER_AGENT = 'AbodeNestly/1.0 (+https://github.com/viktorferenczi/AbodeNestly)';
const OFF_FIELDS = 'product_name,product_name_en,generic_name,brands,categories_tags,image_front_url';
const OFF_TIMEOUT_MS = 3000;

type OffProduct = {
  product_name?: unknown;
  product_name_en?: unknown;
  generic_name?: unknown;
  brands?: unknown;
  categories_tags?: unknown;
  image_front_url?: unknown;
};

const text = (v: unknown): string | null => (typeof v === 'string' && v.trim() ? v.trim() : null);

// What the cache stores: the Product plus where it came from. `source` is never sent to clients.
export type CachedProduct = Product & { source: string };

// Null when OFF has no usable name: a product without one cannot become a list item.
export function mapOffProduct(barcode: string, p: OffProduct): CachedProduct | null {
  const name = text(p.product_name) ?? text(p.product_name_en) ?? text(p.generic_name);
  if (!name) return null;
  const brand = text(typeof p.brands === 'string' ? p.brands.split(',')[0] : null);
  const tag = Array.isArray(p.categories_tags) ? text(p.categories_tags[0]) : null;
  const category = tag ? text(tag.replace(/^[a-z]{2,3}:/, '').replaceAll('-', ' ')) : null;
  const image = text(p.image_front_url);
  return {
    barcode,
    name,
    brand,
    category,
    image_url: image?.startsWith('https://') ? image : null,
    default_unit: null, // OFF has no clean unit; clients apply their own default
    source: 'openfoodfacts',
  };
}

// Returns null for "OFF does not know it" (a 404 for the caller) and throws 502 when OFF cannot be
// asked, so the client can offer manual entry. Neither case is cached.
export async function fetchOffProduct(barcode: string, doFetch: typeof fetch): Promise<CachedProduct | null> {
  const base = (process.env.OFF_BASE_URL || DEFAULT_OFF_BASE_URL).replace(/\/+$/, '');
  const url = `${base}/api/v2/product/${barcode}.json?fields=${OFF_FIELDS}`;
  const unavailable = () => new ApiError(502, 'upstream_unavailable', 'Product lookup is unavailable');
  let res: Response;
  try {
    res = await doFetch(url, {
      headers: { 'user-agent': process.env.OPENFOODFACTS_USER_AGENT || DEFAULT_USER_AGENT, accept: 'application/json' },
      signal: AbortSignal.timeout(OFF_TIMEOUT_MS),
    });
  } catch {
    throw unavailable();
  }
  if (res.status === 404) return null;
  if (!res.ok) throw unavailable();
  let body: { status?: unknown; product?: OffProduct | null };
  try {
    body = (await res.json()) as typeof body;
  } catch {
    throw unavailable();
  }
  if (body?.status !== 1 || !body.product || typeof body.product !== 'object') return null;
  return mapOffProduct(barcode, body.product);
}

export const toProduct = (p: CachedProduct | Product): Product => ({
  barcode: p.barcode,
  name: p.name,
  brand: p.brand,
  category: p.category,
  image_url: p.image_url,
  default_unit: p.default_unit,
});

const COLUMNS = 'barcode, name, brand, category, image_url, default_unit';

// Runs inside withUser: the product_read policy lets any authenticated user read the cache.
export async function readCachedProduct(client: Queryable, barcode: string): Promise<Product | null> {
  const res = await client.query(`select ${COLUMNS} from product where barcode = $1`, [barcode]);
  return res.rows[0] ? toProduct(res.rows[0]) : null;
}

// One of the SPEC §11 exceptions: `product` has no write policy, so the cache fill cannot run as the
// user; it runs as `service_role`, the role §11 reserves for product cache writes. A small pool private
// to this module; one transaction (the pooler is in transaction mode, so `set local` must share it)
// switches role and runs this one upsert. Never exported as a general bypass.
let writePool: pg.Pool | undefined;

export async function writeCachedProduct(product: CachedProduct): Promise<void> {
  writePool ??= new pg.Pool({ connectionString: process.env.DATABASE_URL, max: 2 });
  const client = await writePool.connect();
  try {
    await client.query('begin');
    await client.query('set local role service_role');
    // Upsert so two lookups racing on the same barcode both succeed.
    await client.query(
      `insert into product (barcode, name, brand, category, image_url, default_unit, source)
       values ($1, $2, $3, $4, $5, $6, $7)
       on conflict (barcode) do update set
         name = excluded.name, brand = excluded.brand, category = excluded.category,
         image_url = excluded.image_url, default_unit = excluded.default_unit,
         source = excluded.source, fetched_at = now()`,
      [product.barcode, product.name, product.brand, product.category, product.image_url, product.default_unit, product.source],
    );
    await client.query('commit');
  } catch (e) {
    await client.query('rollback').catch(() => {});
    throw e;
  } finally {
    client.release();
  }
}

export type ProductDeps = {
  readCache: (barcode: string) => Promise<Product | null>;
  writeCache: (product: CachedProduct) => Promise<void>;
  fetch: typeof fetch;
};

export const defaultDeps = (claims: JWTPayload): ProductDeps => ({
  readCache: (barcode) => withUser(claims, (c) => readCachedProduct(c, barcode)),
  writeCache: writeCachedProduct,
  fetch: (input, init) => globalThis.fetch(input, init),
});

// Cache, then Open Food Facts. The barcode is validated before any query or fetch.
export async function lookupProduct(barcode: string, deps: ProductDeps): Promise<Product> {
  if (!isBarcode(barcode)) throw new ApiError(400, 'invalid_barcode', 'Barcode must be 8 to 14 digits');
  const cached = await deps.readCache(barcode);
  if (cached) return cached;
  const found = await fetchOffProduct(barcode, deps.fetch);
  if (!found) throw new ApiError(404, 'not_found', 'Unknown barcode');
  await deps.writeCache(found);
  return toProduct(found);
}
