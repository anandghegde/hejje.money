import { useEffect, useState } from 'react';

/** Token colours for `lightweight-charts` (and inline SVG), which cannot read CSS custom properties themselves. */
export interface ChartTheme {
  background: string; text: string; muted: string; grid: string; border: string;
  accent: string; profit: string; loss: string; warning: string; series3: string;
}

const TOKENS: Record<keyof ChartTheme, string> = {
  background: '--surface', text: '--text-secondary', muted: '--text-muted', grid: '--surface-2', border: '--border',
  accent: '--accent', profit: '--profit', loss: '--loss', warning: '--warning', series3: '--mode-sim',
};

export function readChartTheme(root: HTMLElement = document.documentElement): ChartTheme {
  const style = getComputedStyle(root);
  const out = {} as ChartTheme;
  for (const [key, token] of Object.entries(TOKENS) as [keyof ChartTheme, string][]) out[key] = style.getPropertyValue(token).trim();
  return out;
}

/** The current chart colours, re-read when the theme changes (the Settings override or the OS setting), so charts redraw. */
export function useChartTheme(): ChartTheme {
  const [theme, setTheme] = useState(readChartTheme);
  useEffect(() => {
    const update = () => setTheme(readChartTheme());
    const observer = new MutationObserver(update);
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });
    const mql = typeof window.matchMedia === 'function' ? window.matchMedia('(prefers-color-scheme: dark)') : null;
    mql?.addEventListener('change', update);
    return () => { observer.disconnect(); mql?.removeEventListener('change', update); };
  }, []);
  return theme;
}

/** Base `createChart` options on the theme. */
export function chartOptions(t: ChartTheme) {
  return {
    layout: { background: { color: t.background }, textColor: t.text },
    grid: { vertLines: { color: t.grid }, horzLines: { color: t.grid } },
    rightPriceScale: { borderColor: t.border },
    timeScale: { borderColor: t.border },
  };
}
