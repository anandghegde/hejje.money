import { ReactNode, useEffect, useState } from 'react';
import { NavLink, useLocation, useNavigate } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { request } from '../api/client';
import { Approval, Health, KillSwitch, NotificationSeverity } from '../api/types';
import { useEventsSocket } from '../ws/useEventsSocket';
import { useAuth } from '../auth/AuthContext';
import { PushedNotification, pushedNotification } from '../lib/notifications';
import { ToastStack, ToastTone } from '../ui';
import '../styles/shell.css';

/** Side navigation, grouped. An item without a route is a placeholder for a later phase. */
const NAV: { group: string; items: { name: string; to?: string }[] }[] = [
  { group: 'Trade', items: [
    { name: 'Today', to: '/today' }, { name: 'Approvals', to: '/approvals' }, { name: 'Orders', to: '/orders' },
    { name: 'Positions', to: '/positions' }, { name: 'Trades', to: '/trades' }, { name: 'Risk', to: '/risk' },
  ] },
  { group: 'Research', items: [
    { name: 'Screener', to: '/screener' }, { name: 'Pulse' }, { name: 'Strategies', to: '/strategies' },
    { name: 'Lab', to: '/lab' }, { name: 'Reviews', to: '/reviews' }, { name: 'Analytics', to: '/analytics' },
  ] },
  { group: 'Automation', items: [{ name: 'Hejje AI', to: '/agent' }] },
  { group: 'System', items: [{ name: 'Broker', to: '/broker' }, { name: 'Server', to: '/system' }, { name: 'Settings', to: '/settings' }] },
];

/** The phone tab bar: the pages used away from the desk. */
const QUICK = [
  { name: 'Today', to: '/today' }, { name: 'Positions', to: '/positions' }, { name: 'Approvals', to: '/approvals' }, { name: 'Risk', to: '/risk' },
];

const SEVERITY_TONE: Record<NotificationSeverity, ToastTone> = { INFO: 'info', WARNING: 'warning', CRITICAL: 'loss' };

function Dot({ ok, label }: { ok: boolean; label: string }) {
  return (
    <span className={ok ? 'health health-ok' : 'health health-down'} title={`${label}: ${ok ? 'OK' : 'down'}`}>
      <span aria-hidden="true">{ok ? '●' : '✕'}</span> {label}<span className="sr-only">{ok ? ' OK' : ' down'}</span>
    </span>
  );
}

function Count({ n }: { n: number }) {
  return n > 0 ? <span className="nav-count">{n}</span> : null;
}

export function Layout({ children }: { children: ReactNode }) {
  const nav = useNavigate();
  const location = useLocation();
  const { logout } = useAuth();
  const { data: health } = useQuery({
    queryKey: ['health'],
    queryFn: () => request<Health>('/server/health'),
    refetchInterval: 5000,
  });
  const { data: ks } = useQuery({
    queryKey: ['kill-switch'],
    queryFn: () => request<KillSwitch>('/risk/kill-switch'),
    refetchInterval: 10000,
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

  // phone: the full navigation opens as a sheet from the tab bar's Menu button
  const [menuOpen, setMenuOpen] = useState(false);
  useEffect(() => setMenuOpen(false), [location.pathname]);
  useEffect(() => {
    if (!menuOpen) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setMenuOpen(false); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [menuOpen]);

  const pending = pendingApprovals?.length ?? 0;
  const mode = health?.mode ?? 'PAPER';
  const live = mode === 'CONFIRM' || mode === 'AUTO';
  const modeClass = live ? 'mode-live' : mode === 'SIM' ? 'mode-sim' : 'mode-paper';

  return (
    <div className={menuOpen ? 'shell shell-menu-open' : 'shell'}>
      <nav className="shell-nav" id="shell-nav" aria-label="Main">
        <div className="shell-brand">Hejje</div>
        {NAV.map((g) => (
          <div key={g.group} className="nav-group">
            <div className="nav-group-title">{g.group}</div>
            {g.items.map((item) => item.to ? (
              <NavLink key={item.name} to={item.to} className="nav-link">
                {item.name}{item.name === 'Approvals' && <Count n={pending} />}
              </NavLink>
            ) : (
              <div key={item.name} className="nav-link nav-placeholder" title="Arrives in a later phase">{item.name}</div>
            ))}
          </div>
        ))}
        <button className="nav-logout" onClick={() => logout().then(() => nav('/login'))}>Log out</button>
      </nav>
      <div className="shell-main">
        <div data-testid="mode-banner" className={`mode-banner ${modeClass}`}>
          <span className="mode-text">{mode === 'AUTO' ? '● LIVE · AUTO' : mode === 'SIM' ? '● SIM — historical replay' : `${live ? '● LIVE' : '● PAPER'} — ${mode}`}</span>
          <span className="banner-items">
            <NavLink to="/risk" data-testid="kill-state" className={ks?.stopNewOrders ? 'kill-state kill-stopping' : 'kill-state'}>
              {ks === undefined ? 'Kill switch …' : ks.stopNewOrders ? '■ Kill switch: STOPPING new orders' : 'Kill switch armed'}
            </NavLink>
            {pending > 0 && <NavLink to="/approvals" data-testid="approvals-badge" className="banner-approvals">⚑ {pending} awaiting approval</NavLink>}
            <Dot ok={health?.status === 'UP'} label="Server" />
            <Dot ok={health?.broker.status === 'HEALTHY'} label="Broker" />
            <Dot ok={health?.marketData.status !== 'DOWN'} label="Market Data" />
          </span>
        </div>
        <main className="shell-content">{children}</main>
      </div>
      <nav className="shell-tabbar" aria-label="Quick links">
        {QUICK.map((q) => (
          <NavLink key={q.name} to={q.to} className="tabbar-link">
            {q.name}{q.name === 'Approvals' && <Count n={pending} />}
          </NavLink>
        ))}
        <button
          type="button"
          className="tabbar-link tabbar-menu"
          aria-expanded={menuOpen}
          aria-controls="shell-nav"
          onClick={() => setMenuOpen((o) => !o)}
        >
          {menuOpen ? 'Close' : 'Menu'}
        </button>
      </nav>
      <ToastStack data-testid="toasts" toasts={toasts.map((t) => ({ id: t.id, tone: SEVERITY_TONE[t.severity], title: t.title, body: t.body }))} />
    </div>
  );
}
