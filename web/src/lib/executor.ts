export interface ExecutorStatus {
  instance: string; role: 'ACTIVE' | 'STANDBY' | 'NOT_REQUIRED'; required: boolean; held: boolean; epoch: number;
  activeInstance?: string | null; activeEpoch?: number | null; expiresAt?: string | null; holdUntil?: string | null;
}
export interface BrokerAccount { id: string; broker: string; accountId: string; label?: string | null; active: boolean; activatedAt?: string | null; activatedBy?: string | null; }
export interface BrokerAccountsView { adapter: string; active: BrokerAccount | null; accounts: BrokerAccount[]; }

/** "ACTIVE (epoch 3)" / "STANDBY — active: vm-b (epoch 4)" / "single instance (lease not required)". */
export function executorLine(s: ExecutorStatus): string {
  if (s.role === 'NOT_REQUIRED') return `single instance ${s.instance} (lease not required)`;
  if (s.role === 'ACTIVE') return `ACTIVE ${s.instance} (epoch ${s.epoch})`;
  const active = s.activeInstance ? `${s.activeInstance} (epoch ${s.activeEpoch})` : 'none (lease expired)';
  return `STANDBY ${s.instance} — active: ${active}${s.holdUntil ? `; holding back until ${new Date(s.holdUntil).toLocaleTimeString()}` : ''}`;
}

/** Failover is offered only on the instance that holds the lease. */
export function canFailover(s?: ExecutorStatus): boolean {
  return !!s && s.required && s.role === 'ACTIVE';
}
