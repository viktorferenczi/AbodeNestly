import type { Household, Profile } from '@abodenestly/contract';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { Shell } from '../app/Shell.tsx';
import { openHouseholdDb } from '../sync/db.ts';
import { ApiProvider } from './api/ApiProvider.tsx';
import { FakeApiClient } from './api/FakeApiClient.ts';
import type { ApiResult, UploadFn } from './api/apiClient.ts';
import { AuthProvider } from './auth/AuthProvider.tsx';
import { FakeAuthClient } from './auth/FakeAuthClient.ts';
import { writeLastHousehold } from './auth/lastHousehold.ts';
import { AttachmentPicker } from './AttachmentPicker.tsx';

function setOnline(value: boolean) {
  Object.defineProperty(navigator, 'onLine', { value, configurable: true });
}
// The control offers its buttons only once the household database is open (a pick before that would be dropped).
async function databaseOpen() {
  await waitFor(() => expect((screen.getByRole('button', { name: 'Add photo' }) as HTMLButtonElement).disabled).toBe(false));
}
const household = {
  id: 'h1', name: 'Home One', currency: 'EUR', module_shopping: true, module_pantry: true, module_maintenance: true,
} as Household;

let puts: Array<{ url: string; mime: string }>;
let putResult: ApiResult<null>;
const upload: UploadFn = (url, _blob, mime) => {
  puts.push({ url, mime });
  return Promise.resolve(putResult);
};

async function renderPicker(opts: { entity?: 'pantry_item' | 'maint_log'; enabled?: boolean } = {}) {
  const api = new FakeApiClient();
  api.fetchHousehold = async () => ({ ok: true, value: { household, role: 'owner' } });
  api.fetchMyHouseholdsResult = { ok: true, value: [{ household, role: 'owner' }] };
  writeLastHousehold('h1');
  const auth = new FakeAuthClient({ user: { id: 'u1' } });
  auth.profile = { id: 'u1', display_name: 'Ada' } as Profile;
  const router = createMemoryRouter(
    [
      {
        element: <Shell />,
        children: [
          {
            path: '/home',
            element: (
              <AttachmentPicker
                householdId="h1"
                entity={opts.entity ?? 'pantry_item'}
                entityId="e1"
                enabled={opts.enabled ?? true}
                upload={upload}
              />
            ),
          },
        ],
      },
    ],
    { initialEntries: ['/home'] },
  );
  render(
    <AuthProvider client={auth}>
      <ApiProvider client={api}>
        <RouterProvider router={router} />
      </ApiProvider>
    </AuthProvider>,
  );
  await waitFor(() => expect(screen.queryByTestId('attachments.idle') ?? screen.queryByRole('navigation')).toBeTruthy());
  if (opts.enabled !== false) await databaseOpen();
  return { api, auth };
}

const photo = () => new File(['x'], 'milk.jpg', { type: 'image/jpeg' });
const pdf = () => new File(['y'], 'warranty.pdf', { type: 'application/pdf' });
const choose = (testId: string, file: File) => fireEvent.change(screen.getByTestId(testId), { target: { files: [file] } });

beforeEach(async () => {
  setOnline(true);
  localStorage.clear();
  puts = [];
  putResult = { ok: true, value: null };
  const { default: Dexie } = await import('dexie');
  await Dexie.delete('abodenestly-h1');
});
afterEach(cleanup);

