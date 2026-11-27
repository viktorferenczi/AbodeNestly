import type { Modules } from '../core/household/HouseholdProvider.tsx';

/**
 * The tables the migrations have added to the `supabase_realtime` publication, by the module that owns them. Binding a
 * table that is not published makes the whole join fail (`CHANNEL_ERROR`), so a table joins this list in the same
 * change that publishes it (`docs/schema.sql` shows the end state, which also has household, member, item_price and
 * the pantry and maintenance tables). Every table here carries `household_id`.
 */
export const REALTIME_TABLES: Record<'core' | 'shopping' | 'pantry' | 'maintenance', readonly string[]> = {
  core: ['location', 'invite', 'activity', 'attachment', 'notification_pref'],
  shopping: ['store', 'shopping_list', 'list_item', 'store_item_order'],
  pantry: [],
  maintenance: [],
};

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
    channel = channel.on(
      'postgres_changes',
      { event: '*', schema: 'public', table, filter: `household_id=eq.${householdId}` },
      () => onChange(),
    );
  }
  const subscribed = channel.subscribe((status) => {
    // Catch up on whatever happened while the channel was not joined. CHANNEL_ERROR, TIMED_OUT and CLOSED are ignored on purpose.
    if (status === 'SUBSCRIBED') onChange();
  });
  return () => void client.removeChannel(subscribed);
}
