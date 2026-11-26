import type { Modules } from '../core/household/HouseholdProvider.tsx';

/**
 * The household-scoped tables in the `supabase_realtime` publication (docs/schema.sql), by the module that owns them.
 * `household` is keyed by its own `id`; every other table carries `household_id`.
 */
export const REALTIME_TABLES = {
  core: ['household', 'member', 'invite', 'activity', 'attachment', 'notification_pref', 'location'],
  shopping: ['store', 'shopping_list', 'list_item', 'store_item_order', 'item_price'],
  pantry: ['category', 'pantry_item', 'pantry_movement', 'recipe', 'recipe_ingredient'],
  maintenance: ['house', 'contractor', 'asset', 'maint_task', 'task_consumable', 'maint_log'],
} as const;

/** The tables to listen to: core always, a module's tables only while that module is on (SHOP-09 AC4, X7). */
export function realtimeTables(modules: Modules): string[] {
  return [
    ...REALTIME_TABLES.core,
    ...(modules.shopping ? REALTIME_TABLES.shopping : []),
    ...(modules.pantry ? REALTIME_TABLES.pantry : []),
    ...(modules.maintenance ? REALTIME_TABLES.maintenance : []),
  ];
}

/** The part of a Supabase channel used here, so tests can pass a fake. */
export interface RealtimeChannelLike {
  on(
    type: 'postgres_changes',
    filter: { event: '*'; schema: 'public'; table: string; filter: string },
    callback: () => void,
  ): RealtimeChannelLike;
  subscribe(callback?: (status: string) => void): RealtimeChannelLike;
}

/** The part of the Supabase client used here. */
export interface RealtimeClientLike {
  channel(name: string): RealtimeChannelLike;
  removeChannel(channel: RealtimeChannelLike): unknown;
}

/**
 * SPEC §6.4 step 3 (SHOP-09): one household channel with a `postgres_changes` listener per published table, filtered
 * to the household. Every event calls `onChange` and the payload is never read: `onChange` starts a pull, the one code
 * path that applies data. A lost connection is not an error here: the SDK reconnects, and the open/visible/online/
 * 5-minute kicks keep running meanwhile. Returns the unsubscribe.
 */
export function subscribeHouseholdRealtime({
  client,
  householdId,
  modules,
  onChange,
}: {
  client: RealtimeClientLike;
  householdId: string;
  modules: Modules;
  onChange: () => void;
}): () => void {
  let channel = client.channel(`household:${householdId}`);
  for (const table of realtimeTables(modules)) {
    const column = table === 'household' ? 'id' : 'household_id';
    channel = channel.on(
      'postgres_changes',
      { event: '*', schema: 'public', table, filter: `${column}=eq.${householdId}` },
      () => onChange(),
    );
  }
  const subscribed = channel.subscribe((status) => {
    // Catch up on whatever happened while the channel was not joined. CHANNEL_ERROR, TIMED_OUT and CLOSED are ignored on purpose.
    if (status === 'SUBSCRIBED') onChange();
  });
  return () => void client.removeChannel(subscribed);
}
