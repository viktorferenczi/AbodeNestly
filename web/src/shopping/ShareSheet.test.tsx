import type { Household, Profile, ShoppingList } from '@abodenestly/contract';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import Dexie from 'dexie';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { RequireModule } from '../app/RequireModule.tsx';
import { Shell } from '../app/Shell.tsx';
import { ApiProvider } from '../core/api/ApiProvider.tsx';
import { FakeApiClient } from '../core/api/FakeApiClient.ts';
import { AuthProvider } from '../core/auth/AuthProvider.tsx';
import { FakeAuthClient } from '../core/auth/FakeAuthClient.ts';
import { writeLastHousehold } from '../core/auth/lastHousehold.ts';
import { FakeSync } from '../sync/FakeSync.ts';
import { openHouseholdDb } from '../sync/db.ts';
import { SyncProvider } from '../sync/SyncEngine.ts';
import { ListDetail } from './ListDetail.tsx';
import { ShareSheet } from './ShareSheet.tsx';
import { shareLink, tokenFromUrl } from './ShareSheet.ts';

const TOKEN = 'T'.repeat(43);
const NEW_TOKEN = 'N'.repeat(43);
const apiUrl = (t: string) => `http://localhost:3000/shared/${t}`;
const origin = () => window.location.origin;

function setOnline(value: boolean) {
  Object.defineProperty(navigator, 'onLine', { value, configurable: true });
}

function renderSheet(api: FakeApiClient, token: string | null = null) {
  const changed = vi.fn();
  render(
    <ApiProvider client={api}>
      <ShareSheet listId="l1" token={token} onChanged={changed} onClose={() => undefined} />
    </ApiProvider>,
  );
  return { changed };
}

const toggle = () => screen.getByRole('switch', { name: 'Share with a link' }) as HTMLInputElement;

beforeEach(() => setOnline(true));
afterEach(() => {
  cleanup();
  setOnline(true);
  vi.restoreAllMocks();
});

describe('SHOP-13: share link helpers', () => {
  it('takes the token from the API url and builds the page link from the web origin', () => {
    expect(tokenFromUrl(apiUrl(TOKEN))).toBe(TOKEN);
    expect(tokenFromUrl('')).toBeNull();
    expect(shareLink('https://app.test/', TOKEN)).toBe(`https://app.test/shared/${TOKEN}`);
  });
});

