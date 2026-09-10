import { ExperimentVariant, SplitSummary, VariantMetrics } from '../api/types';

export const VERDICT_COLOR: Record<string, string> = {
  RECOMMENDED: '#2e7d32', BETTER_OUT_OF_SAMPLE: '#558b2f', BETTER_BUT_FRAGILE: '#ef6c00', NOT_BETTER: '#616161', BASELINE: '#1565c0',
};

/** The slice a variant is ranked on: out-of-sample, else validation, else overall (mirrors the server). */
export function basis(m: VariantMetrics): { name: string; split: SplitSummary } {
  if (m.outOfSample) return { name: 'OOS', split: m.outOfSample };
  if (m.validation) return { name: 'validation', split: m.validation };
  return { name: 'overall', split: m.overall };
}

/** Promotion is offered for finished, non-baseline, not yet promoted variants. */
export function canPromote(v: ExperimentVariant): boolean {
  return v.ordinal !== 0 && v.status === 'DONE' && !v.promotedVersionId;
}
