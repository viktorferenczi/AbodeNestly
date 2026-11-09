import { defineConfig } from 'vitest/config';

// esbuild's automatic JSX runtime replaces @vitejs/plugin-react (one dependency fewer).
export default defineConfig({
  esbuild: { jsx: 'automatic' },
  // Pinned so Supabase redirect URLs (supabase/config.toml) and Playwright agree.
  server: { port: 5173, strictPort: true },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.{ts,tsx}'],
  },
});
