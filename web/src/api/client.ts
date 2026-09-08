import { newIdempotencyKey } from '../lib/idempotency';

const API_URL = import.meta.env.VITE_API_URL ?? 'http://localhost:8080/api/v1';

let accessToken: string | null = null;
let onUnauthorized: (() => void) | null = null;

export function setAccessToken(token: string | null) { accessToken = token; }
export function getAccessToken(): string | null { return accessToken; }
export function setUnauthorizedHandler(handler: () => void) { onUnauthorized = handler; }
export function apiBaseUrl(): string { return API_URL; }

interface RequestOptions {
  method?: string;
  body?: unknown;
  idempotent?: boolean;
  retryOn401?: boolean;
}

export class ApiError extends Error {
  constructor(public status: number, public problem: any) {
    super(problem?.detail ?? problem?.title ?? `HTTP ${status}`);
  }
}

async function refresh(): Promise<boolean> {
  const res = await fetch(`${API_URL}/auth/refresh`, { method: 'POST', credentials: 'include' });
  if (!res.ok) return false;
  const body = await res.json();
  accessToken = body.accessToken;
  return true;
}

export async function request<T>(path: string, opts: RequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  if (accessToken) headers['Authorization'] = `Bearer ${accessToken}`;
  if (opts.idempotent) headers['Idempotency-Key'] = newIdempotencyKey();
  const res = await fetch(`${API_URL}${path}`, {
    method: opts.method ?? 'GET',
    headers,
    credentials: 'include',
    body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
  });
  if (res.status === 401 && opts.retryOn401 !== false) {
    if (await refresh()) return request<T>(path, { ...opts, retryOn401: false });
    onUnauthorized?.();
    throw new ApiError(401, { detail: 'Unauthorized' });
  }
  if (!res.ok) {
    let problem: any = {};
    try { problem = await res.json(); } catch { /* empty */ }
    throw new ApiError(res.status, problem);
  }
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  return text ? (JSON.parse(text) as T) : (undefined as T);
}

export async function login(username: string, password: string): Promise<void> {
  const res = await fetch(`${API_URL}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Client-Source': 'web' },
    credentials: 'include',
    body: JSON.stringify({ username, password }),
  });
  if (!res.ok) throw new ApiError(res.status, await res.json().catch(() => ({})));
  const body = await res.json();
  accessToken = body.accessToken;
}

export async function logout(): Promise<void> {
  await fetch(`${API_URL}/auth/logout`, { method: 'POST', credentials: 'include' });
  accessToken = null;
}
