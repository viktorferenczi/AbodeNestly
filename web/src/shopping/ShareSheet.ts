// SHOP-13: the pure parts of the share sheet.

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
