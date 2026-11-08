import { defineConfig } from 'vitest/config';

// Integration tests: real HTTP against the local server (and `supabase start` once
// endpoints touch the database). TESTING §1.
export default defineConfig({
  test: {
    include: ['test/**/*.int.test.ts'],
  },
});
