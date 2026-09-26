import { useEffect, useRef } from 'react';
import { createChart } from 'lightweight-charts';
import { Money } from '../api/types';
import { chartOptions, useChartTheme } from '../ui';

/** Equity curve of a backtest (one point per closed trade); redrawn in the theme's colours when the theme changes. */
export function EquityChart({ points }: { points: { time: string; value: Money }[] }) {
  const ref = useRef<HTMLDivElement>(null);
  const theme = useChartTheme();
  useEffect(() => {
    if (!ref.current || points.length === 0) return;
    const chart = createChart(ref.current, { height: 220, width: ref.current.clientWidth, ...chartOptions(theme) });
    const series = chart.addLineSeries({ color: theme.accent });
    const seen = new Set<number>();
    const data = points
      .map((p) => ({ time: Math.floor(new Date(p.time).getTime() / 1000), value: p.value.paise / 100 }))
      .filter((d) => (seen.has(d.time) ? false : (seen.add(d.time), true)))
      .sort((a, b) => a.time - b.time);
    series.setData(data as any);
    chart.timeScale().fitContent();
    return () => chart.remove();
  }, [points, theme]);
  return <div ref={ref} data-testid="equity-chart" className="chart section" />;
}
