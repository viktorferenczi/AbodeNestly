import type { Invite } from '@abodenestly/contract';
import type { ApiFailure } from './api/apiClient.ts';

export type InviteSheetState =
  | { kind: 'loading' }
  | { kind: 'ready'; invite: Invite }
  | { kind: 'rotating'; invite: Invite }
  /** `invite` is kept when a "New code" attempt fails so the old code stays visible. */
  | { kind: 'error'; code: ApiFailure['code']; invite?: Invite };

export type InviteSheetAction =
  | { type: 'load' }
  | { type: 'loaded'; invite: Invite }
  | { type: 'rotate' }
  | { type: 'failed'; code: ApiFailure['code'] };

/** The invite on screen, whatever state the sheet is in. */
export function shownInvite(state: InviteSheetState): Invite | null {
  return state.kind === 'loading' ? null : (state.invite ?? null);
}

export function inviteSheetReducer(state: InviteSheetState, action: InviteSheetAction): InviteSheetState {
  switch (action.type) {
    case 'load':
      return { kind: 'loading' };
    case 'loaded':
      return { kind: 'ready', invite: action.invite };
    case 'rotate': {
      const invite = shownInvite(state);
      return invite ? { kind: 'rotating', invite } : state;
    }
    case 'failed': {
      const invite = shownInvite(state);
      return invite ? { kind: 'error', code: action.code, invite } : { kind: 'error', code: action.code };
    }
  }
}

/** The share link; `/join/:code` is routed to the Welcome join card. */
export function inviteLink(origin: string, code: string): string {
  return `${origin}/join/${encodeURIComponent(code)}`;
}

export function joinPath(code: string): string {
  return `/welcome?code=${encodeURIComponent(code)}`;
}

export function usesLeft(invite: Pick<Invite, 'max_uses' | 'uses'>): number {
  return invite.max_uses - invite.uses;
}

/** "19 Nov" in the viewer's locale; `timeZone` is only for deterministic tests. */
export function formatExpiry(iso: string, locale?: string, timeZone?: string): string {
  return new Intl.DateTimeFormat(locale, { day: 'numeric', month: 'short', timeZone }).format(new Date(iso));
}
