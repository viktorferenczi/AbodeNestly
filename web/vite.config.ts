import { defineConfig } from 'vitest/config';

// esbuild's automatic JSX runtime replaces @vitejs/plugin-react (one dependency fewer).
export default defineConfig({
  esbuild: { jsx: 'automatic' },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.{ts,tsx}'],
  },
});
