import { describe, expect, it, vi } from 'vitest';
import { isSyncPushRequest, type Mutation } from '@abodenestly/contract';

vi.mock('../_lib/auth.ts', () => ({
  verifyJwt: vi.fn(async () => ({ sub: 'user-1', claims: { sub: 'user-1' } })),
}));
vi.mock('../_lib/db.ts', () => ({ withUser: vi.fn() }));

import handler, { parsePullQuery } from '../sync.ts';
import { withUser } from '../_lib/db.ts';
import { buildPullSql, pullChanges } from '../_lib/sync/pull.ts';
import { errorResponse } from '../_lib/http.ts';
import { buildUpsert, classifyError, pushMutations } from '../_lib/sync/push.ts';

const HID = '8f6f3b9e-1c1e-4a55-9a52-0d2c5d9f7a11';
const id = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;

const upsert = (n: number, table: string, data: Record<string, unknown> = { name: 'x' }): Mutation =>
  ({ id: id(n), table, op: 'upsert', row_id: id(n + 100), data, client_ts: '2026-11-08T10:12:00Z' }) as Mutation;

// Records every statement; `fail` maps a statement prefix to an error to throw once.
function fakeClient(fail: Record<string, { code: string; message: string }> = {}) {
  const calls: Array<{ text: string; values?: unknown[] }> = [];
  return {
    calls,
    query: vi.fn(async (text: string, values?: unknown[]) => {
      calls.push({ text, values });
      const hit = Object.keys(fail).find((p) => text.startsWith(p) && values?.includes(fail[p]!.message));
      if (hit) throw Object.assign(new Error(fail[hit]!.message), { code: fail[hit]!.code });
      if (text.startsWith('select record_sync_mutation')) return { rows: [{ fresh: true }] };
      if (text.startsWith('insert') || text.startsWith('update')) return { rows: [{ id: 'row' }] };
      return { rows: [] };
    }),
  };
}

describe('pushMutations', () => {
  it('maps a deadlock to a retryable 503 instead of a 500', async () => {
    const res = errorResponse(Object.assign(new Error('deadlock detected'), { code: '40P01' }));
    expect(res.status).toBe(503);
    expect(await res.json()).toEqual({ error: { code: 'retryable', message: 'Conflicting write, try again' } });
  });

  it('takes the household sync lock before the first mutation (#514)', async () => {
    const c = fakeClient();
    await pushMutations(c, HID, [upsert(1, 'store')]);
    expect(c.calls[0]!.text).toContain('pg_advisory_xact_lock');
    expect(c.calls[0]!.values).toEqual([HID]);
    const empty = fakeClient();
    await pushMutations(empty, HID, []);
    expect(empty.calls).toEqual([]);
  });

  it('AC3: applies valid mutations around an unknown table', async () => {
    const c = fakeClient();
    const res = await pushMutations(c, HID, [upsert(1, 'list_item'), upsert(2, 'nope'), upsert(3, 'store')]);
    expect(res.applied).toEqual([id(1), id(3)]);
    expect(res.rejected).toEqual([{ id: id(2), reason: 'unknown_table', detail: 'Unknown table nope' }]);
    expect(c.calls.filter((q) => q.text.startsWith('insert into'))).toHaveLength(2);
    expect(c.calls.some((q) => q.text.startsWith('rollback to savepoint'))).toBe(false);
  });

  it('rejects a pull-only table and a delta as validation', async () => {
    const c = fakeClient();
    const delta = { ...upsert(2, 'store'), op: 'delta' } as Mutation;
    const res = await pushMutations(c, HID, [upsert(1, 'invite'), delta]);
    expect(res.rejected.map((r) => [r.id, r.reason])).toEqual([
      [id(1), 'validation'],
      [id(2), 'validation'],
    ]);
    // pantry_item is not registered yet, so a delta on it is an unknown table.
    const pantry = { ...upsert(3, 'pantry_item'), op: 'delta' } as Mutation;
    expect((await pushMutations(c, HID, [pantry])).rejected[0]!.reason).toBe('unknown_table');
    expect(c.calls.some((q) => q.text.startsWith('rollback to savepoint'))).toBe(true);
  });

  it('rejects an unknown column as validation and rolls the savepoint back', async () => {
    const c = fakeClient();
    const res = await pushMutations(c, HID, [upsert(1, 'store', { name: 'a', bogus: 1 }), upsert(2, 'store')]);
    expect(res.applied).toEqual([id(2)]);
    expect(res.rejected[0]).toMatchObject({ id: id(1), reason: 'validation' });
    expect(res.rejected[0]!.detail).toContain('bogus');
    expect(c.calls.filter((q) => q.text.startsWith('rollback to savepoint'))).toHaveLength(1);
  });

  it('maps 42501 to rls_denied and 23502 to validation, and continues', async () => {
    const c = fakeClient({
      'insert into': { code: '42501', message: 'denied' },
    });
    const m = { ...upsert(1, 'store'), data: { name: 'denied' } } as Mutation;
    const res = await pushMutations(c, HID, [m, upsert(2, 'store')]);
    expect(res.rejected).toEqual([{ id: id(1), reason: 'rls_denied', detail: 'denied' }]);
    expect(res.applied).toEqual([id(2)]);
    expect(classifyError(Object.assign(new Error('null value'), { code: '23502' }))).toEqual({
      reason: 'validation',
      detail: 'null value',
    });
  });

  it('rethrows unexpected database errors', async () => {
    const c = fakeClient({ 'insert into': { code: '08006', message: 'boom' } });
    const m = { ...upsert(1, 'store'), data: { name: 'boom' } } as Mutation;
    await expect(pushMutations(c, HID, [m])).rejects.toThrow('boom');
  });

  it('treats an already-applied mutation as applied without writing', async () => {
    const c = fakeClient();
    c.query.mockImplementation(async (text: string) => {
      c.calls.push({ text });
      return { rows: text.startsWith('select record_sync_mutation') ? [{ fresh: false }] : [] };
    });
    const res = await pushMutations(c, HID, [upsert(1, 'store')]);
    expect(res.applied).toEqual([id(1)]);
    expect(c.calls.some((q) => q.text.startsWith('insert'))).toBe(false);
  });

  it('delete that matches no row is rls_denied', async () => {
    const c = fakeClient();
    c.query.mockImplementation(async (text: string) => {
      c.calls.push({ text });
      return { rows: text.startsWith('select record_sync_mutation') ? [{ fresh: true }] : [] };
    });
    const del = { id: id(1), table: 'store', op: 'delete', row_id: id(2), client_ts: 't' } as Mutation;
    const res = await pushMutations(c, HID, [del]);
    expect(res.rejected[0]).toMatchObject({ reason: 'rls_denied' });
  });
});

