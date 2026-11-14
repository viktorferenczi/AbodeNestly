import type { Role } from '@abodenestly/contract';
import type { MemberRow } from './api/apiClient.ts';

const name = (row: MemberRow) => row.profile?.display_name.trim() || 'Member';

/** Display name with the fallback the screen shows when the profile is hidden or empty. */
export const memberName = name;

export function isMe(row: MemberRow, meId: string): boolean {
  return row.member.user_id === meId;
}

/** Owners first, then by name; ties keep input order (sort is stable). */
export function sortMembers(rows: MemberRow[]): MemberRow[] {
  const rank = (r: MemberRow) => (r.member.role === 'owner' ? 0 : 1);
  return [...rows].sort((a, b) => rank(a) - rank(b) || name(a).localeCompare(name(b)));
}

/** "Anna Kovacs" → "AK"; a single name gives one letter. */
export function initials(displayName: string | undefined): string {
  const words = (displayName ?? '').trim().split(/\s+/).filter(Boolean);
  if (words.length === 0) return '?';
  const first = [...words[0]!][0]!;
  const last = words.length > 1 ? [...words[words.length - 1]!][0]! : '';
  return (first + last).toLocaleUpperCase();
}

export function viewerRole(rows: MemberRow[], meId: string): Role | null {
  return rows.find((r) => isMe(r, meId))?.member.role ?? null;
}

/** True when the viewer is an owner and no one else is. */
export function isLastOwner(rows: MemberRow[], meId: string): boolean {
  if (viewerRole(rows, meId) !== 'owner') return false;
  return !rows.some((r) => r.member.role === 'owner' && !isMe(r, meId));
}

/** Only owners remove others; leaving yourself is the separate Leave action. */
export function canRemove(role: Role | null, target: MemberRow, meId: string): boolean {
  return role === 'owner' && !isMe(target, meId);
}

export type LeaveDecision = { ok: true } | { ok: false; reason: 'last_owner' };

/** AC4: the only owner is blocked before any call is made. */
export function leaveDecision(rows: MemberRow[], meId: string): LeaveDecision {
  return isLastOwner(rows, meId) ? { ok: false, reason: 'last_owner' } : { ok: true };
}

// ---------- undo scheduling ----------

export type UndoScheduler = {
  /** Runs `run` after the delay unless `undo()` is called first. A second schedule for the same key replaces the first. */
  schedule(key: string, run: () => void): { undo(): void };
  /** Runs everything still waiting now (unmount: Undo is the only cancel, leaving the screen is not). */
  flushAll(): void;
};

export function createUndoScheduler(
  options: {
    delayMs?: number;
    setTimeout?: (fn: () => void, ms: number) => unknown;
    clearTimeout?: (id: never) => void;
  } = {},
): UndoScheduler {
  const { delayMs = 5000 } = options;
  const set = options.setTimeout ?? ((fn, ms) => globalThis.setTimeout(fn, ms));
  const clear = (options.clearTimeout ?? ((id) => globalThis.clearTimeout(id as never))) as (id: unknown) => void;
  const timers = new Map<string, { id: unknown; run: () => void }>();
  const cancel = (key: string) => {
    const timer = timers.get(key);
    if (!timer) return;
    clear(timer.id);
    timers.delete(key);
  };
  return {
    schedule(key, run) {
      cancel(key);
      const id = set(() => {
        timers.delete(key);
        run();
      }, delayMs);
      timers.set(key, { id, run });
      return { undo: () => cancel(key) };
    },
    flushAll() {
      for (const [key, { run }] of [...timers]) {
        cancel(key);
        run();
      }
    },
  };
}

// ---------- screen state ----------

export type MembersView =
  | { kind: 'loading' }
  | { kind: 'ready'; rows: MemberRow[]; source: 'live' | 'cache' }
  | { kind: 'error' };

export type MembersState = {
  view: MembersView;
  /** user_id of the member whose removal (or own leave) is waiting out the undo window; the row is hidden. */
  pendingRemoval: string | null;
  /** Failure text for a remove, leave or make-owner attempt. */
  message: string | null;
};

export type MembersAction =
  | { type: 'loaded'; rows: MemberRow[]; source: 'live' | 'cache' }
  | { type: 'failed' }
  | { type: 'removeStarted'; userId: string }
  | { type: 'removeUndone' }
  /** The DELETE succeeded or the member was already gone: drop the row. */
  | { type: 'removeDone'; userId: string }
  | { type: 'removeFailed'; message: string }
  | { type: 'messageShown'; message: string }
  | { type: 'messageCleared' };

export const initialMembersState: MembersState = { view: { kind: 'loading' }, pendingRemoval: null, message: null };

export function membersReducer(state: MembersState, action: MembersAction): MembersState {
  switch (action.type) {
    case 'loaded':
      return { ...state, view: { kind: 'ready', rows: action.rows, source: action.source } };
    case 'failed':
      // A list already on screen stays; only a first load can end in the error state.
      return state.view.kind === 'ready' ? state : { ...state, view: { kind: 'error' } };
    case 'removeStarted':
      return { ...state, pendingRemoval: action.userId, message: null };
    case 'removeUndone':
      return { ...state, pendingRemoval: null };
    case 'removeDone': {
      const view =
        state.view.kind === 'ready'
          ? { ...state.view, rows: state.view.rows.filter((r) => r.member.user_id !== action.userId) }
          : state.view;
      return { ...state, view, pendingRemoval: null };
    }
    case 'removeFailed':
      return { ...state, pendingRemoval: null, message: action.message };
    case 'messageShown':
      return { ...state, message: action.message };
    case 'messageCleared':
      return { ...state, message: null };
  }
}

/** Rows to render: sorted, with the pending removal hidden. */
export function visibleRows(state: MembersState): MemberRow[] {
  if (state.view.kind !== 'ready') return [];
  return sortMembers(state.view.rows.filter((r) => r.member.user_id !== state.pendingRemoval));
}
