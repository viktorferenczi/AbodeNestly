import { describe, expect, it } from 'vitest';
import handler from '../health.ts';

describe('GET /health', () => {
  it('INIT: body is { ok: true }', async () => {
    const res = await handler();
    expect(await res.json()).toEqual({ ok: true });
  });
});
