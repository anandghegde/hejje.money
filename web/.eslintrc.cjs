/**
 * Files not yet moved onto the design tokens (plan Phase 10). They may still use inline `style` props; every other file
 * may not. The list shrinks milestone by milestone and is empty at the end of the phase.
 */
const INLINE_STYLE_ALLOW_LIST = [
  'src/components/ApiKeysPanel.tsx',
  'src/components/Baskets.tsx',
  'src/components/ContextCard.tsx',
  'src/components/DescribeStrategy.tsx',
  'src/components/DriftPanel.tsx',
  'src/components/EquityChart.tsx',
  'src/components/Experiments.tsx',
  'src/components/LegBuilder.tsx',
  'src/components/LossInvestigation.tsx',
  'src/components/NewsBiasPanel.tsx',
  'src/components/NotificationsPanel.tsx',
  'src/components/WebhooksPanel.tsx',
  'src/pages/Agent.tsx',
  'src/pages/Analytics.tsx',
  'src/pages/Approvals.tsx',
  'src/pages/Broker.tsx',
  'src/pages/Lab.tsx',
  'src/pages/Login.tsx',
  'src/pages/ManualOrder.tsx',
  'src/pages/Options.tsx',
  'src/pages/Orders.tsx',
  'src/pages/Policies.tsx',
  'src/pages/Positions.tsx',
  'src/pages/Pulse.tsx',
  'src/pages/Risk.tsx',
  'src/pages/Screener.tsx',
  'src/pages/Stock.tsx',
  'src/pages/StrategyDetail.tsx',
  'src/pages/Today.tsx',
];

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
    'no-restricted-syntax': ['error', {
      selector: "JSXAttribute[name.name='style']",
      message: 'No inline styles: use the design tokens and classes (web/src/styles, web/src/ui).',
    }],
  },
  overrides: [
    { files: INLINE_STYLE_ALLOW_LIST, rules: { 'no-restricted-syntax': 'off' } },
  ],
};
