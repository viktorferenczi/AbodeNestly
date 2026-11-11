import { existsSync } from 'node:fs';
import { defineConfig } from 'vitest/config';

// Integration tests: real HTTP against the local server and `supabase start`. TESTING §1.
// Node's loadEnvFile never overrides values already set, so load the git-ignored
// .local file first and the committed defaults second.
for (const file of ['.env.development.local', '.env.development']) {
  if (existsSync(file)) process.loadEnvFile(file);
}

export default defineConfig({
  test: {
    include: ['test/**/*.int.test.ts'],
  },
});
