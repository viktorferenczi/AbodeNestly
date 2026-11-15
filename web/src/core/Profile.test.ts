import type { Profile } from '@abodenestly/contract';
import { beforeEach, describe, expect, it } from 'vitest';
import { FakeAuthClient } from './auth/FakeAuthClient.ts';
import {
  avatarStoragePath,
  drainProfileOutbox,
  editDisplayName,
  isStoragePath,
  parseOutbox,
  persistEdit,
  queueProfileEdit,
  readCachedProfile,
  readOutbox,
  withPending,
  writeOutbox,
  type EditDeps,
  type ProfileOutboxEntry,
} from './Profile.ts';

const UID = 'u1';
const profile = { id: UID, display_name: 'Ada', avatar_path: null, created_at: 'x', updated_at: 'y' } as Profile;

function deps(): EditDeps {
  let n = 0;
  return { now: () => '2026-11-15T10:00:00.000Z', uuid: () => `id-${++n}` };
}

const entry = (id: string, data: ProfileOutboxEntry['data']): ProfileOutboxEntry => ({
  id,
  table: 'profile',
  op: 'upsert',
  row_id: UID,
  data,
  client_ts: 't',
});

beforeEach(() => localStorage.clear());

describe('CORE-06: profile edits', () => {
  it('AC2: a name edit appends exactly one outbox upsert, updates the local profile and makes no client call', () => {
    const client = new FakeAuthClient();
    const edit = editDisplayName(profile, '  Grace  ', deps());
    expect(edit).not.toBeNull();
    const local = persistEdit(UID, edit!);
    expect(local.display_name).toBe('Grace');
    expect(readCachedProfile(UID)?.display_name).toBe('Grace');
    expect(readOutbox(UID)).toEqual([
      {
        id: 'id-1',
        table: 'profile',
        op: 'upsert',
        row_id: UID,
        data: { display_name: 'Grace' },
        client_ts: '2026-11-15T10:00:00.000Z',
      },
    ]);
    expect(client.updateCalls).toEqual([]);
    expect(client.uploadCalls).toEqual([]);
  });

  it('CORE-06: blank or unchanged names make no entry', () => {
    expect(editDisplayName(profile, '   ', deps())).toBeNull();
    expect(editDisplayName(profile, 'Ada', deps())).toBeNull();
    expect(editDisplayName(profile, ' Ada ', deps())).toBeNull();
  });

  it('CORE-06: queueProfileEdit appends to existing entries in order', () => {
    const d = deps();
    queueProfileEdit(UID, profile, { display_name: 'A' }, d);
    queueProfileEdit(UID, profile, { avatar_path: 'profiles/u1/x.png' }, d);
    expect(readOutbox(UID).map((e) => e.data)).toEqual([{ display_name: 'A' }, { avatar_path: 'profiles/u1/x.png' }]);
  });

  it('CORE-06: parseOutbox rejects bad shapes and bad JSON', () => {
    expect(parseOutbox(null)).toEqual([]);
    expect(parseOutbox('not json')).toEqual([]);
    expect(parseOutbox('{}')).toEqual([]);
    expect(parseOutbox(JSON.stringify([{ ...entry('a', {}), table: 'member' }]))).toEqual([]);
    expect(parseOutbox(JSON.stringify([{ ...entry('a', {}), op: 'delete' }]))).toEqual([]);
    expect(parseOutbox(JSON.stringify([entry('a', { display_name: 5 as unknown as string })]))).toEqual([]);
    expect(parseOutbox(JSON.stringify([entry('a', { display_name: 'ok' }), 7]))).toEqual([]);
    const good = [entry('a', { display_name: 'ok' }), entry('b', { avatar_path: null })];
    expect(parseOutbox(JSON.stringify(good))).toEqual(good);
  });

  it('CORE-06: withPending overlays queued edits in order and ignores other rows', () => {
    const other = { ...entry('c', { display_name: 'Nope' }), row_id: 'someone-else' };
    const merged = withPending(profile, [entry('a', { display_name: 'B' }), entry('b', { avatar_path: 'p' }), other]);
    expect(merged).toMatchObject({ display_name: 'B', avatar_path: 'p' });
    expect(withPending(profile, [])).toEqual(profile);
  });

  it('CORE-06: avatarStoragePath builds a two-folder path from the MIME type', () => {
    expect(avatarStoragePath(UID, { type: 'image/png' }, 'abc')).toBe('profiles/u1/abc.png');
    expect(avatarStoragePath(UID, { type: 'image/jpeg' }, 'abc')).toBe('profiles/u1/abc.jpg');
    expect(avatarStoragePath(UID, { type: 'image/webp' }, 'abc')).toBe('profiles/u1/abc.webp');
    expect(avatarStoragePath(UID, { type: 'image/gif' }, 'abc')).toBe('profiles/u1/abc.gif');
    expect(avatarStoragePath(UID, { type: 'image/heic' }, 'abc')).toBeNull();
    expect(avatarStoragePath(UID, { type: 'image/png' }, 'abc')!.split('/')).toHaveLength(3);
  });

  it('CORE-06: isStoragePath tells Storage paths from provider URLs', () => {
    expect(isStoragePath('profiles/u1/abc.png')).toBe(true);
    expect(isStoragePath('https://lh3.googleusercontent.com/a')).toBe(false);
    expect(isStoragePath('http://example.com/a.png')).toBe(false);
  });
});

describe('CORE-06: drainProfileOutbox', () => {
  it('applies entries in order and empties the queue', async () => {
    const client = new FakeAuthClient();
    writeOutbox(UID, [entry('a', { display_name: 'A' }), entry('b', { avatar_path: 'p' })]);
    const result = await drainProfileOutbox(UID, client);
    expect(result).toEqual({ status: 'done', rejected: [] });
    expect(client.updateCalls).toEqual([
      { userId: UID, data: { display_name: 'A' } },
      { userId: UID, data: { avatar_path: 'p' } },
    ]);
    expect(readOutbox(UID)).toEqual([]);
  });

  it('stops on a network error and keeps the entry for later', async () => {
    const client = new FakeAuthClient();
    client.updateError = { code: 'network' };
    writeOutbox(UID, [entry('a', { display_name: 'A' }), entry('b', { display_name: 'B' })]);
    const result = await drainProfileOutbox(UID, client);
    expect(result.status).toBe('network');
    expect(client.updateCalls).toHaveLength(1);
    expect(readOutbox(UID).map((e) => e.id)).toEqual(['a', 'b']);
  });

  it('drops a rejected entry, reports it and continues with the next', async () => {
    const client = new FakeAuthClient();
    client.updateError = { code: 'unknown' };
    writeOutbox(UID, [entry('a', { display_name: 'A' })]);
    const result = await drainProfileOutbox(UID, client);
    expect(result.status).toBe('done');
    expect(result.rejected.map((e) => e.id)).toEqual(['a']);
    expect(readOutbox(UID)).toEqual([]);
  });
});