describe('buildUpsert', () => {
  it('drops server-owned keys and forces household and id', () => {
    const { sql, values } = buildUpsert('shopping_list', ['name', 'is_template'], HID, id(5), {
      id: 'evil', household_id: 'evil', created_at: 'x', updated_at: 'x', sync_seq: 9, share_token: 'tok',
      name: 'Weekly',
    });
    expect(values).toEqual([id(5), HID, 'Weekly']);
    expect(sql).not.toMatch(/share_token|sync_seq|updated_at|created_at/);
    expect(sql).toContain('name = excluded.name');
  });
});

describe('buildUpsert deleted_at', () => {
  it('stores server time for a non-null client value and keeps an existing tombstone', () => {
    const { sql, values } = buildUpsert('store', ['name', 'deleted_at'], HID, id(5), {
      deleted_at: '2020-01-01T00:00:00Z',
    });
    expect(values).toEqual([id(5), HID, '2020-01-01T00:00:00Z']);
    expect(sql).toContain('case when $3::timestamptz is null then null else now() end');
    expect(sql).toContain('coalesce(t.deleted_at, excluded.deleted_at)');
    expect(sql).toContain('when excluded.deleted_at is null then null');
  });
});

describe('pullChanges', () => {
  it('builds one ordered union over the allow-listed tables', () => {
    const sql = buildPullSql();
    expect(sql.match(/union all/g)).toHaveLength(9);
    expect(sql).toContain('from household r where r.id = $1');
    expect(sql).toContain('from list_item r where r.household_id = $1');
    expect(sql.trimEnd().endsWith('order by seq limit $3')).toBe(true);
  });

  it('groups rows by table, trims the probe row and sets has_more and cursor', async () => {
    const rows = [
      { t: 'store', seq: '5', row: { id: 'a', sync_seq: 5 } },
      { t: 'list_item', seq: '6', row: { id: 'b', sync_seq: 6 } },
      { t: 'list_item', seq: '7', row: { id: 'c', sync_seq: 7 } },
    ];
    const res = await pullChanges({ query: async () => ({ rows }) }, HID, 0, 2);
    expect(res).toEqual({
      cursor: 6,
      has_more: true,
      changes: { store: [rows[0]!.row], list_item: [rows[1]!.row] },
    });
  });

  it('keeps the cursor at since when nothing changed', async () => {
    const res = await pullChanges({ query: async () => ({ rows: [] }) }, HID, 42, 500);
    expect(res).toEqual({ cursor: 42, has_more: false, changes: {} });
  });
});

describe('guards and query parsing', () => {
  const ok = upsert(1, 'store');
  it('accepts a valid envelope and rejects bad ones', () => {
    expect(isSyncPushRequest({ household_id: HID, mutations: [ok] })).toBe(true);
    expect(isSyncPushRequest({ household_id: 'nope', mutations: [] })).toBe(false);
    expect(isSyncPushRequest({ household_id: HID, mutations: [{ ...ok, op: 'merge' }] })).toBe(false);
    expect(isSyncPushRequest({ household_id: HID, mutations: [{ ...ok, id: 'x' }] })).toBe(false);
  });
  it('rejects more than 500 mutations', () => {
    expect(isSyncPushRequest({ household_id: HID, mutations: Array(500).fill(ok) })).toBe(true);
    expect(isSyncPushRequest({ household_id: HID, mutations: Array(501).fill(ok) })).toBe(false);
  });
  it.each(['since=-1', 'since=1.5', 'since=abc', 'limit=0', 'limit=501'])('rejects %s', (q) => {
    expect(() => parsePullQuery(new URL(`http://x/sync?household_id=${HID}&${q}`))).toThrow();
  });
  it('defaults since and limit', () => {
    expect(parsePullQuery(new URL(`http://x/sync?household_id=${HID}`))).toMatchObject({ since: 0, limit: 500 });
  });
});

describe('handler', () => {
  const req = (method: string, path = `/sync?household_id=${HID}`, body?: string) =>
    new Request(`http://x${path}`, {
      method,
      headers: { authorization: 'Bearer t', 'content-type': 'application/json' },
      body,
    });

  it('returns 400 for a bad query and 405 for other methods, without touching the DB', async () => {
    vi.mocked(withUser).mockClear();
    expect((await handler(req('GET', '/sync?household_id=x'))).status).toBe(400);
    expect((await handler(req('GET', `/sync?household_id=${HID}&since=-1`))).status).toBe(400);
    expect((await handler(req('POST', '/sync', 'not json'))).status).toBe(400);
    expect((await handler(req('PUT'))).status).toBe(405);
    expect(withUser).not.toHaveBeenCalled();
  });
});
