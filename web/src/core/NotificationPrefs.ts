import type { NotificationPref, NotificationType } from '@abodenestly/contract';
import type { HouseholdDb, OutboxRow } from '../sync/db.ts';
import type { Modules } from './household/HouseholdProvider.tsx';

/** DESIGN B9 order. */
export const TYPES: readonly { type: NotificationType; label: string }[] = [
  { type: 'eat_soon', label: 'Eat soon' },
  { type: 'low_stock', label: 'Low stock' },
  { type: 'task_due', label: 'Task due' },
  { type: 'warranty', label: 'Warranty' },
  { type: 'activity', label: 'Activity' },
];

/** Rows of a disabled module are hidden; Activity is core and always stays. */
export function visibleTypes(modules: Modules): typeof TYPES {
  return TYPES.filter(({ type }) => {
    if (type === 'eat_soon' || type === 'low_stock') return modules.pantry;
    if (type === 'task_due' || type === 'warranty') return modules.maintenance;
    return true;
  });
}

/** A missing row means enabled (the server has no row until the first change). */
export function enabled(rows: readonly Pick<NotificationPref, 'type' | 'enabled'>[], type: NotificationType): boolean {
  return rows.find((r) => r.type === type)?.enabled ?? true;
}

const URL_NAMESPACE = '6ba7b811-9dad-11d1-80b4-00c04fd430c8';
const hexToBytes = (hex: string) => Uint8Array.from(hex.replaceAll('-', '').match(/../g)!, (b) => parseInt(b, 16));

/**
 * Deterministic row id (UUID v5, RFC 4122 §4.3): the same on web, iOS and Android, so the same switch queued on two
 * devices addresses one server row. The table has no `id` column, so this is only the outbox `row_id`.
 */
export async function prefRowId(userId: string, householdId: string, type: NotificationType): Promise<string> {
  const name = new TextEncoder().encode(`abodenestly:notification_pref:${userId}:${householdId}:${type}`.toLowerCase());
  const ns = hexToBytes(URL_NAMESPACE);
  const input = new Uint8Array(ns.length + name.length);
  input.set(ns);
  input.set(name, ns.length);
  const hash = new Uint8Array(await crypto.subtle.digest('SHA-1', input)).slice(0, 16);
  hash[6] = (hash[6]! & 0x0f) | 0x50;
  hash[8] = (hash[8]! & 0x3f) | 0x80;
  const hex = Array.from(hash, (b) => b.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

export type PrefKey = { userId: string; householdId: string; type: NotificationType };
export type PrefDeps = { uuid: () => string; now: () => string };
/** SPEC §6.2 envelope. `notification_pref` is not in SyncTable yet; SHOP-08-api is to accept it and key the row off `data`. */
export type PrefEnvelope = {
  id: string;
  table: 'notification_pref';
  op: 'upsert';
  row_id: string;
  data: NotificationPref;
  client_ts: string;
};

/** Ids are stored lowercase on every platform and hashed lowercase, so the row, `data` and `row_id` always agree. */
export const normalizeId = (id: string) => id.toLowerCase();

export async function prefEnvelope(key: PrefKey, on: boolean, deps: PrefDeps): Promise<PrefEnvelope> {
  const userId = normalizeId(key.userId);
  const householdId = normalizeId(key.householdId);
  return {
    id: deps.uuid(),
    table: 'notification_pref',
    op: 'upsert',
    row_id: await prefRowId(userId, householdId, key.type),
    data: { user_id: userId, household_id: householdId, type: key.type, enabled: on },
    client_ts: deps.now(),
  };
}

/** Writes the row locally and queues the upsert in one transaction, so the two cannot diverge. The caller kicks sync. */
export async function setPref(db: HouseholdDb, key: PrefKey, on: boolean, deps: PrefDeps): Promise<void> {
  const envelope = await prefEnvelope(key, on, deps); // before the transaction: awaiting WebCrypto inside would close it
  await db.transaction('rw', db.notification_pref, db.outbox, async () => {
    await db.notification_pref.put(envelope.data);
    const entry: OutboxRow = { table: 'notification_pref', op: 'upsert', payload: envelope };
    await db.outbox.add(entry);
  });
}
