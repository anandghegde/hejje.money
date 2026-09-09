import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { request } from '../api/client';
import { NewsBias } from '../api/types';

/** PRD 17.1 news indicator with the retained source items behind it. */
export function NewsBiasPanel({ instrumentId }: { instrumentId?: string }) {
  const [open, setOpen] = useState(false);
  const { data } = useQuery({ queryKey: ['news-bias', instrumentId], enabled: !!instrumentId,
    queryFn: () => request<NewsBias>(`/context/news-bias?instrumentId=${instrumentId}`), refetchInterval: 300_000 });
  if (!instrumentId || !data) return null;
  const color = data.score > 0.2 ? '#1a9f57' : data.score < -0.2 ? '#c0392b' : '#616161';
  return (
    <div data-testid="news-bias" style={{ marginTop: 8 }}>
      <div>
        News bias <b style={{ color }}>{data.label}{data.available ? ` ${data.score > 0 ? '+' : ''}${data.score.toFixed(2)}` : ''}</b>
        {data.available && data.items > 0 && (
          <button type="button" style={{ marginLeft: 8 }} onClick={() => setOpen(!open)}>{open ? 'Hide' : `Why (${data.items})`}</button>
        )}
      </div>
      {!data.available && <div style={{ fontSize: 12, color: '#888' }}>{data.evidence[0]}</div>}
      {open && (
        <div>
          <ul>{data.evidence.map((e) => <li key={e}>{e}</li>)}</ul>
          <ul>
            {data.sources.map((s) => (
              <li key={s.itemId}><a href={s.url} target="_blank" rel="noreferrer">{s.title}</a> — {s.source}, {new Date(s.publishedAt).toLocaleString()}</li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}
