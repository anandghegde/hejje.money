/** API keys (client credentials) created from the web: which access a key gets and the request that creates it. */

export interface ApiKeySummary {
  id: string; name: string; keyPrefix: string; scopes: string[];
  createdAt: string; expiresAt?: string | null; revokedAt?: string | null; lastUsedAt?: string | null;
}
export interface ApiKeyCreated { id: string; name: string; key: string; scopes: string[]; expiresAt?: string | null }

/**
 * The terminal is a human's tool, so it may trade, cancel, close and change risk; agent presets never can (server
 * AgentPresets). No key made here gets admin, sim:run or bot:decide.
 */
export const TERMINAL_SCOPES = ['market:read', 'strategies:read', 'strategies:write', 'orders:prepare', 'orders:execute',
  'orders:cancel', 'positions:close', 'risk:read', 'risk:write'];

export const ACCESS = [
  { id: 'terminal', label: 'Terminal (TUI): trade, cancel, close, risk' },
  { id: 'research', label: 'Agent: research (read only)' },
  { id: 'execution', label: 'Agent: execution (prepares orders, you approve)' },
  { id: 'bot', label: 'Bot (decisions, never orders)' },
] as const;
export type Access = (typeof ACCESS)[number]['id'];

export function createKeyBody(name: string, access: Access, days: number | null, now: Date = new Date()) {
  const expiresAt = days ? new Date(now.getTime() + days * 86_400_000).toISOString() : null;
  return access === 'terminal'
    ? { name: name.trim(), scopes: TERMINAL_SCOPES, expiresAt }
    : { name: name.trim(), preset: access, expiresAt };
}

export function keyState(k: ApiKeySummary, now: Date = new Date()): 'revoked' | 'expired' | 'active' {
  if (k.revokedAt) return 'revoked';
  if (k.expiresAt && new Date(k.expiresAt) <= now) return 'expired';
  return 'active';
}
