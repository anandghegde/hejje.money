module.exports = {
  root: true,
  env: { browser: true, es2021: true, node: true },
  extends: [
    'eslint:recommended',
    'plugin:@typescript-eslint/recommended',
  ],
  parser: '@typescript-eslint/parser',
  parserOptions: { ecmaVersion: 'latest', sourceType: 'module' },
  plugins: ['react-hooks', 'react-refresh'],
  ignorePatterns: ['dist', 'node_modules', 'playwright-report', '*.cjs'],
  rules: {
    '@typescript-eslint/no-explicit-any': 'off',
    'react-hooks/rules-of-hooks': 'error',
    'react-hooks/exhaustive-deps': 'warn',
    // Phase 10: no inline styles anywhere; colours, sizes and spacing come from web/src/styles/tokens.css through classes
    // (docs/web.md, "Design system"). Charts take token colours through ui/useChartTheme; SVG geometry uses attributes.
    'no-restricted-syntax': ['error', {
      selector: "JSXAttribute[name.name='style']",
      message: 'No inline styles: use the design tokens and classes (web/src/styles, web/src/ui).',
    }],
  },
};
