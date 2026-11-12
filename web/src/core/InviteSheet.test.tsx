import type { Invite } from '@abodenestly/contract';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiProvider } from './api/ApiProvider.tsx';
import { FakeApiClient } from './api/FakeApiClient.ts';
import { formatExpiry } from './InviteSheet.ts';
import { InviteSheet } from './InviteSheet.tsx';

function setOnline(value: boolean) {
  Object.defineProperty(navigator, 'onLine', { value, configurable: true });
}

async function goOffline() {
  setOnline(false);
  await act(async () => {
    window.dispatchEvent(new Event('offline'));
  });
}

async function flush() {
  await act(async () => {
    await new Promise((r) => setTimeout(r, 0));
  });
}

const EXPIRES = '2026-11-19T12:00:00.000Z';

function makeInvite(id: string, code: string, extra: Partial<Invite> = {}): Invite {
  return { id, household_id: 'h1', code, role: 'member', uses: 3, max_uses: 10, expires_at: EXPIRES, deleted_at: null, ...extra } as Invite;
}

const OLD = makeInvite('i-old', '0123456789ab');
const NEW = makeInvite('i-new', 'fedcba987654', { uses: 0 });

async function renderSheet(api = new FakeApiClient(), onClose = () => {}) {
  render(
    <ApiProvider client={api}>
      <InviteSheet householdId="h1" onClose={onClose} />
    </ApiProvider>,
  );
  await flush();
  return api;
}

const code = () => screen.getByLabelText('Invite code').textContent;
const newCodeButton = () => screen.getByRole('button', { name: 'New code' }) as HTMLButtonElement;
const shareButton = () => screen.getByRole('button', { name: 'Share link' }) as HTMLButtonElement;

let written: string[];

beforeEach(() => {
  setOnline(true);
  written = [];
  Object.defineProperty(navigator, 'clipboard', {
    value: { writeText: (text: string) => (written.push(text), Promise.resolve()) },
    configurable: true,
  });
});

afterEach(() => {
  cleanup();
  setOnline(true);
  Reflect.deleteProperty(navigator, 'share');
});