describe('CORE-11: attachment control', () => {
  it('CORE-11: idle shows the add buttons and no items', async () => {
    await renderPicker();
    expect(screen.getByRole('button', { name: 'Add photo' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Add PDF' })).toBeTruthy();
    expect(screen.queryAllByTestId('attachments.item')).toHaveLength(0);
    expect(screen.getByTestId('attachments.photo').getAttribute('accept')).toBe('image/*');
    expect(screen.getByTestId('attachments.pdf').getAttribute('accept')).toBe('application/pdf');
  });

  it('CORE-11: AC1 picking a photo shows uploading, then signs and PUTs the bytes', async () => {
    const { api } = await renderPicker();
    api.pending = true;
    choose('attachments.photo', photo());
    await waitFor(() => expect(screen.getByTestId('attachments.uploading')).toBeTruthy());
    expect(screen.getByText('Uploading…')).toBeTruthy();
    api.pending = false;
    api.resolvePending();
    await waitFor(() => expect(puts).toHaveLength(1));
    expect(api.signAttachmentCalls).toEqual([
      { household_id: 'h1', entity: 'pantry_item', entity_id: 'e1', filename: 'milk.jpg', mime: 'image/jpeg', size: 1 },
    ]);
    expect(puts[0]).toEqual({ url: 'https://upload.test/a1', mime: 'image/jpeg' });
  });

  it('CORE-11: AC2 after the upload the control is idle and shows the photo thumbnail', async () => {
    await renderPicker();
    choose('attachments.photo', photo());
    // Decorative thumbnails (alt="") have no img role, so read the element itself.
    await waitFor(() => expect(screen.getByTestId('attachments.item').querySelector('img')).toBeTruthy());
    expect(screen.getByTestId('attachments.idle')).toBeTruthy();
    expect(screen.getByTestId('attachments.item').querySelector('img')!.getAttribute('alt')).toBe('a1.jpg');
    expect(screen.getByTestId('attachments.item').querySelector('img')!.getAttribute('src')).toBe('https://signed.test/h1/pantry_item/e1/a1.jpg');
  });

  it('CORE-11: AC3 a PDF on a log entry is listed as a filename row', async () => {
    const { api } = await renderPicker({ entity: 'maint_log' });
    api.signAttachmentResult = {
      ok: true,
      value: { attachment_id: 'a2', upload_url: 'https://upload.test/a2', storage_path: 'h1/maint_log/e1/a2.pdf' },
    };
    choose('attachments.pdf', pdf());
    await waitFor(() => expect(screen.getByText('a2.pdf')).toBeTruthy());
    expect(api.signAttachmentCalls[0]).toMatchObject({ entity: 'maint_log', mime: 'application/pdf' });
    expect(screen.getByTestId('attachments.item').querySelector('img')).toBeNull();
    const db = await openHouseholdDb('h1');
    expect(await db.attachment.get('a2')).toMatchObject({ entity: 'maint_log', created_by: 'u1' });
    db.close();
  });

  it('CORE-11: AC4 offline the pick is queued, sign is not called, and it uploads on reconnect', async () => {
    setOnline(false);
    const { api } = await renderPicker();
    choose('attachments.photo', photo());
    await waitFor(() => expect(screen.getByTestId('attachments.queued')).toBeTruthy());
    expect(screen.getByText("Will upload when you're online")).toBeTruthy();
    expect(screen.getAllByTestId('attachments.queuedItem')).toHaveLength(1);
    expect(api.signAttachmentCalls).toEqual([]);

    setOnline(true);
    await act(async () => {
      window.dispatchEvent(new Event('online'));
    });
    await waitFor(() => expect(screen.getByTestId('attachments.idle')).toBeTruthy());
    expect(api.signAttachmentCalls).toHaveLength(1);
    expect(puts).toHaveLength(1);
    expect(screen.queryAllByTestId('attachments.queuedItem')).toHaveLength(0);
    expect(screen.getAllByTestId('attachments.item')).toHaveLength(1);
  });

  it('CORE-11: AC5 a sign failure shows an error, records nothing, and Retry succeeds', async () => {
    const { api } = await renderPicker();
    api.signAttachmentResult = { ok: false, error: { code: 'unknown' } };
    choose('attachments.photo', photo());
    await waitFor(() => expect(screen.getByTestId('attachments.error')).toBeTruthy());
    expect(screen.getByRole('alert').textContent).toContain('Could not attach the file.');
    expect(screen.queryAllByTestId('attachments.item')).toHaveLength(0);
    expect(puts).toEqual([]);

    api.signAttachmentResult = {
      ok: true,
      value: { attachment_id: 'a1', upload_url: 'https://upload.test/a1', storage_path: 'h1/pantry_item/e1/a1.jpg' },
    };
    fireEvent.click(screen.getByTestId('attachments.retry'));
    await waitFor(() => expect(screen.getByTestId('attachments.idle')).toBeTruthy());
    expect(api.signAttachmentCalls).toHaveLength(2);
    expect(screen.getAllByTestId('attachments.item')).toHaveLength(1);
  });

  it('CORE-11: a failed PUT shows an error and Retry signs again', async () => {
    const { api } = await renderPicker();
    putResult = { ok: false, error: { code: 'unknown' } };
    choose('attachments.pdf', pdf());
    await waitFor(() => expect(screen.getByTestId('attachments.error')).toBeTruthy());
    putResult = { ok: true, value: null };
    fireEvent.click(screen.getByTestId('attachments.retry'));
    await waitFor(() => expect(screen.getByTestId('attachments.idle')).toBeTruthy());
    expect(api.signAttachmentCalls).toHaveLength(2);
  });

  it('CORE-11: a file the API would reject is refused without a request', async () => {
    const { api } = await renderPicker();
    choose('attachments.photo', new File(['z'], 'a.gif', { type: 'image/gif' }));
    await waitFor(() => expect(screen.getByRole('alert').textContent).toContain('up to 25 MB'));
    expect(screen.queryByTestId('attachments.retry')).toBeNull();
    expect(api.signAttachmentCalls).toEqual([]);
  });

  it('CORE-11: module off renders nothing', async () => {
    await renderPicker({ enabled: false });
    expect(screen.queryByRole('button', { name: 'Add photo' })).toBeNull();
    expect(screen.queryByLabelText('Attachments')).toBeNull();
  });
});