describe('SHOP-13: share sheet (C8)', () => {
  it('AC1: the switch is off when there is no token, and there is no link', () => {
    renderSheet(new FakeApiClient());
    expect(toggle().checked).toBe(false);
    expect(screen.queryByRole('textbox', { name: 'Share link' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Stop sharing' })).toBeNull();
  });

  it('AC2: turning it on POSTs, shows the link, and Copy says "Link copied"', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    const api = new FakeApiClient();
    api.shareResult = { ok: true, value: { url: apiUrl(NEW_TOKEN) } };
    const { changed } = renderSheet(api);
    fireEvent.click(toggle());
    const link = (await screen.findByRole('textbox', { name: 'Share link' })) as HTMLInputElement;
    expect(api.shareCalls).toEqual(['l1']);
    expect(link.value).toBe(`${origin()}/shared/${NEW_TOKEN}`);
    expect(toggle().checked).toBe(true);
    expect(screen.getByText('Anyone with the link can view unchecked items')).toBeTruthy();
    expect(changed).toHaveBeenCalledTimes(1);
    expect(changed).toHaveBeenLastCalledWith(NEW_TOKEN);
    fireEvent.click(screen.getByRole('button', { name: 'Copy link' }));
    expect((await screen.findByRole('status')).textContent).toBe('Link copied');
    expect(writeText).toHaveBeenCalledWith(link.value);
  });

  it('Copy without a clipboard selects the link instead', async () => {
    Object.defineProperty(navigator, 'clipboard', { value: undefined, configurable: true });
    renderSheet(new FakeApiClient(), TOKEN);
    const link = screen.getByRole('textbox', { name: 'Share link' }) as HTMLInputElement;
    fireEvent.click(screen.getByRole('button', { name: 'Copy link' }));
    await waitFor(() => expect(document.activeElement).toBe(link));
    expect(screen.queryByRole('status')).toBeNull();
  });

  it('while the request is pending the switch is busy', async () => {
    const api = new FakeApiClient();
    api.pending = true;
    api.shareResult = { ok: true, value: { url: apiUrl(NEW_TOKEN) } };
    renderSheet(api);
    fireEvent.click(toggle());
    await waitFor(() => expect(toggle().disabled).toBe(true));
    api.resolvePending();
    await screen.findByRole('textbox', { name: 'Share link' });
    expect(toggle().disabled).toBe(false);
  });

  it('a failed POST returns the switch to off with a message (module_disabled too)', async () => {
    for (const code of ['unknown', 'module_disabled'] as const) {
      const api = new FakeApiClient();
      api.shareResult = { ok: false, error: { code } };
      renderSheet(api);
      fireEvent.click(toggle());
      expect((await screen.findByRole('alert')).textContent).toBe('Could not turn on sharing. Try again.');
      expect(toggle().checked).toBe(false);
      cleanup();
    }
  });

  it('an existing local token opens on, with the link built from it', () => {
    renderSheet(new FakeApiClient(), TOKEN);
    expect(toggle().checked).toBe(true);
    expect((screen.getByRole('textbox', { name: 'Share link' }) as HTMLInputElement).value).toBe(`${origin()}/shared/${TOKEN}`);
    expect(screen.getByText('The current link will stop working')).toBeTruthy();
  });

  it('AC3: Stop sharing DELETEs and the switch is off', async () => {
    const api = new FakeApiClient();
    const { changed } = renderSheet(api, TOKEN);
    fireEvent.click(screen.getByRole('button', { name: 'Stop sharing' }));
    await waitFor(() => expect(toggle().checked).toBe(false));
    expect(api.stopCalls).toEqual(['l1']);
    expect(screen.queryByRole('textbox', { name: 'Share link' })).toBeNull();
    expect(changed).toHaveBeenCalledTimes(1);
    expect(changed).toHaveBeenLastCalledWith(null);
  });

  it('turning the switch off also DELETEs', async () => {
    const api = new FakeApiClient();
    renderSheet(api, TOKEN);
    fireEvent.click(toggle());
    await waitFor(() => expect(toggle().checked).toBe(false));
    expect(api.stopCalls).toEqual(['l1']);
  });

  it('a failed DELETE stays on with a message', async () => {
    const api = new FakeApiClient();
    api.stopResult = { ok: false, error: { code: 'network' } };
    renderSheet(api, TOKEN);
    fireEvent.click(screen.getByRole('button', { name: 'Stop sharing' }));
    expect((await screen.findByRole('alert')).textContent).toBe('Could not stop sharing. Try again.');
    expect(toggle().checked).toBe(true);
  });

  it('AC4: offline disables the switch and Stop sharing, keeps a known link and Copy', () => {
    setOnline(false);
    renderSheet(new FakeApiClient(), TOKEN);
    expect(toggle().disabled).toBe(true);
    expect((screen.getByRole('button', { name: 'Stop sharing' }) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByText('Sharing needs a connection')).toBeTruthy();
    expect(screen.getByRole('textbox', { name: 'Share link' })).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Copy link' }) as HTMLButtonElement).disabled).toBe(false);
  });

  it('AC4: offline with no token, the switch is disabled and off', () => {
    setOnline(false);
    renderSheet(new FakeApiClient());
    expect(toggle().disabled).toBe(true);
    expect(toggle().checked).toBe(false);
  });
});

describe('SHOP-13: Share in List actions', () => {
  const house = () => ({ id: 'h1', name: 'Home One', currency: 'EUR', module_shopping: true, module_pantry: true, module_maintenance: true }) as Household;
  const list = (over: Partial<ShoppingList> = {}) =>
    ({ id: 'a', household_id: 'h1', name: 'Groceries', store_id: null, is_template: false, archived_at: null, deleted_at: null, share_token: null, created_at: '2026-01-01T00:00:01Z', ...over }) as ShoppingList;

  async function renderList(over: Partial<ShoppingList>) {
    const db = await openHouseholdDb('h1');
    await db.shopping_list.put(list(over));
    await db.close();
    const api = new FakeApiClient();
    api.fetchHousehold = async () => ({ ok: true, value: { household: house(), role: 'owner' } });
    api.fetchMyHouseholdsResult = { ok: true, value: [{ household: house(), role: 'owner' }] };
    api.shareResult = { ok: true, value: { url: apiUrl(NEW_TOKEN) } };
    writeLastHousehold('h1');
    const auth = new FakeAuthClient({ user: { id: 'u1' } });
    auth.profile = { id: 'u1', display_name: 'Ada' } as Profile;
    const sync = new FakeSync();
    const router = createMemoryRouter(
      [
        {
          element: <Shell />,
          children: [
            {
              element: <RequireModule module="shopping" />,
              children: [{ path: '/shopping/:listId', element: <SyncProvider engine={sync}><ListDetail /></SyncProvider> }],
            },
          ],
        },
      ],
      { initialEntries: ['/shopping/a'] },
    );
    render(
      <AuthProvider client={auth}>
        <ApiProvider client={api}>
          <RouterProvider router={router} />
        </ApiProvider>
      </AuthProvider>,
    );
    return { api };
  }

  beforeEach(async () => {
    localStorage.clear();
    await Dexie.delete('abodenestly-h1');
  });

  it('is offered on a list with nothing checked, and opens the sheet off', async () => {
    await renderList({});
    fireEvent.click(await screen.findByRole('button', { name: 'List actions' }));
    expect(screen.getAllByRole('menuitem').map((m) => m.textContent)).toEqual(['Share']);
    fireEvent.click(screen.getByRole('menuitem', { name: 'Share' }));
    const dialog = await screen.findByRole('dialog', { name: 'Share list' });
    expect((dialog.querySelector('[role="switch"]') as HTMLInputElement).checked).toBe(false);
  });

  it('opens on for a list whose local row has a token', async () => {
    await renderList({ share_token: TOKEN });
    fireEvent.click(await screen.findByRole('button', { name: 'List actions' }));
    fireEvent.click(screen.getByRole('menuitem', { name: 'Share' }));
    expect(((await screen.findByRole('textbox', { name: 'Share link' })) as HTMLInputElement).value).toBe(`${origin()}/shared/${TOKEN}`);
  });
});
