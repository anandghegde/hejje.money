import { describe, expect, it, vi, beforeEach } from 'vitest';
import { request, setAccessToken, ApiError, tryRefresh, getAccessToken } from '../src/api/client';

describe('api client', () => {
  beforeEach(() => setAccessToken('tok'));

  it('sends bearer token and idempotency key on transactional calls', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ id: '1' }), { status: 201, headers: { 'Content-Type': 'application/json' } }));
    vi.stubGlobal('fetch', fetchMock);
    await request('/orders/intents', { method: 'POST', idempotent: true, body: { a: 1 } });
    const [, init] = fetchMock.mock.calls[0];
    expect(init.headers['Authorization']).toBe('Bearer tok');
    expect(init.headers['Idempotency-Key']).toMatch(/[0-9a-f-]{36}/);
    vi.unstubAllGlobals();
  });

  it('throws ApiError with the problem body', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ detail: 'nope', reasons: ['x'] }), { status: 422, headers: { 'Content-Type': 'application/json' } }));
    vi.stubGlobal('fetch', fetchMock);
    await expect(request('/orders/intents', { method: 'POST', idempotent: true, retryOn401: false })).rejects.toBeInstanceOf(ApiError);
    vi.unstubAllGlobals();
  });

  it('shares one refresh between concurrent callers (the server rotates the refresh token)', async () => {
    setAccessToken(null);
    const fetchMock = vi.fn().mockImplementation(async () => {
      if (fetchMock.mock.calls.length > 1) return new Response('{}', { status: 401 }); // a second call with the rotated cookie
      return new Response(JSON.stringify({ accessToken: 'fresh' }), { status: 200, headers: { 'Content-Type': 'application/json' } });
    });
    vi.stubGlobal('fetch', fetchMock);
    expect(await Promise.all([tryRefresh(), tryRefresh()])).toEqual([true, true]);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(getAccessToken()).toBe('fresh');
    expect(await tryRefresh()).toBe(false); // the next refresh is a new call
    expect(fetchMock).toHaveBeenCalledTimes(2);
    vi.unstubAllGlobals();
  });
});
