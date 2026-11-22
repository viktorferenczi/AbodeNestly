import type { ListItem } from '@abodenestly/contract';
import type { HouseholdDb } from '../sync/db.ts';
import { mutate, type MutateDeps } from '../sync/outbox.ts';
import { UNDO_MS } from './Lists.ts';

export const checkFields = (userId: string | null, now: string) => ({ checked: true, checked_at: now, checked_by: userId });
export const uncheckFields = () => ({ checked: false, checked_at: null, checked_by: null });

export type PendingCheck = { id: string; name: string; writing: boolean };

type Options = {
  db: HouseholdDb;
  deps: Partial<MutateDeps>;
  userId: string | null;
  delayMs?: number;
  /** Called whenever the waiting set changes, so the screen can re-render. */
  onChange: () => void;
};

/**
 * SHOP-04: a check-off is written only when its 5 s undo window ends, so Undo never has anything to take back
 * (no local row change, no outbox entry). Until then the item is "pending" and the screen shows it ticked in place.
 */
export function createCheckOffQueue({ db, deps, userId, delayMs = UNDO_MS, onChange }: Options) {
  const waiting = new Map<string, { name: string; writing: boolean; timer: ReturnType<typeof setTimeout> }>();
  const now = () => (deps.now ?? (() => new Date().toISOString()))();

  // Re-reads the row: a stale timer must not resurrect a deleted row or overwrite a check-off made on another device.
  async function write(id: string) {
    const row = await db.list_item.get(id);
    if (!row || row.deleted_at != null || row.checked) return;
    await mutate(db, deps).patch('list_item', id, checkFields(userId, now()));
  }

  return {
    check(item: Pick<ListItem, 'id' | 'name'>) {
      const old = waiting.get(item.id);
      if (old) clearTimeout(old.timer);
      const timer = setTimeout(() => {
        const w = waiting.get(item.id);
        if (w) w.writing = true;
        // Stays "pending" (ticked in place) until the row has changed, so it never flickers back to unchecked.
        void write(item.id)
          .catch(() => undefined)
          .finally(() => {
            waiting.delete(item.id);
            onChange();
          });
      }, delayMs);
      waiting.set(item.id, { name: item.name, writing: false, timer });
      onChange();
    },
    /** Nothing was written yet, so this only forgets the pending check. */
    undo(id: string) {
      const w = waiting.get(id);
      if (!w || w.writing) return;
      clearTimeout(w.timer);
      waiting.delete(id);
      onChange();
    },
    /** Writes everything still waiting now (leaving the screen or the page). */
    async flush() {
      const ids = [...waiting].filter(([, w]) => !w.writing).map(([id]) => id);
      for (const id of ids) clearTimeout(waiting.get(id)!.timer);
      await Promise.all(ids.map((id) => write(id).catch(() => undefined)));
      for (const id of ids) waiting.delete(id);
    },
    has: (id: string) => waiting.has(id),
    pending: (): PendingCheck[] => [...waiting].map(([id, w]) => ({ id, name: w.name, writing: w.writing })),
  };
}

/** Unchecking is its own undo: written at once, no toast. */
export const uncheck = (db: HouseholdDb, deps: Partial<MutateDeps>, id: string) => mutate(db, deps).patch('list_item', id, uncheckFields());

/** One soft delete per row, each its own outbox entry (the sync contract is one mutation per row). */
export async function clearChecked(db: HouseholdDb, deps: Partial<MutateDeps>, ids: string[]): Promise<void> {
  for (const id of ids) await mutate(db, deps).remove('list_item', id);
}
