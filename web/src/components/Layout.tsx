import { ReactNode, useState } from 'react';
import { NavLink, useNavigate } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { request } from '../api/client';
import { Approval, Health } from '../api/types';
import { useEventsSocket } from '../ws/useEventsSocket';
import { useAuth } from '../auth/AuthContext';
import { PushedNotification, SEVERITY_COLOR, pushedNotification } from '../lib/notifications';

const ACTIVE = ['Today', 'Approvals', 'Screener', 'Strategies', 'Lab', 'Orders', 'Trades', 'Positions', 'Reviews', 'Analytics', 'Hejje AI', 'Risk', 'Broker', 'Server', 'Settings'];
const PLACEHOLDERS = ['Pulse'];

const ROUTES: Record<string, string> = {
  Today: '/today', Approvals: '/approvals', Pulse: '/pulse', Screener: '/screener', Strategies: '/strategies', Lab: '/lab', Orders: '/orders', Trades: '/trades', Positions: '/positions',
  Reviews: '/reviews', Analytics: '/analytics', Options: '/options', 'Hejje AI': '/agent', Risk: '/risk', Broker: '/broker', Server: '/system', Settings: '/settings',
};

function Dot({ ok, label }: { ok: boolean; label: string }) {
  return <span title={label} style={{ color: ok ? '#1a9f57' : '#c0392b' }}>● {label}</span>;
}

export function Layout({ children }: { children: ReactNode }) {
  const nav = useNavigate();
  const { logout } = useAuth();
  const { data: health } = useQuery({
    queryKey: ['health'],
    queryFn: () => request<Health>('/server/health'),
    refetchInterval: 5000,
  });

  const qc = useQueryClient();
  const { data: pendingApprovals } = useQuery({
    queryKey: ['approvals', 'PENDING'],
    queryFn: () => request<Approval[]>('/approvals?status=PENDING'),
    refetchInterval: 10000,
    retry: false,
  });
  const [toasts, setToasts] = useState<PushedNotification[]>([]);
  useEventsSocket((e) => {
    if (e.type === 'approval') qc.invalidateQueries({ queryKey: ['approvals'] });
    const n = pushedNotification(e);
    if (n) {
      qc.invalidateQueries({ queryKey: ['notifications'] });
      setToasts((t) => [...t.slice(-3), n]);
      setTimeout(() => setToasts((t) => t.filter((x) => x.id !== n.id)), 8000);
      if (typeof Notification !== 'undefined' && Notification.permission === 'granted') new Notification(n.title, { body: n.body });
    }
  });
  const pending = pendingApprovals?.length ?? 0;
  const mode = health?.mode ?? 'PAPER';
  const live = mode === 'CONFIRM' || mode === 'AUTO';

  return (
    <div style={{ fontFamily: 'system-ui, sans-serif', display: 'flex', minHeight: '100vh' }}>
      <nav style={{ width: 180, background: '#1c2530', color: '#fff', padding: 16 }}>
        <h2 style={{ fontSize: 18 }}>Hejje</h2>
        {ACTIVE.map((name) => (
          <div key={name} style={{ margin: '8px 0' }}>
            <NavLink to={ROUTES[name]} style={({ isActive }) => ({ color: isActive ? '#4aa3ff' : '#cbd5e1', textDecoration: 'none' })}>
              {name}{name === 'Approvals' && pending > 0 ? <span style={{ background: '#c0392b', color: '#fff', borderRadius: 8, padding: '0 6px', marginLeft: 6, fontSize: 12 }}>{pending}</span> : null}
            </NavLink>
          </div>
        ))}
        {PLACEHOLDERS.map((name) => (
          <div key={name} style={{ margin: '8px 0', color: '#5b6675' }} title="Arrives in a later phase">{name}</div>
        ))}
        <button onClick={() => logout().then(() => nav('/login'))} style={{ marginTop: 24 }}>Log out</button>
      </nav>
      <main style={{ flex: 1, display: 'flex', flexDirection: 'column' }}>
        <div
          data-testid="mode-banner"
          style={{
            padding: '6px 16px', fontWeight: 700,
            background: live ? '#c0392b' : '#2c6fbb', color: '#fff',
            display: 'flex', justifyContent: 'space-between',
          }}
        >
          <span>{mode === 'AUTO' ? '● LIVE · AUTO' : mode === 'SIM' ? '● SIM — historical replay' : `${live ? '● LIVE' : '● PAPER'} — ${mode}`}</span>
          <span style={{ display: 'flex', gap: 16, fontWeight: 400 }}>
            {pending > 0 && <NavLink to="/approvals" data-testid="approvals-badge" style={{ color: '#fff', fontWeight: 700 }}>⚑ {pending} awaiting approval</NavLink>}
            <Dot ok={health?.status === 'UP'} label="Server" />
            <Dot ok={health?.broker.status === 'HEALTHY'} label="Broker" />
            <Dot ok={health?.marketData.status !== 'DOWN'} label="Market Data" />
          </span>
        </div>
        <div style={{ padding: 24 }}>{children}</div>
        <div data-testid="toasts" style={{ position: 'fixed', right: 16, bottom: 16, display: 'flex', flexDirection: 'column', gap: 8, zIndex: 10 }}>
          {toasts.map((t) => (
            <div key={t.id} style={{ background: '#fff', borderLeft: `4px solid ${SEVERITY_COLOR[t.severity]}`, padding: '8px 12px', boxShadow: '0 2px 6px #0003', maxWidth: 360 }}>
              <b>{t.title}</b>{t.body && <div style={{ fontSize: 13, whiteSpace: 'pre-wrap' }}>{t.body}</div>}
            </div>
          ))}
        </div>
      </main>
    </div>
  );
}