describe('CORE-03: InviteSheet', () => {
  // The sheet takes no role input, so owner and member get identical output (B6).
  it('CORE-03: AC1 an existing live invite shows code, uses left and expiry', async () => {
    const api = new FakeApiClient();
    api.fetchInviteResult = { ok: true, value: OLD };
    await renderSheet(api);
    expect(screen.getByRole('dialog', { name: 'Invite to household' })).toBeTruthy();
    expect(code()).toBe(OLD.code);
    expect(screen.getByText(`7 uses left · Expires ${formatExpiry(EXPIRES)}`)).toBeTruthy();
    expect(api.createInviteCalls).toEqual([]);
  });

  it('CORE-03: AC1 with no live invite the sheet creates one', async () => {
    const api = new FakeApiClient();
    api.createInviteResult = { ok: true, value: NEW };
    await renderSheet(api);
    expect(api.createInviteCalls).toEqual(['h1']);
    expect(code()).toBe(NEW.code);
  });

  it('CORE-03: a failed load shows an error and Try again loads it', async () => {
    const api = new FakeApiClient();
    api.fetchInviteResult = { ok: false, error: { code: 'network' } };
    await renderSheet(api);
    expect(screen.getByRole('alert').textContent).toContain("Couldn't load");
    api.fetchInviteResult = { ok: true, value: OLD };
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    await flush();
    expect(code()).toBe(OLD.code);
  });

  it('CORE-03: Copy writes the code to the clipboard', async () => {
    const api = new FakeApiClient();
    api.fetchInviteResult = { ok: true, value: OLD };
    await renderSheet(api);
    fireEvent.click(screen.getByRole('button', { name: 'Copy' }));
    await flush();
    expect(written).toEqual([OLD.code]);
    expect(screen.getByText('Code copied')).toBeTruthy();
  });

  it('CORE-03: Esc and Close both call onClose', async () => {
    const onClose = vi.fn();
    const api = new FakeApiClient();
    api.fetchInviteResult = { ok: true, value: OLD };
    await renderSheet(api, onClose);
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' });
    fireEvent.click(screen.getByRole('button', { name: 'Close' }));
    expect(onClose).toHaveBeenCalledTimes(2);
  });

  it('CORE-03: AC2 New code shows the new code and retires the old one', async () => {
    const api = new FakeApiClient();
    api.fetchInviteResult = { ok: true, value: OLD };
    api.createInviteResult = { ok: true, value: NEW };
    await renderSheet(api);
    fireEvent.click(newCodeButton());
    await flush();
    expect(code()).toBe(NEW.code);
    expect(api.retireCalls).toEqual(['i-old']);
    expect(screen.getByText(`10 uses left · Expires ${formatExpiry(EXPIRES)}`)).toBeTruthy();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('CORE-03: AC2 a failed create keeps the old code, shows an error and retires nothing', async () => {
    const api = new FakeApiClient();
    api.fetchInviteResult = { ok: true, value: OLD };
    await renderSheet(api);
    fireEvent.click(newCodeButton());
    await flush();
    expect(code()).toBe(OLD.code);
    expect(api.retireCalls).toEqual([]);
    expect(screen.getByRole('alert').textContent).toContain('current code still works');
    expect(newCodeButton().disabled).toBe(false);
  });

  it('CORE-03: AC2 a failed retire shows the new code and warns the old one may work', async () => {
    const api = new FakeApiClient();
    api.fetchInviteResult = { ok: true, value: OLD };
    api.createInviteResult = { ok: true, value: NEW };
    api.retireResult = { ok: false, error: { code: 'unknown' } };
    await renderSheet(api);
    fireEvent.click(newCodeButton());
    await flush();
    expect(code()).toBe(NEW.code);
    expect(screen.getByRole('alert').textContent).toContain('old code may still work');
  });

  it('CORE-03: New code is disabled while rotating', async () => {
    const api = new FakeApiClient();
    api.fetchInviteResult = { ok: true, value: OLD };
    api.createInviteResult = { ok: true, value: NEW };
    await renderSheet(api);
    api.pending = true;
    fireEvent.click(newCodeButton());
    await flush();
    expect(newCodeButton().disabled).toBe(true);
    expect(newCodeButton().getAttribute('aria-busy')).toBe('true');
    api.pending = false;
    api.resolvePending();
    await flush();
  });

  it('CORE-03: AC3 Share calls navigator.share with the join URL', async () => {
    const share = vi.fn(() => Promise.resolve());
    Object.defineProperty(navigator, 'share', { value: share, configurable: true });
    const api = new FakeApiClient();
    api.fetchInviteResult = { ok: true, value: OLD };
    await renderSheet(api);
    fireEvent.click(shareButton());
    await flush();
    expect(share).toHaveBeenCalledWith({
      title: 'Join my household',
      url: `${location.origin}/join/${OLD.code}`,
    });
    expect(written).toEqual([]);
  });

  it('CORE-03: AC3 without navigator.share the link is copied and announced', async () => {
    const api = new FakeApiClient();
    api.fetchInviteResult = { ok: true, value: OLD };
    await renderSheet(api);
    fireEvent.click(shareButton());
    await flush();
    expect(written).toEqual([`${location.origin}/join/${OLD.code}`]);
    expect(screen.getByText('Link copied').getAttribute('aria-live')).toBe('polite');
  });

  it('CORE-03: AC3 cancelling the share sheet is ignored', async () => {
    const abort = Object.assign(new Error('cancelled'), { name: 'AbortError' });
    Object.defineProperty(navigator, 'share', { value: () => Promise.reject(abort), configurable: true });
    const api = new FakeApiClient();
    api.fetchInviteResult = { ok: true, value: OLD };
    await renderSheet(api);
    fireEvent.click(shareButton());
    await flush();
    expect(written).toEqual([]);
    expect(screen.queryByText('Link copied')).toBeNull();
  });

  it('CORE-03: AC5 offline from the start shows the banner, no code and no calls', async () => {
    setOnline(false);
    const api = await renderSheet();
    expect(screen.getByRole('status').textContent).toBe("You're offline. Connect to change household settings.");
    expect(screen.getByText('Connect to see the invite code.')).toBeTruthy();
    expect(shareButton().disabled).toBe(true);
    expect(api.fetchInviteCalls).toEqual([]);
  });

  it('CORE-03: AC5 coming online loads the invite', async () => {
    setOnline(false);
    const api = new FakeApiClient();
    api.fetchInviteResult = { ok: true, value: OLD };
    await renderSheet(api);
    setOnline(true);
    await act(async () => {
      window.dispatchEvent(new Event('online'));
    });
    await flush();
    expect(code()).toBe(OLD.code);
    expect(screen.queryByRole('status')).toBeNull();
  });

  it('CORE-03: AC5 going offline after load keeps the code and disables New code and Share', async () => {
    const api = new FakeApiClient();
    api.fetchInviteResult = { ok: true, value: OLD };
    await renderSheet(api);
    await goOffline();
    expect(screen.getByRole('status')).toBeTruthy();
    expect(code()).toBe(OLD.code);
    expect(newCodeButton().disabled).toBe(true);
    expect(shareButton().disabled).toBe(true);
    expect(api.createInviteCalls).toEqual([]);
  });
});
