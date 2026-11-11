import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: 'e2e',
  fullyParallel: true,
  reporter: [['list']],
  use: { baseURL: 'http://localhost:5173' },
  // TESTING §2: Chromium plus a WebKit mobile viewport.
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
    { name: 'webkit-mobile', use: { ...devices['iPhone 13'] } },
  ],
  webServer: [
    { command: 'pnpm dev', url: 'http://localhost:5173', reuseExistingServer: true },
    // Welcome creates a household through the API (reached via the Vite /api proxy).
    {
      command: 'pnpm --filter @abodenestly/api dev',
      url: 'http://localhost:3000/health',
      reuseExistingServer: true,
    },
  ],
});
