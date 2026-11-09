// Playwright runs in Node; the web tsconfig deliberately loads no Node types.
declare const process: { env: Record<string, string | undefined> };
