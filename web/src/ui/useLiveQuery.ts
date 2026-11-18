import { liveQuery } from 'dexie';
import { useEffect, useRef, useState } from 'react';

/**
 * Subscribes to a Dexie query and re-renders when the rows it read change. `undefined` until the first result.
 * `deps` restart the subscription (the querier itself is read through a ref, so inline closures are fine).
 */
export function useLiveQuery<T>(querier: () => T | Promise<T>, deps: readonly unknown[]): T | undefined {
  const [value, setValue] = useState<T | undefined>(undefined);
  const latest = useRef(querier);
  latest.current = querier;
  useEffect(() => {
    setValue(undefined);
    const subscription = liveQuery(() => latest.current()).subscribe({
      next: setValue,
      // A closed database (household switch) ends the query; the next deps change starts a fresh one.
      error: () => undefined,
    });
    return () => subscription.unsubscribe();
  }, deps);
  return value;
}
