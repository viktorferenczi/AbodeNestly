// Node 22 built-in. loadEnvFile never overrides variables that are already set, so the
// git-ignored local file goes first and wins over the committed defaults.
for (const file of ['.env.development.local', '.env.development']) {
  try {
    process.loadEnvFile(file);
  } catch {
    // the local file is optional
  }
}
