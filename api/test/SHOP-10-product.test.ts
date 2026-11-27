import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../_lib/auth.ts', async () => {
  const { ApiError } = await import('../_lib/errors.ts');
  return {
    verifyJwt: vi.fn(async (req: Request) => {
      if (!req.headers.get('authorization')) throw new ApiError(401, 'unauthorized', 'no token');
      return { sub: 'user-1', claims: { sub: 'user-1' }, token: 'tok' };
    }),
  };
});
vi.mock('../_lib/db.ts', () => ({ withUser: vi.fn() }));
vi.mock('pg', () => ({ default: { Pool: vi.fn(() => ({ query: vi.fn() })) } }));

import handler from '../products/[barcode].ts';
import { ApiError } from '../_lib/errors.ts';
import { fetchOffProduct, lookupProduct, mapOffProduct, type CachedProduct, type ProductDeps } from '../_lib/products.ts';

const BARCODE = '3017620422003';
const offOk = (product: Record<string, unknown>) =>
  new Response(JSON.stringify({ status: 1, product }), { status: 200 });

function deps(fetchImpl: (...a: Parameters<typeof fetch>) => Promise<Response>, cached: CachedProduct | null = null) {
  const fetchSpy = vi.fn(fetchImpl);
  const d = {
    readCache: vi.fn(async () => cached),
    writeCache: vi.fn(async () => undefined),
    fetch: fetchSpy as unknown as typeof fetch,
  } satisfies ProductDeps;
  return { d, fetchSpy };
}

const rejection = async (p: Promise<unknown>) => (await p.then(() => null, (e: unknown) => e)) as ApiError;

beforeEach(() => {
  delete process.env.OFF_BASE_URL;
  delete process.env.OPENFOODFACTS_USER_AGENT;
});

