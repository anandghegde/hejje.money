import { describe, expect, it } from 'vitest';
import { EMPTY_LEG, legsYaml, moneyness, pct, replaceLegs } from '../src/lib/options';

describe('options helpers', () => {
  it('writes the legs block of a spread', () => {
    const yaml = legsYaml([{ ...EMPTY_LEG, hedgeFirst: true }, { ...EMPTY_LEG, action: 'sell', strike: 'offset', strikeValue: '100', hedgeFirst: true }]);
    expect(yaml).toBe('legs:\n  - action: buy\n    option: directional\n    strike: atm\n    expiry: nearest\n    lots: 1\n    hedge_first: true\n'
      + '  - action: sell\n    option: directional\n    strike: {offset: 100}\n    expiry: nearest\n    lots: 1\n');
  });
  it('replaces an existing legs block or appends one', () => {
    const def = 'name: x\nlegs:\n  - action: buy\n    lots: 2\nevent_rules:\n  action: allow\n';
    expect(replaceLegs(def, 'legs:\n  - action: sell\n')).toBe('name: x\nlegs:\n  - action: sell\nevent_rules:\n  action: allow\n');
    expect(replaceLegs('name: x\n', 'legs:\n  - action: buy\n')).toBe('name: x\n\nlegs:\n  - action: buy\n');
  });
  it('formats IV and moneyness', () => {
    expect(pct(0.0966)).toBe('9.7%');
    expect(pct(undefined)).toBe('—');
    expect(moneyness(24900, 25000, 'CE')).toBe('ITM');
    expect(moneyness(24900, 25000, 'PE')).toBe('OTM');
    expect(moneyness(25000, 25000, 'PE')).toBe('ATM');
  });
});
