import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { request } from '../api/client';
import { ChannelStatus, HejjeNotification, NotificationRule, NotificationTestResult } from '../api/types';
import { CHANNELS, SEVERITY_TONE, deliverySummary, ruleMatrix } from '../lib/notifications';
import { Badge, Button, Card, EmptyState } from '../ui';
import '../styles/system.css';

/** Notification channels, rules and inbox (M5.5); rules, channels and the test need the admin scope. */
export function NotificationsPanel() {
  const qc = useQueryClient();
  const [tested, setTested] = useState<string | null>(null);
  const { data: inbox } = useQuery({ queryKey: ['notifications'], queryFn: () => request<HejjeNotification[]>('/notifications?limit=30') });
  const { data: channels } = useQuery({ queryKey: ['notification-channels'], queryFn: () => request<ChannelStatus[]>('/notifications/channels'), retry: false });
  const { data: rules } = useQuery({ queryKey: ['notification-rules'], queryFn: () => request<NotificationRule[]>('/notifications/rules'), retry: false });
  const toggle = useMutation({
    mutationFn: (r: NotificationRule) => request<NotificationRule>(`/notifications/rules/${r.id}`, { method: 'PUT', body: { enabled: !r.enabled } }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['notification-rules'] }),
  });
  const test = useMutation({
    mutationFn: () => request<NotificationTestResult>('/notifications/test', { method: 'POST' }),
    onSuccess: (r) => { setTested(deliverySummary(r.deliveries)); qc.invalidateQueries({ queryKey: ['notifications'] }); },
    onError: (e: Error) => setTested(e.message),
  });
  const read = useMutation({
    mutationFn: (id: string) => request(`/notifications/${id}/read`, { method: 'POST' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['notifications'] }),
  });

  return (
    <Card title="Notifications" data-testid="notifications-panel">
      <div className="stack">
        {channels && (
          <ul className="plain-list">
            {channels.map((c) => (
              <li key={c.channel} className="cluster">
                <b>{c.channel}</b> <Badge tone={c.configured ? 'profit' : 'neutral'}>{c.status}</Badge>
                {c.perMinute > 0 && <span className="muted">{c.perMinute}/min, then a digest</span>}
              </li>
            ))}
          </ul>
        )}
        <div><Button onClick={() => test.mutate()} disabled={test.isPending}>Send a test notification</Button></div>
        {tested && <p data-testid="notify-test-result" className="message">{tested}</p>}

        {rules && (
          <div className="table-scroll">
            <table>
              <thead><tr><th>Event</th>{CHANNELS.map((c) => <th key={c} className="center">{c}</th>)}</tr></thead>
              <tbody>
                {ruleMatrix(rules).map((row) => (
                  <tr key={row.eventType}>
                    <td>{row.eventType}</td>
                    {CHANNELS.map((c) => {
                      const r = row.rules[c];
                      return (
                        <td key={c} className="center">
                          {r ? <input type="checkbox" aria-label={`${row.eventType} ${c}`} checked={r.enabled} title={`min ${r.minSeverity}`} onChange={() => toggle.mutate(r)} /> : '—'}
                        </td>
                      );
                    })}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        <h3>Inbox</h3>
        {!inbox?.length && <EmptyState title="No notifications yet." />}
        <ul className="plain-list inbox">
          {inbox?.map((n) => (
            <li key={n.id} className={n.readAt ? 'inbox-item inbox-read' : 'inbox-item'}>
              <div className="cluster">
                <Badge tone={SEVERITY_TONE[n.severity]}>{n.severity}</Badge>
                <span>{n.title}</span> <small className="muted">{new Date(n.createdAt).toLocaleString()}</small>
                {!n.readAt && <Button size="sm" onClick={() => read.mutate(n.id)}>Mark read</Button>}
              </div>
              {n.body && <div className="inbox-body">{n.body}</div>}
            </li>
          ))}
        </ul>
      </div>
    </Card>
  );
}
