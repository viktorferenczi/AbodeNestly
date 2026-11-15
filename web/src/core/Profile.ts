import type { Profile } from '@abodenestly/contract';
import type { AuthClient } from './auth/authClient.ts';

// Web has no Dexie/outbox/sync yet (SHOP-08), so the local profile and its pending edits live in
// localStorage, following membersCache.ts. SHOP-08 replaces all of this with the real outbox.

export type ProfileEdit = Partial<Pick<Profile, 'display_name' | 'avatar_path'>>;

/**
 * Internal queue shape, modelled on SPEC §6.2. The wire type lands in `packages/contract` with SHOP-08;
 * there is deliberately no contract change here.
 */
export type ProfileOutboxEntry = {
  id: string;
  table: 'profile';
  op: 'upsert';
  row_id: string;
  data: ProfileEdit;
  client_ts: string;
};

export type EditDeps = { now: () => string; uuid: () => string };

export const defaultDeps: EditDeps = {
  now: () => new Date().toISOString(),
  uuid: () => crypto.randomUUID(),
};

const profileKey = (uid: string) => `abodenestly:profile:${uid}`;
const outboxKey = (uid: string) => `abodenestly:outbox:profile:${uid}`;

export function readCachedProfile(uid: string): Profile | null {
  try {
    const raw = localStorage.getItem(profileKey(uid));
    return raw ? (JSON.parse(raw) as Profile) : null;
  } catch {
    return null;
  }
}

export function writeCachedProfile(uid: string, profile: Profile): void {
  try {
    localStorage.setItem(profileKey(uid), JSON.stringify(profile));
  } catch {
    // Storage blocked (private mode): the edit just isn't available offline.
  }
}

function isEdit(v: unknown): v is ProfileEdit {
  if (typeof v !== 'object' || v === null) return false;
  const { display_name, avatar_path } = v as Record<string, unknown>;
  if (display_name !== undefined && typeof display_name !== 'string') return false;
  if (avatar_path !== undefined && avatar_path !== null && typeof avatar_path !== 'string') return false;
  return true;
}

function isEntry(v: unknown): v is ProfileOutboxEntry {
  if (typeof v !== 'object' || v === null) return false;
  const e = v as Record<string, unknown>;
  return (
    typeof e.id === 'string' &&
    e.table === 'profile' &&
    e.op === 'upsert' &&
    typeof e.row_id === 'string' &&
    typeof e.client_ts === 'string' &&
    isEdit(e.data)
  );
}

/** Anything unexpected yields `[]` so a stale shape never crashes the screen. */
export function parseOutbox(raw: string | null): ProfileOutboxEntry[] {
  if (!raw) return [];
  try {
    const json: unknown = JSON.parse(raw);
    return Array.isArray(json) && json.every(isEntry) ? json : [];
  } catch {
    return [];
  }
}

export function readOutbox(uid: string): ProfileOutboxEntry[] {
  try {
    return parseOutbox(localStorage.getItem(outboxKey(uid)));
  } catch {
    return [];
  }
}

export function writeOutbox(uid: string, entries: ProfileOutboxEntry[]): void {
  try {
    localStorage.setItem(outboxKey(uid), JSON.stringify(entries));
  } catch {
    // Storage blocked: the edit stays in the local profile for this session only.
  }
}

export type ProfileEditResult = { profile: Profile; entry: ProfileOutboxEntry };

export function buildEdit(profile: Profile, data: ProfileEdit, deps: EditDeps): ProfileEditResult {
  return {
    // updated_at stays as fetched: it is server-owned (profile_touch).
    profile: { ...profile, ...data },
    entry: { id: deps.uuid(), table: 'profile', op: 'upsert', row_id: profile.id, data, client_ts: deps.now() },
  };
}

/** `null` when the trimmed name is empty (`display_name` is not null) or unchanged. */
export function editDisplayName(profile: Profile, draft: string, deps: EditDeps): ProfileEditResult | null {
  const name = draft.trim();
  if (!name || name === profile.display_name) return null;
  return buildEdit(profile, { display_name: name }, deps);
}

/** Writes the local profile and appends one outbox entry. No network. */
export function persistEdit(uid: string, edit: ProfileEditResult): Profile {
  writeCachedProfile(uid, edit.profile);
  writeOutbox(uid, [...readOutbox(uid), edit.entry]);
  return edit.profile;
}

export function queueProfileEdit(uid: string, profile: Profile, data: ProfileEdit, deps: EditDeps): Profile {
  return persistEdit(uid, buildEdit(profile, data, deps));
}

/** Overlays queued edits onto a fetched server row so a refetch does not revert an unsynced edit. */
export function withPending(profile: Profile, outbox: ProfileOutboxEntry[]): Profile {
  return outbox.reduce((p, e) => (e.row_id === p.id ? { ...p, ...e.data } : p), profile);
}

const EXTENSIONS: Record<string, string> = {
  'image/jpeg': 'jpg',
  'image/png': 'png',
  'image/webp': 'webp',
  'image/gif': 'gif',
};

export const isAvatarType = (type: string): boolean => Object.hasOwn(EXTENSIONS, type);

/** `profiles/<uid>/<uuid>.<ext>`: exactly two folders, as the Storage policy requires. `null` for other types. */
export function avatarStoragePath(uid: string, file: { type: string }, uuid: string): string | null {
  const ext = EXTENSIONS[file.type];
  return ext ? `profiles/${uid}/${uuid}.${ext}` : null;
}

/** SPEC §3: `avatar_path` is either a Storage object path or a provider URL. */
export function isStoragePath(avatarPath: string): boolean {
  return !/^https?:\/\//i.test(avatarPath);
}

export type DrainResult = { status: 'done' | 'network'; rejected: ProfileOutboxEntry[] };

/** Interim drain (until SHOP-08): applies queued edits in order with a direct update. */
export async function drainProfileOutbox(
  uid: string,
  client: Pick<AuthClient, 'updateProfile'>,
): Promise<DrainResult> {
  const rejected: ProfileOutboxEntry[] = [];
  for (;;) {
    // Re-read each round: an edit queued while a request was in flight must not be lost or skipped.
    const entry = readOutbox(uid)[0];
    if (!entry) return { status: 'done', rejected };
    const result = await client.updateProfile(uid, entry.data);
    if (!result.ok && result.error.code === 'network') return { status: 'network', rejected };
    // A rejected entry (RLS, validation) would block the queue forever, so it is dropped and reported.
    if (!result.ok) rejected.push(entry);
    writeOutbox(
      uid,
      readOutbox(uid).filter((e) => e.id !== entry.id),
    );
  }
}
