import { createContext, useContext, type ReactNode } from 'react';
import type { RealtimeClientLike } from '../../shopping/Realtime.ts';

const RealtimeContext = createContext<RealtimeClientLike | null>(null);

/** Supplies the Realtime client (production: the Supabase client). Without one, nothing subscribes. */
export function RealtimeProvider({ client, children }: { client: RealtimeClientLike | null; children: ReactNode }) {
  return <RealtimeContext.Provider value={client}>{children}</RealtimeContext.Provider>;
}

export function useRealtimeClient(): RealtimeClientLike | null {
  return useContext(RealtimeContext);
}
