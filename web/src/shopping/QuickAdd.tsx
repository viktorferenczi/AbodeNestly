import type { ListItem } from '@abodenestly/contract';
import { useMemo, useRef, useState } from 'react';
import { useAuth } from '../core/auth/AuthProvider.tsx';
import { useSync } from '../sync/SyncEngine.ts';
import type { HouseholdDb } from '../sync/db.ts';
import { QuickAddBar } from '../ui/QuickAddBar.tsx';
import { addItem, historyOf, suggest } from './QuickAdd.ts';

type Props = { db: HouseholdDb; householdId: string; listId: string; items: ListItem[]; autoFocus: boolean };

/** Wires the QuickAddBar to one list: suggestions from the household's item history, writes through the outbox. */
export function QuickAdd({ db, householdId, listId, items, autoFocus }: Props) {
  const { session } = useAuth();
  const sync = useSync();
  const syncRef = useRef(sync);
  syncRef.current = sync;
  const deps = useMemo(() => ({ onQueued: () => void syncRef.current.kick() }), []);
  const [value, setValue] = useState('');

  const history = useMemo(() => historyOf(items), [items]);
  const suggestions = useMemo(() => suggest(history, value, { listId }), [history, value, listId]);

  const add = (name: string) => {
    const trimmed = name.trim();
    if (!trimmed) return;
    setValue('');
    void addItem(db, deps, { householdId, listId, name: trimmed, userId: session?.user.id ?? null });
  };

  return <QuickAddBar value={value} onChange={setValue} suggestions={suggestions} onPick={add} onSubmit={add} autoFocus={autoFocus} />;
}
