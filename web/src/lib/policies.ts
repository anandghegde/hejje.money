import { PolicyDecision, PolicyRule } from '../api/types';

/** Evaluation order: priority, then name (mirrors the server). */
export function sortRules(rules: PolicyRule[]): PolicyRule[] {
  return [...rules].sort((a, b) => a.priority - b.priority || a.name.localeCompare(b.name));
}

/** The server accepts ALLOW only on AUTO_ELIGIBLE rules (automatic execution of qualified strategy signals). */
export function decisionsFor(rule: PolicyRule): PolicyDecision[] {
  return rule.condition === 'AUTO_ELIGIBLE' ? ['ALLOW', 'REQUIRE_APPROVAL', 'DENY'] : ['REQUIRE_APPROVAL', 'DENY'];
}

export function actionsText(rule: PolicyRule): string {
  return rule.actions.length === 0 ? 'all actions' : rule.actions.join(', ');
}

/** Rule params are edited as JSON; null when the text is not a JSON object. */
export function parseParams(text: string): Record<string, unknown> | null {
  try {
    const value = JSON.parse(text.trim() === '' ? '{}' : text);
    return value && typeof value === 'object' && !Array.isArray(value) ? value : null;
  } catch {
    return null;
  }
}
