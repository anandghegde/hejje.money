import { Recommendation } from '../api/types';
import { BadgeTone } from '../ui';

/** Badge tone for a PRD 15 decision. */
export function decisionTone(decision: Recommendation['decision']): BadgeTone {
  return decision === 'TRADE' ? 'profit' : decision === 'AVOID' ? 'loss' : decision === 'WAIT' ? 'neutral' : 'warning';
}

/** Reward:risk of a recommendation from its levels, or null when a level is missing. */
export function rewardRisk(r: Pick<Recommendation, 'entry' | 'stop' | 'target'>): number | null {
  if (r.entry == null || r.stop == null || r.target == null) return null;
  const risk = Math.abs(r.entry - r.stop);
  if (risk === 0) return null;
  return Math.round((Math.abs(r.target - r.entry) / risk) * 100) / 100;
}

/** Whole seconds a signal stays valid, never negative. */
export function secondsLeft(validUntil: string | undefined, now: Date = new Date()): number {
  if (!validUntil) return 0;
  return Math.max(0, Math.floor((new Date(validUntil).getTime() - now.getTime()) / 1000));
}

/** "+0.42R" style formatting. */
export function formatR(r: number | null | undefined, digits = 2): string {
  if (r == null || Number.isNaN(r)) return '—';
  return `${r >= 0 ? '+' : ''}${r.toFixed(digits)}R`;
}