describe('lookupProduct', () => {
  it('AC3: OFF status 0 gives 404 not_found and nothing is cached', async () => {
    const { d } = deps(async () => new Response(JSON.stringify({ status: 0 }), { status: 200 }));
    const err = await rejection(lookupProduct(BARCODE, d));
    expect([err.status, err.code]).toEqual([404, 'not_found']);
    expect(d.writeCache).not.toHaveBeenCalled();
  });

  it('an OFF 404 gives 404', async () => {
    const { d } = deps(async () => new Response('{"status":0}', { status: 404 }));
    expect((await rejection(lookupProduct(BARCODE, d))).status).toBe(404);
  });

  it('a product with a blank name gives 404', async () => {
    const { d } = deps(async () => offOk({ product_name: '  ', generic_name: '' }));
    expect((await rejection(lookupProduct(BARCODE, d))).status).toBe(404);
  });

  it.each([
    ['network error', async () => Promise.reject(new TypeError('fetch failed'))],
    ['timeout', async () => Promise.reject(new DOMException('timed out', 'TimeoutError'))],
    ['5xx', async () => new Response('oops', { status: 503 })],
    ['bad JSON', async () => new Response('<html>', { status: 200 })],
  ])('%s gives 502 upstream_unavailable and nothing is cached', async (_n, impl) => {
    const { d } = deps(impl);
    const err = await rejection(lookupProduct(BARCODE, d));
    expect([err.status, err.code]).toEqual([502, 'upstream_unavailable']);
    expect(d.writeCache).not.toHaveBeenCalled();
  });

  it.each(['abc', '1234567', '123456789012345', '1234 5678', ''])('invalid barcode %j gives 400 with no query or fetch', async (code) => {
    const { d, fetchSpy } = deps(async () => offOk({ product_name: 'x' }));
    const err = await rejection(lookupProduct(code, d));
    expect([err.status, err.code]).toEqual([400, 'invalid_barcode']);
    expect(fetchSpy).not.toHaveBeenCalled();
    expect(d.readCache).not.toHaveBeenCalled();
  });

  it('accepts 8 and 14 digits and does not check the check digit', async () => {
    for (const code of ['12345678', '12345678901234']) {
      const { d } = deps(async () => offOk({ product_name: 'x' }));
      expect((await lookupProduct(code, d)).barcode).toBe(code);
    }
  });

  it('AC1: a cache hit is returned and OFF is not called', async () => {
    const cached = { barcode: BARCODE, name: 'Cached', brand: null, category: null, image_url: null, default_unit: null };
    const { d, fetchSpy } = deps(async () => offOk({ product_name: 'Other' }), cached as CachedProduct);
    expect(await lookupProduct(BARCODE, d)).toEqual(cached);
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('AC2: a miss fetches, caches with source and returns exactly the six Product fields', async () => {
    const { d } = deps(async () => offOk({ product_name: 'Nutella', brands: 'Ferrero' }));
    const p = await lookupProduct(BARCODE, d);
    expect(Object.keys(p).sort()).toEqual(['barcode', 'brand', 'category', 'default_unit', 'image_url', 'name']);
    expect(d.writeCache).toHaveBeenCalledWith(expect.objectContaining({ barcode: BARCODE, name: 'Nutella', source: 'openfoodfacts' }));
  });
});

describe('mapOffProduct', () => {
  it('maps brand, category and image', () => {
    expect(
      mapOffProduct(BARCODE, {
        product_name: '  Nutella ',
        brands: ' Ferrero , Other',
        categories_tags: ['en:spreads', 'en:sweet-spreads'],
        image_front_url: 'https://images.example.com/a.jpg',
      }),
    ).toEqual({
      barcode: BARCODE,
      name: 'Nutella',
      brand: 'Ferrero',
      category: 'spreads',
      image_url: 'https://images.example.com/a.jpg',
      default_unit: null,
      source: 'openfoodfacts',
    });
  });

  it('strips the language prefix and turns dashes into spaces', () => {
    expect(mapOffProduct(BARCODE, { product_name: 'x', categories_tags: ['fr:pates-a-tartiner'] })?.category).toBe('pates a tartiner');
  });

  it('drops a non-https image and empty brand/category', () => {
    const p = mapOffProduct(BARCODE, { product_name: 'x', brands: ' ,', categories_tags: [], image_front_url: 'http://a/b.jpg' });
    expect([p?.image_url, p?.brand, p?.category]).toEqual([null, null, null]);
  });

  it('falls back from product_name to product_name_en to generic_name', () => {
    expect(mapOffProduct(BARCODE, { product_name: '', product_name_en: 'English', generic_name: 'Generic' })?.name).toBe('English');
    expect(mapOffProduct(BARCODE, { product_name_en: ' ', generic_name: 'Generic' })?.name).toBe('Generic');
    expect(mapOffProduct(BARCODE, {})).toBeNull();
  });
});

describe('fetchOffProduct request', () => {
  it('sends the URL, the default User-Agent without an email, and a timeout signal', async () => {
    const { d, fetchSpy } = deps(async () => offOk({ product_name: 'x' }));
    await fetchOffProduct(BARCODE, d.fetch);
    const [url, init] = fetchSpy.mock.calls[0]!;
    expect(url).toBe(
      `https://world.openfoodfacts.org/api/v2/product/${BARCODE}.json?fields=product_name,product_name_en,generic_name,brands,categories_tags,image_front_url`,
    );
    const ua = (init!.headers as Record<string, string>)['user-agent'];
    expect(ua).toBe('AbodeNestly/1.0 (+https://github.com/viktorferenczi/AbodeNestly)');
    expect(ua).not.toContain('@');
    expect(init!.signal).toBeInstanceOf(AbortSignal);
  });

  it('honours OFF_BASE_URL and OPENFOODFACTS_USER_AGENT', async () => {
    process.env.OFF_BASE_URL = 'http://127.0.0.1:9/';
    process.env.OPENFOODFACTS_USER_AGENT = 'Custom/2';
    const { d, fetchSpy } = deps(async () => offOk({ product_name: 'x' }));
    await fetchOffProduct(BARCODE, d.fetch);
    const [url, init] = fetchSpy.mock.calls[0]!;
    expect(String(url).startsWith(`http://127.0.0.1:9/api/v2/product/${BARCODE}.json`)).toBe(true);
    expect((init!.headers as Record<string, string>)['user-agent']).toBe('Custom/2');
  });
});

describe('handler', () => {
  const errCode = async (res: Response) => ((await res.json()) as { error: { code: string } }).error.code;

  it('AC4: no JWT gives 401 before anything else, even for a bad barcode', async () => {
    const res = await handler(new Request('http://x/products/abc'));
    expect(res.status).toBe(401);
    expect(await errCode(res)).toBe('unauthorized');
  });

  it('a bad barcode with a JWT gives 400 invalid_barcode', async () => {
    const res = await handler(new Request('http://x/products/abc', { headers: { authorization: 'Bearer t' } }));
    expect(res.status).toBe(400);
    expect(await errCode(res)).toBe('invalid_barcode');
  });

  it('other methods give 405', async () => {
    const res = await handler(new Request(`http://x/products/${BARCODE}`, { method: 'POST' }));
    expect(res.status).toBe(405);
  });
});
