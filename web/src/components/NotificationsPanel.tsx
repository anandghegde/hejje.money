import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { request } from '../api/client';
import { ChannelStatus, HejjeNotification, NotificationRule, NotificationTestResult } from '../api/types';
import { CHANNELS, SEVERITY_COLOR, deliverySummary, ruleMatrix } from '../lib/notifications';

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
    <section data-testid="notifications-panel">
      <h2>Notifications</h2>
      {channels && (
        <ul>
          {channels.map((c) => (
            <li key={c.channel}>
              <b>{c.channel}</b> <span style={{ color: c.configured ? '#2e7d32' : '#616161' }}>{c.status}</span>
              {c.perMinute > 0 && <span style={{ color: '#616161' }}> · {c.perMinute}/min, then a digest</span>}
            </li>
          ))}
        </ul>
      )}
      <button onClick={() => test.mutate()} disabled={test.isPending}>Send a test notification</button>
      {tested && <p data-testid="notify-test-result">{tested}</p>}

      {rules && (
        <table style={{ borderCollapse: 'collapse', marginTop: 12 }}>
          <thead><tr><th style={{ textAlign: 'left' }}>Event</th>{CHANNELS.map((c) => <th key={c}>{c}</th>)}</tr></thead>
          <tbody>
            {ruleMatrix(rules).map((row) => (
              <tr key={row.eventType}>
                <td>{row.eventType}</td>
                {CHANNELS.map((c) => {
                  const r = row.rules[c];
                  return (
                    <td key={c} style={{ textAlign: 'center' }}>
                      {r ? <input type="checkbox" checked={r.enabled} title={`min ${r.minSeverity}`} onChange={() => toggle.mutate(r)} /> : '—'}
                    </td>
                  );
                })}
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <h3>Inbox</h3>
      {!inbox?.length && <p>No notifications yet.</p>}
      <ul style={{ listStyle: 'none', padding: 0 }}>
        {inbox?.map((n) => (
          <li key={n.id} style={{ margin: '6px 0', opacity: n.readAt ? 0.6 : 1 }}>
            <span style={{ color: SEVERITY_COLOR[n.severity], fontWeight: 700 }}>{n.severity}</span>{' '}
            <span>{n.title}</span> <small style={{ color: '#616161' }}>{new Date(n.createdAt).toLocaleString()}</small>
            {n.body && <div style={{ whiteSpace: 'pre-wrap', color: '#444' }}>{n.body}</div>}
            {!n.readAt && <button onClick={() => read.mutate(n.id)} style={{ fontSize: 12 }}>Mark read</button>}
          </li>
        ))}
      </ul>
    </section>
  );
}
