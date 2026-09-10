import { describe, expect, it } from 'vitest';
import { actionsText, decisionsFor, parseParams, sortRules } from '../src/lib/policies';
import { PolicyRule } from '../src/api/types';

const rule = (name: string, priority: number, condition: string, actions: string[] = []): PolicyRule => ({
  id: name, name, priority, condition, actions, decision: 'REQUIRE_APPROVAL', params: {}, enabled: true, description: '', updatedAt: '', updatedBy: 'seed',
});

describe('policy helpers', () => {
  it('orders rules by priority then name', () => {
    const sorted = sortRules([rule('strategy_signals', 90, 'ACTOR_STRATEGY'), rule('b', 15, 'X'), rule('a', 15, 'X'), rule('daily_loss_block', 10, 'DAILY_LOSS_EXCEEDED')]);
    expect(sorted.map((r) => r.name)).toEqual(['daily_loss_block', 'a', 'b', 'strategy_signals']);
  });
  it('offers ALLOW only on AUTO_ELIGIBLE rules', () => {
    expect(decisionsFor(rule('auto_strategy', 85, 'AUTO_ELIGIBLE'))).toContain('ALLOW');
    expect(decisionsFor(rule('event_risk_high', 40, 'EVENT_RISK_HIGH'))).toEqual(['REQUIRE_APPROVAL', 'DENY']);
  });
  it('describes actions and parses params', () => {
    expect(actionsText(rule('x', 1, 'ALWAYS'))).toBe('all actions');
    expect(actionsText(rule('x', 1, 'ALWAYS', ['ORDER_NEW']))).toBe('ORDER_NEW');
    expect(parseParams('{"threshold": 80}')).toEqual({ threshold: 80 });
    expect(parseParams('')).toEqual({});
    expect(parseParams('[1]')).toBeNull();
    expect(parseParams('{oops')).toBeNull();
  });
});
