// vite.config.ts lets this file through Vitest's CSS stub, which otherwise empties every CSS import (even ?raw)
import tokensCss from '../src/styles/tokens.css?raw';

/** Custom properties declared in the block that follows `selector` (the first `{…}` without nesting). */
function block(css: string, selector: string): Record<string, string> {
  const start = css.indexOf(selector);
  if (start < 0) throw new Error(`no block ${selector}`);
  const open = css.indexOf('{', start);
  const body = css.slice(open + 1, css.indexOf('}', open));
  const vars: Record<string, string> = {};
  for (const m of body.replace(/\/\*[\s\S]*?\*\//g, '').matchAll(/(--[\w-]+)\s*:\s*([^;]+);/g)) vars[m[1]] = m[2].trim();
  return vars;
}

function luminance(hex: string): number {
  const m = /^#([0-9a-f]{6})$/i.exec(hex);
  if (!m) throw new Error(`not a #rrggbb colour: ${hex}`);
  const [r, g, b] = [0, 2, 4].map((i) => {
    const c = parseInt(m[1].slice(i, i + 2), 16) / 255;
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

function contrast(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

const light = block(tokensCss, ":root,\n[data-theme='light']");
const darkOs = block(tokensCss, ":root:not([data-theme='light'])");
const darkManual = block(tokensCss, "\n[data-theme='dark']");

/** Text tokens and the surfaces they are drawn on; AA for normal text is 4.5:1. */
const PAIRS: [string, string[]][] = [
  ['--text', ['--bg', '--surface', '--surface-2']],
  ['--text-secondary', ['--bg', '--surface', '--surface-2']],
  ['--text-muted', ['--bg', '--surface', '--surface-2']],
  ['--accent', ['--bg', '--surface', '--surface-2']],
  ['--profit', ['--bg', '--surface', '--surface-2', '--profit-soft']],
  ['--loss', ['--bg', '--surface', '--surface-2', '--loss-soft']],
  ['--warning', ['--bg', '--surface', '--surface-2', '--warning-soft']],
  ['--info', ['--bg', '--surface', '--surface-2', '--info-soft']],
  ['--text-nav', ['--surface-nav']],
  ['--text-nav-active', ['--surface-nav']],
  ['--on-accent', ['--accent']],
  ['--on-danger', ['--danger']],
  ['--on-mode', ['--mode-live', '--mode-paper', '--mode-sim']],
];

describe('design tokens', () => {
  it('every colour token has a light and a dark value', () => {
    expect(Object.keys(light).length).toBeGreaterThan(20);
    expect(Object.keys(darkOs).sort()).toEqual(Object.keys(light).sort());
    expect(Object.keys(darkManual).sort()).toEqual(Object.keys(light).sort());
  });

  it('the OS-dark and manual-dark blocks agree', () => {
    expect(darkManual).toEqual(darkOs);
  });

  for (const [name, theme] of [['light', light], ['dark', darkOs]] as const) {
    it(`text tokens pass WCAG AA on their surfaces (${name})`, () => {
      const failures: string[] = [];
      for (const [fg, bgs] of PAIRS) {
        for (const bg of bgs) {
          const ratio = contrast(theme[fg], theme[bg]);
          if (ratio < 4.5) failures.push(`${fg} on ${bg}: ${ratio.toFixed(2)}`);
        }
      }
      expect(failures).toEqual([]);
    });
  }

  it('computes contrast like WCAG', () => {
    expect(contrast('#000000', '#ffffff')).toBeCloseTo(21, 5);
    expect(contrast('#777777', '#ffffff')).toBeCloseTo(4.48, 2);
  });
});
