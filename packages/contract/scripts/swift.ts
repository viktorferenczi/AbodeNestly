// Generates the Swift models for apps/ios/Packages/Contract from docs/openapi.yaml (SPEC §3 Tooling).
// Only models plus the few supporting files they compile against are kept; the output is committed
// and CI fails if it is stale. Requires Java (the generator is a JAR fetched by openapi-generator-cli).
import { spawnSync } from 'node:child_process';
import { cpSync, mkdirSync, mkdtempSync, readdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { componentSchemas } from '../src/routes.ts';

const root = fileURLToPath(new URL('../../../', import.meta.url));
const spec = join(root, 'docs/openapi.yaml');
const out = join(root, 'apps/ios/Packages/Contract/Sources/Contract');
const packageDir = fileURLToPath(new URL('..', import.meta.url));

// What the generated models reference: ParameterConvertible (Models.swift) and the shared date/JSON coding.
const SUPPORT = [
  'Models.swift',
  'CodableHelper.swift',
  'Extensions.swift',
  'URLSessionImplementations.swift',
  'APIs.swift',
  'APIHelper.swift',
  'Validation.swift',
  'JSONDataEncoding.swift',
  'JSONEncodingHelper.swift',
  'SynchronizedDictionary.swift',
  'OpenISO8601DateFormatter.swift',
  'JSONValue.swift',
];

const work = mkdtempSync(join(tmpdir(), 'openapi-swift-'));
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
      'swift6',
      '-o',
      work,
      '--global-property',
      // Shared entities only: the generator emits an invalid enum for `const: true` (HealthResponse).
      `models=${Object.keys(componentSchemas).join(':')},supportingFiles`,
      '--additional-properties=projectName=Contract,library=urlsession,hashableModels=true',
    ],
    { cwd: packageDir, stdio: ['ignore', 'ignore', 'inherit'] },
  );
  if (result.status !== 0) throw new Error(`openapi-generator failed (exit ${result.status})`);

  rmSync(out, { recursive: true, force: true });
  mkdirSync(join(out, 'Infrastructure'), { recursive: true });
  const generated = join(work, 'Sources/Contract');
  cpSync(join(generated, 'Models'), join(out, 'Models'), { recursive: true });
  for (const file of SUPPORT) {
    if (!readdirSync(join(generated, 'Infrastructure')).includes(file)) throw new Error(`missing ${file}`);
    cpSync(join(generated, 'Infrastructure', file), join(out, 'Infrastructure', file));
  }
  console.log(`wrote ${out}`);
} finally {
  rmSync(work, { recursive: true, force: true });
}
