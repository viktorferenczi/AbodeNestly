// Generates the Kotlin models for apps/android/contract from docs/openapi.yaml (SPEC §3 Tooling).
// Same shape as swift.ts: shared entities only, plus the serializer support files the models compile
// against. The output is committed and CI fails if it is stale. Requires Java.
import { spawnSync } from 'node:child_process';
import { cpSync, mkdirSync, mkdtempSync, readdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { componentSchemas } from '../src/routes.ts';

const root = fileURLToPath(new URL('../../../', import.meta.url));
const spec = join(root, 'docs/openapi.yaml');
const pkg = 'com/abodenestly/contract';
const out = join(root, 'apps/android/contract/src/main/kotlin', pkg);
const packageDir = fileURLToPath(new URL('..', import.meta.url));

// Models use @Contextual UUID / OffsetDateTime, so Serializer.kt (the Json instance with the contextual
// module) and every adapter it registers must come along.
const SUPPORT = [
  'Serializer.kt',
  'AtomicBooleanAdapter.kt',
  'AtomicIntegerAdapter.kt',
  'AtomicLongAdapter.kt',
  'BigDecimalAdapter.kt',
  'BigIntegerAdapter.kt',
  'LocalDateAdapter.kt',
  'LocalDateTimeAdapter.kt',
  'OffsetDateTimeAdapter.kt',
  'StringBuilderAdapter.kt',
  'URIAdapter.kt',
  'URLAdapter.kt',
  'UUIDAdapter.kt',
];

const work = mkdtempSync(join(tmpdir(), 'openapi-kotlin-'));
try {
  const result = spawnSync(
    'pnpm',
    [
      'exec',
      'openapi-generator-cli',
      'generate',
      '-i',
      spec,
      '-g',
      'kotlin',
      '-o',
      work,
      '--global-property',
      // Shared entities only: the generator emits an invalid enum for `const: true` (HealthResponse).
      `models=${Object.keys(componentSchemas).join(':')},supportingFiles`,
      '--additional-properties=serializationLibrary=kotlinx_serialization,packageName=com.abodenestly.contract,dateLibrary=java8',
    ],
    { cwd: packageDir, stdio: ['ignore', 'ignore', 'inherit'] },
  );
  if (result.status !== 0) throw new Error(`openapi-generator failed (exit ${result.status})`);

  rmSync(out, { recursive: true, force: true });
  mkdirSync(join(out, 'infrastructure'), { recursive: true });
  const generated = join(work, 'src/main/kotlin', pkg);
  cpSync(join(generated, 'models'), join(out, 'models'), { recursive: true });
  const available = readdirSync(join(generated, 'infrastructure'));
  for (const file of SUPPORT) {
    if (!available.includes(file)) throw new Error(`missing ${file}`);
    cpSync(join(generated, 'infrastructure', file), join(out, 'infrastructure', file));
  }
  console.log(`wrote ${out}`);
} finally {
  rmSync(work, { recursive: true, force: true });
}
