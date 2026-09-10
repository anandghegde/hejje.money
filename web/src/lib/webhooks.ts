import { Webhook } from '../api/types';

/** Where the external system posts: the API base plus the webhook path. */
export function webhookUrl(apiBase: string, id: string): string {
  return `${apiBase.replace(/\/$/, '')}/webhooks/${id}`;
}

export function targetLabel(w: Webhook): string {
  return w.strategyVersionId ? `strategy version ${w.strategyVersionId.slice(0, 8)}…` : 'MANUAL_EXTERNAL (manual order proposals)';
}

/** "NSE:INFY, NSE:TCS" → ["NSE:INFY", "NSE:TCS"] (blank entries dropped). */
export function parseInstruments(text: string): string[] {
  return text.split(/[,\s]+/).map((s) => s.trim().toUpperCase()).filter(Boolean);
}
