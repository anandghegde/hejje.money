import { useEffect, useRef, useState } from 'react';
import { getAccessToken } from '../api/client';

const WS_URL = import.meta.env.VITE_WS_URL ?? 'ws://localhost:8080';

export interface MarketMessage { type: 'tick' | 'candle'; instrumentId?: string; lastPrice?: number; [k: string]: unknown; }

/** Subscribes to /ws/market for the given instrument ids; returns last price per instrument. */
export function useMarketSocket(instrumentIds: string[]) {
  const [prices, setPrices] = useState<Record<string, number>>({});
  const idsRef = useRef<string[]>(instrumentIds);
  idsRef.current = instrumentIds;

  useEffect(() => {
    let ws: WebSocket | null = null;
    let closed = false;
    const connect = () => {
      const token = getAccessToken();
      if (!token) { setTimeout(connect, 1000); return; }
      ws = new WebSocket(`${WS_URL}/ws/market?token=${encodeURIComponent(token)}`);
      ws.onopen = () => { if (idsRef.current.length) ws?.send(JSON.stringify({ subscribe: idsRef.current })); };
      ws.onmessage = (m) => {
        try {
          const msg: MarketMessage = JSON.parse(m.data);
          if (msg.type === 'tick' && msg.instrumentId && typeof msg.lastPrice === 'number') {
            setPrices((p) => ({ ...p, [msg.instrumentId as string]: msg.lastPrice as number }));
          }
        } catch { /* ignore */ }
      };
      ws.onclose = () => { if (!closed) setTimeout(connect, 2000); };
      ws.onerror = () => ws?.close();
    };
    connect();
    return () => { closed = true; ws?.close(); };
  }, []);

  return prices;
}
