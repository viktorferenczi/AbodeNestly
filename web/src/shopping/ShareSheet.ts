// SHOP-13: the pure parts of the share sheet, and the local write of the server's share token.
import type { HouseholdDb } from '../sync/db.ts';

/**
 * The page a link opens is the web app's `/shared/:token`. The API's own link (`API_BASE_URL/shared/<token>`) is the
 * JSON endpoint, which is a different origin in dev and in production, so only the token is taken from it.
 */
export function tokenFromUrl(url: string): string | null {
  try {
    const segments = new URL(url, 'http://localhost').pathname.split('/').filter(Boolean);
    return segments.length > 0 ? decodeURIComponent(segments[segments.length - 1]!) : null;
  } catch {
    return null;
  }
}

export const shareLink = (origin: string, token: string): string => `${origin.replace(/\/+$/, '')}/shared/${encodeURIComponent(token)}`;

/**
 * `share_token` is server-owned (SPEC §6.2), so it never goes through the outbox. After a successful POST or DELETE the
 * server's value is written straight onto the local row, so reopening the sheet shows the live link (or none) even
 * before a pull, and even when a pending or failed mutation makes the pull skip this row.
 */
export async function recordShare(db: HouseholdDb, listId: string, token: string | null): Promise<void> {
  await db.shopping_list.update(listId, { share_token: token });
}
