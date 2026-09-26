import { ExperimentVariant, SplitSummary, VariantMetrics } from '../api/types';
import type { BadgeTone } from '../ui';

export const VERDICT_TONE: Record<string, BadgeTone> = {
  RECOMMENDED: 'profit', BETTER_OUT_OF_SAMPLE: 'profit', BETTER_BUT_FRAGILE: 'warning', NOT_BETTER: 'neutral', BASELINE: 'info',
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
