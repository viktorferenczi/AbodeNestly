import type { Invite } from '@abodenestly/contract';
import { describe, expect, it } from 'vitest';
import { formatExpiry, inviteLink, inviteSheetReducer, joinPath, usesLeft } from './InviteSheet.ts';

const invite = { id: 'i1', code: '0123456789ab', uses: 3, max_uses: 10, expires_at: '2026-11-19T12:00:00.000Z' } as Invite;

describe('CORE-03: invite link round trip', () => {
  it.each(['0123456789ab', 'ABCDEF', 'a b', 'a/b', 'a?b=c&d', 'ä€#%'])('AC4: %s survives link → route → join path', (code) => {
    const link = new URL(inviteLink('https://app.example', code));
    expect(link.origin).toBe('https://app.example');
    expect(link.pathname.startsWith('/join/')).toBe(true);
    // react-router decodes the :code param.
    const routed = decodeURIComponent(link.pathname.slice('/join/'.length));
    expect(routed).toBe(code);
    const path = new URL(joinPath(routed), 'https://app.example');
    expect(path.pathname).toBe('/welcome');
    expect(path.searchParams.get('code')).toBe(code);
  });
});

describe('CORE-03: invite helpers', () => {
  it('usesLeft is max_uses - uses', () => {
    expect(usesLeft(invite)).toBe(7);
    expect(usesLeft({ max_uses: 1, uses: 1 })).toBe(0);
  });

  it('formatExpiry gives day and short month', () => {
    expect(formatExpiry(invite.expires_at, 'en-GB', 'UTC')).toBe('19 Nov');
    expect(formatExpiry(invite.expires_at, 'en-US', 'UTC')).toBe('Nov 19');
  });
});

describe('CORE-03: inviteSheetReducer', () => {
  it('loads, rotates and keeps the old invite on failure', () => {
    let state = inviteSheetReducer({ kind: 'loading' }, { type: 'loaded', invite });
    expect(state).toEqual({ kind: 'ready', invite });
    state = inviteSheetReducer(state, { type: 'rotate' });
    expect(state).toEqual({ kind: 'rotating', invite });
    state = inviteSheetReducer(state, { type: 'failed', code: 'unknown' });
    expect(state).toEqual({ kind: 'error', code: 'unknown', invite });
  });

  it('a failed first load has no invite, and rotate is ignored without one', () => {
    const failed = inviteSheetReducer({ kind: 'loading' }, { type: 'failed', code: 'network' });
    expect(failed).toEqual({ kind: 'error', code: 'network' });
    expect(inviteSheetReducer(failed, { type: 'rotate' })).toBe(failed);
  });
});
