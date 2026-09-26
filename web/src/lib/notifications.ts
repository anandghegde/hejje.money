import { NotificationChannel, NotificationDelivery, NotificationRule, NotificationSeverity } from '../api/types';

export const SEVERITY_TONE: Record<NotificationSeverity, 'info' | 'warning' | 'loss'> = { INFO: 'info', WARNING: 'warning', CRITICAL: 'loss' };
export const CHANNELS: NotificationChannel[] = ['IN_APP', 'EMAIL', 'TELEGRAM'];

export interface RuleRow { eventType: string; rules: Partial<Record<NotificationChannel, NotificationRule>>; }

/** Rules as one row per event type (in the server's order) with a cell per channel; a missing cell has no rule. */
export function ruleMatrix(rules: NotificationRule[]): RuleRow[] {
  const rows = new Map<string, RuleRow>();
  for (const r of rules) {
    const row = rows.get(r.eventType) ?? { eventType: r.eventType, rules: {} };
    row.rules[r.channel] = r;
    rows.set(r.eventType, row);
  }
  return [...rows.values()];
}

/** "IN_APP sent · TELEGRAM skipped (missing …)" for a delivery log. */
export function deliverySummary(deliveries: NotificationDelivery[]): string {
  return deliveries
    .map((d) => `${d.channel} ${d.status.toLowerCase().replace('_', ' ')}${d.detail ? ` (${d.detail})` : ''}`)
    .join(' · ');
}

export interface PushedNotification { id: string; notificationType: string; severity: NotificationSeverity; title: string; body: string; }

/** The payload of a `/ws/events` notification push, or null for any other event. */
export function pushedNotification(e: { type: string; data?: unknown }): PushedNotification | null {
  if (e.type !== 'notification' || !e.data || typeof e.data !== 'object') return null;
  const d = e.data as Record<string, unknown>;
  if (typeof d.id !== 'string' || typeof d.title !== 'string') return null;
  const severity = (['INFO', 'WARNING', 'CRITICAL'].includes(d.severity as string) ? d.severity : 'INFO') as NotificationSeverity;
  return { id: d.id, notificationType: String(d.notificationType ?? ''), severity, title: d.title, body: String(d.body ?? '') };
}
