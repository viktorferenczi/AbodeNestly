import Dexie from 'dexie';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { openHouseholdDb } from '../sync/db.ts';
import { createItem } from './ItemSheet.ts';
import { checkFields, clearChecked, createCheckOffQueue, uncheck, uncheckFields } from './CheckOff.ts';

let n = 0;
const deps = () => ({ uuid: () => `m${++n}`, now: () => '2026-02-01T00:00:00Z' });

beforeEach(async () => {
  n = 0;
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
  await Dexie.delete('abodenestly-h1');
});
afterEach(() => vi.useRealTimers());

async function seed(count = 1) {
  const db = await openHouseholdDb('h1');
  const ids: string[] = [];
  for (let i = 0; i < count; i++) {
    ids.push(await createItem(db, deps(), { householdId: 'h1', listId: 'l1', userId: 'u1', fields: { name: `Item ${i}`, quantity: null, unit: null, note: null } }));
  }
  await db.outbox.clear();
  return { db, ids };
}
const queueFor = (db: Awaited<ReturnType<typeof seed>>['db']) => createCheckOffQueue({ db, deps: deps(), userId: 'u1', onChange: () => undefined });

describe('SHOP-04: check-off queue', () => {
  it('fields', () => {
    expect(checkFields('u1', 'T')).toEqual({ checked: true, checked_at: 'T', checked_by: 'u1' });
    expect(uncheckFields()).toEqual({ checked: false, checked_at: null, checked_by: null });
  });

  it('AC2: undo before 5 s writes nothing, even after the window', async () => {
    const { db, ids } = await seed();
    const q = queueFor(db);
    q.check({ id: ids[0]!, name: 'Item 0' });
    q.undo(ids[0]!);
    await vi.advanceTimersByTimeAsync(6000);
    expect((await db.list_item.get(ids[0]!))!.checked).toBe(false);
    expect(await db.outbox.count()).toBe(0);
  });

  it('AC3: after 5 s the row is checked and exactly one upsert is queued', async () => {
    const { db, ids } = await seed();
    const q = queueFor(db);
    q.check({ id: ids[0]!, name: 'Item 0' });
    await vi.advanceTimersByTimeAsync(4999);
    expect(await db.outbox.count()).toBe(0);
    await vi.advanceTimersByTimeAsync(1);
    await vi.waitFor(async () => expect(await db.outbox.count()).toBe(1));
    expect(await db.list_item.get(ids[0]!)).toMatchObject({ checked: true, checked_at: '2026-02-01T00:00:00Z', checked_by: 'u1' });
    const [out] = await db.outbox.toArray();
    expect(out!.payload).toMatchObject({ op: 'upsert', data: { checked: true, checked_at: '2026-02-01T00:00:00Z', checked_by: 'u1' } });
  });

  it('expiry skips a row deleted meanwhile and one already checked elsewhere', async () => {
    const { db, ids } = await seed(2);
    const q = queueFor(db);
    q.check({ id: ids[0]!, name: 'a' });
    q.check({ id: ids[1]!, name: 'b' });
    await db.list_item.update(ids[0]!, { deleted_at: '2026-02-01T00:00:00Z' });
    await db.list_item.update(ids[1]!, { checked: true });
    await vi.advanceTimersByTimeAsync(5000);
    await vi.waitFor(() => expect(q.pending()).toHaveLength(0));
    expect(await db.outbox.count()).toBe(0);
  });

  it('flush writes everything waiting at once', async () => {
    const { db, ids } = await seed(2);
    const q = queueFor(db);
    q.check({ id: ids[0]!, name: 'a' });
    q.check({ id: ids[1]!, name: 'b' });
    await q.flush();
    expect(await db.outbox.count()).toBe(2);
    expect(q.pending()).toHaveLength(0);
    await vi.advanceTimersByTimeAsync(6000);
    expect(await db.outbox.count()).toBe(2);
  });

  it('uncheck queues one upsert with the three fields null', async () => {
    const { db, ids } = await seed();
    await db.list_item.update(ids[0]!, { checked: true, checked_at: 'T', checked_by: 'u1' });
    await uncheck(db, deps(), ids[0]!);
    const out = await db.outbox.toArray();
    expect(out).toHaveLength(1);
    expect(out[0]!.payload).toMatchObject({ op: 'upsert', data: { checked: false, checked_at: null, checked_by: null } });
  });

  it('AC5: clearChecked soft-deletes the given rows only, one outbox delete each', async () => {
    const { db, ids } = await seed(3);
    await clearChecked(db, deps(), [ids[0]!, ids[1]!]);
    const rows = await db.list_item.bulkGet(ids);
    expect(rows.map((r) => r!.deleted_at != null)).toEqual([true, true, false]);
    const out = await db.outbox.toArray();
    expect(out).toHaveLength(2);
    expect(out.every((o) => (o.payload as { op: string }).op === 'delete')).toBe(true);
  });
});
