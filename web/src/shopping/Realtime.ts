import type { Modules } from '../core/household/HouseholdProvider.tsx';

/** Shopping tables in the `supabase_realtime` publication (docs/schema.sql). */
export const SHOPPING_REALTIME_TABLES = ['shopping_list', 'list_item', 'store', 'store_item_order'] as const;

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
 * SHOP-09, SPEC §6.4 step 3: listens to household changes in the shopping tables and calls `onChange` for each.
 * The payload is never read; `onChange` starts a pull, the one code path that applies data. A lost connection is
 * not an error here: the SDK reconnects, and the open/visible/online/5-minute kicks keep running meanwhile.
 * Returns the unsubscribe. With shopping off nothing is subscribed.
 */
export function subscribeShoppingRealtime({
  client,
  householdId,
  modules,
  onChange,
}: {
  client: RealtimeClientLike;
  householdId: string;
  modules: Pick<Modules, 'shopping'>;
  onChange: () => void;
}): () => void {
  if (!modules.shopping) return () => undefined;
  let channel = client.channel(`household:${householdId}:shopping`);
  for (const table of SHOPPING_REALTIME_TABLES) {
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
