import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { ApiProvider } from '../../core/api/ApiProvider.tsx';
import { FakeApiClient } from '../../core/api/FakeApiClient.ts';
import { SharedList } from './SharedList.tsx';

const TOKEN = 'A'.repeat(43);

function renderAt(api: FakeApiClient, token = TOKEN) {
  const router = createMemoryRouter([{ path: '/shared/:token', element: <SharedList /> }], { initialEntries: [`/shared/${token}`] });
  render(
    <ApiProvider client={api}>
      <RouterProvider router={router} />
    </ApiProvider>,
  );
}

afterEach(cleanup);

describe('SHOP-13: public shared list (C9)', () => {
  it('populated: the list name and the items with quantities, and nothing to edit', async () => {
    const api = new FakeApiClient();
    api.sharedResult = { ok: true, value: { list: { name: 'Groceries' }, items: [{ name: 'Milk', quantity: 2 }, { name: 'Bread', quantity: null }] } };
    renderAt(api);
    expect(screen.getByText('Loading…')).toBeTruthy();
    expect(await screen.findByRole('heading', { level: 1, name: 'Groceries' })).toBeTruthy();
    expect(screen.getByText('Milk · 2')).toBeTruthy();
    expect(screen.getByText('Bread')).toBeTruthy();
    expect(screen.getByText('Made with AbodeNestly')).toBeTruthy();
    expect(screen.queryAllByRole('button')).toHaveLength(0);
    expect(screen.queryAllByRole('checkbox')).toHaveLength(0);
    expect(screen.queryAllByRole('link')).toHaveLength(0);
    expect(api.sharedCalls).toEqual([TOKEN]);
  });

  it('empty: the name and "Nothing left to buy"', async () => {
    const api = new FakeApiClient();
    api.sharedResult = { ok: true, value: { list: { name: 'Groceries' }, items: [] } };
    renderAt(api);
    expect(await screen.findByText('Nothing left to buy')).toBeTruthy();
    expect(screen.getByRole('heading', { name: 'Groceries' })).toBeTruthy();
  });

  it('expired: an unknown token (404)', async () => {
    const api = new FakeApiClient();
    renderAt(api);
    expect(await screen.findByRole('heading', { name: 'This link has expired' })).toBeTruthy();
    expect(screen.getByText('Ask whoever shared it for a new link')).toBeTruthy();
  });

  it('expired: a malformed token makes no request', async () => {
    const api = new FakeApiClient();
    renderAt(api, 'nope');
    expect(await screen.findByRole('heading', { name: 'This link has expired' })).toBeTruthy();
    expect(api.sharedCalls).toEqual([]);
  });

  it('error: shows a message and Retry loads again', async () => {
    const api = new FakeApiClient();
    api.sharedResult = { ok: false, error: { code: 'network' } };
    renderAt(api);
    expect(await screen.findByText('Could not load the list. Check your connection.')).toBeTruthy();
    api.sharedResult = { ok: true, value: { list: { name: 'Groceries' }, items: [{ name: 'Milk', quantity: 1 }] } };
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByRole('heading', { name: 'Groceries' })).toBeTruthy();
    expect(api.sharedCalls).toHaveLength(2);
  });

  it('is marked noindex while mounted', async () => {
    const api = new FakeApiClient();
    renderAt(api);
    await screen.findByRole('heading', { name: 'This link has expired' });
    await waitFor(() => expect(document.head.querySelector('meta[name="robots"][content="noindex"]')).not.toBeNull());
    cleanup();
    expect(document.head.querySelector('meta[name="robots"]')).toBeNull();
  });
});
