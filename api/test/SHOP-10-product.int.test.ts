import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import pg from 'pg';
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { createDevServer } from '../scripts/dev-server.ts';

let server: Server;
let baseUrl: string;
let off: Server;
let admin: pg.Pool;
let token: string;

// Fake Open Food Facts: records every request; behaviour per barcode.
const offHits: Array<{ url: string; userAgent: string | undefined }> = [];
const NUTELLA = '3017620422003';
const SEEDED = '5000112637922';
const UNKNOWN = '4000000000009';
const DOWN = '4000000000016';

const listen = (s: Server) => new Promise<number>((resolve) => s.listen(0, () => resolve((s.address() as AddressInfo).port)));

async function signUp(): Promise<string> {
  const res = await fetch(`${process.env.SUPABASE_URL}/auth/v1/signup`, {
    method: 'POST',
    headers: { apikey: process.env.SUPABASE_ANON_KEY ?? '', 'content-type': 'application/json' },
    body: JSON.stringify({ email: `shop10-${crypto.randomUUID()}@example.com`, password: crypto.randomUUID() }),
  });
  if (!res.ok) throw new Error(`signup failed: ${res.status} ${await res.text()}`);
  return ((await res.json()) as { access_token: string }).access_token;
}

const get = (barcode: string, jwt: string | null = token) =>
  fetch(`${baseUrl}/products/${barcode}`, { headers: jwt ? { authorization: `Bearer ${jwt}` } : {} });

beforeAll(async () => {
  off = createServer((req, res) => {
    offHits.push({ url: req.url ?? '', userAgent: req.headers['user-agent'] });
    const code = /\/product\/(\d+)\.json/.exec(req.url ?? '')?.[1];
    res.setHeader('content-type', 'application/json');
    if (code === DOWN) {
      res.statusCode = 503;
      res.end('{}');
    } else if (code === NUTELLA) {
      res.end(
        JSON.stringify({
          status: 1,
          product: {
            product_name: 'Nutella',
            brands: 'Ferrero,Other',
            categories_tags: ['en:sweet-spreads'],
            image_front_url: 'https://images.example.com/n.jpg',
          },
        }),
      );
    } else {
      res.statusCode = 404;
      res.end('{"status":0}');
    }
  });
  process.env.OFF_BASE_URL = `http://127.0.0.1:${await listen(off)}`;
  server = createDevServer();
  baseUrl = `http://127.0.0.1:${await listen(server)}`;
  admin = new pg.Pool({ connectionString: process.env.DATABASE_URL, max: 1 });
  token = await signUp();
  await admin.query('delete from product where barcode = any($1)', [[NUTELLA, SEEDED, UNKNOWN, DOWN]]);
  await admin.query("insert into product (barcode, name, brand, category, image_url, default_unit, source) values ($1, 'Seeded Beans', 'Heinz', 'beans', null, 'can', 'manual')", [SEEDED]);
});

beforeEach(() => {
  offHits.length = 0;
});

afterAll(async () => {
  await admin.query('delete from product where barcode = any($1)', [[NUTELLA, SEEDED, UNKNOWN, DOWN]]);
  await admin.end();
  await new Promise<void>((resolve) => server.close(() => resolve()));
  await new Promise<void>((resolve) => off.close(() => resolve()));
});

describe('GET /products/{barcode}', () => {
  it('AC1: a cached barcode is returned without calling Open Food Facts', async () => {
    const res = await get(SEEDED);
    expect(res.status).toBe(200);
    expect(res.headers.get('cache-control')).toBe('private, max-age=86400');
    expect(await res.json()).toEqual({
      barcode: SEEDED,
      name: 'Seeded Beans',
      brand: 'Heinz',
      category: 'beans',
      image_url: null,
      default_unit: 'can',
    });
    expect(offHits).toHaveLength(0);
  });

  it('AC2: an uncached barcode is fetched, cached with its source and served from the cache next time', async () => {
    const res = await get(NUTELLA);
    expect(res.status).toBe(200);
    const body = (await res.json()) as Record<string, unknown>;
    expect(Object.keys(body).sort()).toEqual(['barcode', 'brand', 'category', 'default_unit', 'image_url', 'name']);
    expect(body).toMatchObject({ barcode: NUTELLA, name: 'Nutella', brand: 'Ferrero', category: 'sweet spreads' });
    expect(offHits).toHaveLength(1);
    expect(offHits[0]!.userAgent).toMatch(/^AbodeNestly\/1\.0/);
    expect(offHits[0]!.userAgent).not.toContain('@');

    const row = (await admin.query('select name, source, fetched_at from product where barcode = $1', [NUTELLA])).rows[0];
    expect(row).toMatchObject({ name: 'Nutella', source: 'openfoodfacts' });

    expect((await get(NUTELLA)).status).toBe(200);
    expect(offHits).toHaveLength(1);
  });

  it('AC3: a barcode nobody knows gives 404 and is not cached', async () => {
    const res = await get(UNKNOWN);
    expect(res.status).toBe(404);
    expect(((await res.json()) as { error: { code: string } }).error.code).toBe('not_found');
    expect((await admin.query('select 1 from product where barcode = $1', [UNKNOWN])).rowCount).toBe(0);
  });

  it('AC4: no JWT gives 401', async () => {
    expect((await get(SEEDED, null)).status).toBe(401);
    expect(offHits).toHaveLength(0);
  });

  it('an invalid barcode gives 400 invalid_barcode without calling Open Food Facts', async () => {
    const res = await get('1234567');
    expect(res.status).toBe(400);
    expect(((await res.json()) as { error: { code: string } }).error.code).toBe('invalid_barcode');
    expect(offHits).toHaveLength(0);
  });

  it('an Open Food Facts outage gives 502 upstream_unavailable and is not cached', async () => {
    const res = await get(DOWN);
    expect(res.status).toBe(502);
    expect(((await res.json()) as { error: { code: string } }).error.code).toBe('upstream_unavailable');
    expect((await admin.query('select 1 from product where barcode = $1', [DOWN])).rowCount).toBe(0);
  });

  it('a user cannot write the cache through RLS', async () => {
    const res = await fetch(`${process.env.SUPABASE_URL}/rest/v1/product`, {
      method: 'POST',
      headers: {
        apikey: process.env.SUPABASE_ANON_KEY ?? '',
        authorization: `Bearer ${token}`,
        'content-type': 'application/json',
      },
      body: JSON.stringify({ barcode: '9999999999999', name: 'Forged' }),
    });
    expect(res.ok).toBe(false);
  });
});
