import { describe, expect, it } from 'vitest';
import { createKeyBody, keyState, TERMINAL_SCOPES } from '../src/lib/apiKeys';

describe('API keys', () => {
  const now = new Date('2026-09-24T05:00:00Z');

  it('a terminal key lists its scopes and never gets admin', () => {
    const body = createKeyBody(' macbook-tui ', 'terminal', 90, now);
    expect(body).toEqual({ name: 'macbook-tui', scopes: TERMINAL_SCOPES, expiresAt: '2026-12-23T05:00:00.000Z' });
    expect(TERMINAL_SCOPES).toContain('orders:execute');
    expect(TERMINAL_SCOPES).not.toContain('admin');
  });

  it('agent and bot keys use the server presets; no days means no expiry', () => {
    expect(createKeyBody('agent', 'execution', null, now)).toEqual({ name: 'agent', preset: 'execution', expiresAt: null });
  });

  it('state: revoked beats expired beats active', () => {
    const base = { id: '1', name: 'k', keyPrefix: 'hejje_ab', scopes: [], createdAt: '2026-09-01T00:00:00Z' };
    expect(keyState({ ...base }, now)).toBe('active');
    expect(keyState({ ...base, expiresAt: '2026-09-20T00:00:00Z' }, now)).toBe('expired');
    expect(keyState({ ...base, expiresAt: '2026-09-20T00:00:00Z', revokedAt: '2026-09-10T00:00:00Z' }, now)).toBe('revoked');
  });
});
