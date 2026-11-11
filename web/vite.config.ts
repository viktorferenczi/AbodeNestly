import { defineConfig } from 'vitest/config';

// esbuild's automatic JSX runtime replaces @vitejs/plugin-react (one dependency fewer).
export default defineConfig({
  esbuild: { jsx: 'automatic' },
  // Pinned so Supabase redirect URLs (supabase/config.toml) and Playwright agree.
  server: {
    port: 5173,
    strictPort: true,
    // The API has no CORS, so dev calls go same-origin through /api (VITE_API_BASE_URL=/api).
    proxy: { '/api': { target: 'http://localhost:3000', rewrite: (path) => path.replace(/^\/api/, '') } },
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.{ts,tsx}'],
  },
});
