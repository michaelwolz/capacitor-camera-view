/** @type {import('eslint').Linter.FlatConfig[]} */
module.exports = [
  {
    ignores: ['build/**', 'dist/**', 'example-app/**', '.build/**'],
  },
  {
    files: ['**/*.ts'],
    languageOptions: {
      parser: require('@typescript-eslint/parser'),
      parserOptions: {
        ecmaVersion: 'latest',
        sourceType: 'module',
      },
    },
    plugins: {
      '@typescript-eslint': require('@typescript-eslint/eslint-plugin'),
      prettier: require('eslint-plugin-prettier'),
    },
    rules: {
      // Run Prettier as an ESLint rule.
      'prettier/prettier': 'error',
    },
  },
  // Turn off rules that conflict with Prettier.
  require('eslint-config-prettier'),
];
