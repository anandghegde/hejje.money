import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { request } from '../api/client';
import { NewsBias } from '../api/types';
import { Badge, Button } from '../ui';

/** PRD 17.1 news indicator with the retained source items behind it. */
export function NewsBiasPanel({ instrumentId }: { instrumentId?: string }) {
  const [open, setOpen] = useState(false);
  const { data } = useQuery({ queryKey: ['news-bias', instrumentId], enabled: !!instrumentId,
    queryFn: () => request<NewsBias>(`/context/news-bias?instrumentId=${instrumentId}`), refetchInterval: 300_000 });
  if (!instrumentId || !data) return null;
  const tone = data.score > 0.2 ? 'profit' : data.score < -0.2 ? 'loss' : 'neutral';
  return (
    <div data-testid="news-bias" className="stack-sm">
      <div className="cluster">
        News bias <Badge tone={tone}>{data.label}{data.available ? ` ${data.score > 0 ? '+' : ''}${data.score.toFixed(2)}` : ''}</Badge>
        {data.available && data.items > 0 && (
          <Button size="sm" onClick={() => setOpen(!open)} aria-expanded={open}>{open ? 'Hide' : `Why (${data.items})`}</Button>
        )}
      </div>
      {!data.available && <div className="muted text-sm">{data.evidence[0]}</div>}
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
