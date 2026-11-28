import type { SharedListResponse } from '@abodenestly/contract';

export type PublicList = { name: string; items: Array<{ name: string; quantity: number | null }> };

// What the page may show (SPEC §11). The server already sends only these fields and only unchecked items; the
// client keeps just the same, so a field or a checked row that ever slipped in is not rendered.
export function mapPublicList(response: SharedListResponse): PublicList {
  return {
    name: response.list.name,
    items: response.items
      .filter((i) => (i as { checked?: unknown }).checked !== true)
      .map((i) => ({ name: i.name, quantity: i.quantity ?? null })),
  };
}
