import { describe, expect, it } from 'vitest';
import { deliverySummary, pushedNotification, ruleMatrix } from '../src/lib/notifications';
import { parseInstruments, targetLabel, webhookUrl } from '../src/lib/webhooks';
import { NotificationRule, Webhook } from '../src/api/types';

const rule = (eventType: string, channel: NotificationRule['channel'], enabled = true): NotificationRule => ({
  id: `${eventType}-${channel}`, eventType, channel, minSeverity: 'INFO', enabled, updatedAt: '2026-09-10T00:00:00Z', updatedBy: 'seed',
});

describe('notification helpers', () => {
  it('groups rules into one row per event with a cell per channel', () => {
    const rows = ruleMatrix([rule('KILL_SWITCH', 'EMAIL'), rule('KILL_SWITCH', 'IN_APP'), rule('SIGNAL_GENERATED', 'IN_APP', false)]);
    expect(rows.map((r) => r.eventType)).toEqual(['KILL_SWITCH', 'SIGNAL_GENERATED']);
    expect(rows[0].rules.EMAIL?.id).toBe('KILL_SWITCH-EMAIL');
    expect(rows[1].rules.TELEGRAM).toBeUndefined();
    expect(rows[1].rules.IN_APP?.enabled).toBe(false);
  });

  it('summarises a delivery log', () => {
    expect(deliverySummary([
      { channel: 'IN_APP', status: 'SENT', createdAt: '' },
      { channel: 'TELEGRAM', status: 'DIGEST_SENT', createdAt: '' },
      { channel: 'EMAIL', status: 'SKIPPED', detail: 'disabled', createdAt: '' },
    ])).toBe('IN_APP sent · TELEGRAM digest sent · EMAIL skipped (disabled)');
  });

  it('reads only notification pushes', () => {
    expect(pushedNotification({ type: 'approval', data: { id: 'x', title: 't' } })).toBeNull();
    expect(pushedNotification({ type: 'notification', data: { title: 'no id' } })).toBeNull();
    expect(pushedNotification({ type: 'notification', data: { id: 'n1', title: 'Stop triggered', severity: 'WARNING', notificationType: 'STOP_TRIGGERED' } }))
      .toEqual({ id: 'n1', notificationType: 'STOP_TRIGGERED', severity: 'WARNING', title: 'Stop triggered', body: '' });
    expect(pushedNotification({ type: 'notification', data: { id: 'n2', title: 't', severity: 'LOUD' } })?.severity).toBe('INFO');
  });
});

describe('webhook helpers', () => {
  const hook: Webhook = {
    id: 'w1', name: 'tv', authMode: 'HMAC', strategyVersionId: '01234567-89ab', enabled: true, allowedInstruments: [], createdAt: '', createdBy: '', updatedAt: '',
  };
  it('builds the receiving URL and labels the target', () => {
    expect(webhookUrl('http://localhost:8080/api/v1/', 'w1')).toBe('http://localhost:8080/api/v1/webhooks/w1');
    expect(targetLabel(hook)).toBe('strategy version 01234567…');
    expect(targetLabel({ ...hook, strategyVersionId: null })).toContain('MANUAL_EXTERNAL');
  });
  it('parses the allowed instruments', () => {
    expect(parseInstruments(' nse:infy, NSE:TCS  ,,')).toEqual(['NSE:INFY', 'NSE:TCS']);
    expect(parseInstruments('')).toEqual([]);
  });
});
