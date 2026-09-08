import { useEffect, useRef, useState } from 'react';
import { getAccessToken } from '../api/client';

const WS_URL = import.meta.env.VITE_WS_URL ?? 'ws://localhost:8080';

export interface HejjeEvent { type: string; [k: string]: unknown; }

/** Subscribes to /ws/events with reconnect; returns the latest event and a bumping counter. */
export function useEventsSocket(onEvent?: (e: HejjeEvent) => void) {
  const [connected, setConnected] = useState(false);
  const handler = useRef(onEvent);
  handler.current = onEvent;

  useEffect(() => {
    let ws: WebSocket | null = null;
    let closed = false;
    let retry = 1000;
    const connect = () => {
      const token = getAccessToken();
      if (!token) { setTimeout(connect, 1000); return; }
      ws = new WebSocket(`${WS_URL}/ws/events?token=${encodeURIComponent(token)}`);
      ws.onopen = () => { setConnected(true); retry = 1000; };
      ws.onmessage = (m) => { try { handler.current?.(JSON.parse(m.data)); } catch { /* ignore */ } };
      ws.onclose = () => {
        setConnected(false);
        if (!closed) { setTimeout(connect, retry); retry = Math.min(retry * 2, 30000); }
      };
      ws.onerror = () => ws?.close();
    };
    connect();
    return () => { closed = true; ws?.close(); };
  }, []);

  return { connected };
}
