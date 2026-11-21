import { randomBytes } from 'node:crypto';
import pg from 'pg';
import type { SharedListResponse, ShareLinkResponse } from '@abodenestly/contract';
import type { Queryable } from './queryable.ts';
import { ApiError } from './errors.ts';

// 32 random bytes as base64url: 43 chars, unguessable. Anything else is a 404 without a query.
const TOKEN_RE = /^[A-Za-z0-9_-]{43}$/;
export const isShareToken = (s: string): boolean => TOKEN_RE.test(s);
export const newShareToken = (): string => randomBytes(32).toString('base64url');

export function shareUrl(token: string): string {
  const base = process.env.API_BASE_URL;
  if (!base) throw new Error('API_BASE_URL must be set');
  return `${base.replace(/\/+$/, '')}/shared/${token}`;
}

type ItemRow = { name: string; quantity: string | number | null; checked: boolean };

// The public payload (SPEC §11): list name, and unchecked items' names and quantities. Built field
// by field so a column added to the tables later can never leak.
export function toPublicList(name: string, items: ItemRow[]): SharedListResponse {
  return {
    list: { name },
    items: items
      .filter((i) => !i.checked)
      .map((i) => ({ name: i.name, quantity: i.quantity === null ? null : Number(i.quantity) })),
  };
}

// Runs inside withUser. A list the caller can't see is 404 whatever the reason (RLS hides it).
async function loadMemberList(client: Queryable, listId: string): Promise<void> {
  const list = await client.query(
    `select h.module_shopping as enabled
       from shopping_list l join household h on h.id = l.household_id
      where l.id = $1 and l.deleted_at is null`,
    [listId],
  );
  if (!list.rows[0]) throw new ApiError(404, 'not_found', 'List not found');
  if (!list.rows[0].enabled) throw new ApiError(403, 'module_disabled', 'The shopping module is off');
}

// Every POST mints a new token, so it also rotates (and so revokes) an existing link.
export async function createShare(client: Queryable, listId: string): Promise<ShareLinkResponse> {
  await loadMemberList(client, listId);
  const token = newShareToken();
  const res = await client.query('update shopping_list set share_token = $1 where id = $2 returning id', [
    token,
    listId,
  ]);
  if (!res.rows[0]) throw new ApiError(404, 'not_found', 'List not found');
  return { url: shareUrl(token) };
}

export async function revokeShare(client: Queryable, listId: string): Promise<void> {
  await loadMemberList(client, listId);
  await client.query('update shopping_list set share_token = null where id = $1', [listId]);
}

// The one non-RLS read (SPEC §11): GET /shared/{token} has no JWT, so RLS cannot apply. It gets its
// own small pool, private to this module, so no general owner-role bypass is exported and these two
// SELECTs are the only statements it can run. It connects as the pool's owner role.
let publicPool: pg.Pool | undefined;

// Not-found, deleted and module-off all give the same 404 so a token reveals nothing about why it
// is dead. A malformed token is rejected before a connection is taken (pool max is small).
export async function readShared(token: string): Promise<SharedListResponse> {
  if (!isShareToken(token)) throw new ApiError(404, 'not_found', 'Link not found');
  publicPool ??= new pg.Pool({ connectionString: process.env.DATABASE_URL, max: 2 });
  const client = await publicPool.connect();
  try {
    return await loadShared(client, token);
  } finally {
    client.release();
  }
}

export async function loadShared(client: Queryable, token: string): Promise<SharedListResponse> {
  const list = await client.query(
    `select l.id, l.name
       from shopping_list l join household h on h.id = l.household_id
      where l.share_token = $1 and l.deleted_at is null and h.module_shopping and h.deleted_at is null`,
    [token],
  );
  const row = list.rows[0];
  if (!row) throw new ApiError(404, 'not_found', 'Link not found');
  // Same order as the in-app list (SHOP-06): dragged rows first by manual_position, then learned
  // aisle order for the list's store, unseen items last, insert order as the tiebreak.
  const items = await client.query(
    `select i.name, i.quantity, i.checked
       from list_item i
       join shopping_list l on l.id = i.list_id
       left join store_item_order o on o.store_id = l.store_id and o.name_key = i.name_key
      where i.list_id = $1 and i.deleted_at is null and not i.checked
      order by i.manual_position nulls last, o.mean_position nulls last, i.created_at, i.id`,
    [row.id],
  );
  return toPublicList(row.name, items.rows);
}
