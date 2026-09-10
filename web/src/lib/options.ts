export type LegRow = {
  action: 'buy' | 'sell'; option: 'directional' | 'opposite' | 'ce' | 'pe'; strike: 'atm' | 'offset' | 'delta'; strikeValue: string;
  expiry: 'nearest' | 'next' | 'monthly'; lots: number; stopPct: string; targetPct: string; hedgeFirst: boolean;
};

export const EMPTY_LEG: LegRow = { action: 'buy', option: 'directional', strike: 'atm', strikeValue: '', expiry: 'nearest', lots: 1, stopPct: '', targetPct: '', hedgeFirst: false };

/** The `legs:` block of an options strategy (docs/strategy-dsl.md, "Options legs"). */
export function legsYaml(rows: LegRow[]): string {
  const lines = ['legs:'];
  for (const r of rows) {
    lines.push(`  - action: ${r.action}`);
    lines.push(`    option: ${r.option}`);
    if (r.strike === 'atm' || r.strikeValue.trim() === '') lines.push('    strike: atm');
    else lines.push(`    strike: {${r.strike}: ${r.strikeValue.trim()}}`);
    lines.push(`    expiry: ${r.expiry}`);
    lines.push(`    lots: ${Math.max(1, Math.floor(r.lots))}`);
    if (r.stopPct.trim()) lines.push(`    stop_pct: ${r.stopPct.trim()}`);
    if (r.targetPct.trim()) lines.push(`    target_pct: ${r.targetPct.trim()}`);
    if (r.hedgeFirst && r.action === 'buy') lines.push('    hedge_first: true');
  }
  return lines.join('\n') + '\n';
}

/** Replaces the top-level `legs:` block of a definition (up to the next top-level key), or appends one. */
export function replaceLegs(yaml: string, legs: string): string {
  const lines = yaml.split('\n');
  const start = lines.findIndex((l) => /^legs:\s*$/.test(l));
  if (start < 0) return yaml.replace(/\n*$/, '\n\n') + legs;
  let end = start + 1;
  while (end < lines.length && (lines[end].startsWith(' ') || lines[end].startsWith('-') || lines[end].trim() === '')) end++;
  return [...lines.slice(0, start), ...legs.replace(/\n$/, '').split('\n'), ...lines.slice(end)].join('\n');
}

export function pct(v?: number | null, digits = 1): string {
  return v == null ? '—' : `${(v * 100).toFixed(digits)}%`;
}

/** In/at/out of the money for a strike relative to the ATM strike. */
export function moneyness(strike: number, atm: number | undefined, type: 'CE' | 'PE'): 'ITM' | 'ATM' | 'OTM' | '' {
  if (atm == null) return '';
  if (strike === atm) return 'ATM';
  return (type === 'CE' ? strike < atm : strike > atm) ? 'ITM' : 'OTM';
}
