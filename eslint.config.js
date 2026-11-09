import tseslint from 'typescript-eslint';

const modules = ['shopping', 'pantry', 'maintenance'];

// SPEC §2 principle 2 / AGENTS §2: a module never imports another module's code.
const moduleIsolation = modules.map((self) => ({
  files: [`web/src/${self}/**/*.{ts,tsx}`],
  rules: {
    'no-restricted-imports': [
      'error',
      {
        patterns: modules
          .filter((other) => other !== self)
          .map((other) => ({
            group: [`**/${other}`, `**/${other}/**`],
            message: `${self} must not import ${other} (SPEC §2 principle 2).`,
          })),
      },
    ],
  },
}));

export default tseslint.config(
  { ignores: ['**/dist/**', '**/node_modules/**', 'apps/**', '**/*.config.js'] },
  ...tseslint.configs.recommended,
  ...moduleIsolation,
);
