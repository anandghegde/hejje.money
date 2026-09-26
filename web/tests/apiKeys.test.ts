import { describe, expect, it } from 'vitest';
import { createKeyBody, keyState, TERMINAL_SCOPES } from '../src/lib/apiKeys';

describe('API keys', () => {
  const now = new Date('2026-09-24T05:00:00Z');

  it('a terminal key lists its scopes and never gets admin', () => {
    const body = createKeyBody(' macbook-tui ', 'terminal', 90, null, now);
    expect(body).toEqual({ name: 'macbook-tui', scopes: TERMINAL_SCOPES, expiresAt: '2026-12-23T05:00:00.000Z' });
    expect(TERMINAL_SCOPES).toContain('orders:execute');
    expect(TERMINAL_SCOPES).not.toContain('admin');
  });

  it('agent and bot keys use the server presets; no days means no expiry', () => {
    expect(createKeyBody('agent', 'execution', null, null, now)).toEqual({ name: 'agent', preset: 'execution', expiresAt: null });
  });

  it('a bot key is bound to the chosen bot; a bot id is ignored for every other access', () => {
    expect(createKeyBody('orb', 'bot', 30, 'b-1', now)).toEqual({ name: 'orb', preset: 'bot', expiresAt: '2026-10-24T05:00:00.000Z', botId: 'b-1' });
    expect(createKeyBody('orb', 'bot', null, null, now)).toEqual({ name: 'orb', preset: 'bot', expiresAt: null });
    expect(createKeyBody('agent', 'research', null, 'b-1', now)).toEqual({ name: 'agent', preset: 'research', expiresAt: null });
  });

  it('state: revoked beats expired beats active', () => {
    const base = { id: '1', name: 'k', keyPrefix: 'hejje_ab', scopes: [], createdAt: '2026-09-01T00:00:00Z' };
    expect(keyState({ ...base }, now)).toBe('active');
    expect(keyState({ ...base, expiresAt: '2026-09-20T00:00:00Z' }, now)).toBe('expired');
    expect(keyState({ ...base, expiresAt: '2026-09-20T00:00:00Z', revokedAt: '2026-09-10T00:00:00Z' }, now)).toBe('revoked');
  });
});
