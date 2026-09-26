import { applyTheme, setTheme, storedTheme } from '../src/lib/theme';

/** In-memory storage: Node's own (empty) `localStorage` global shadows jsdom's in Vitest. */
function memoryStorage(): Storage {
  const m = new Map<string, string>();
  return {
    get length() { return m.size; },
    clear: () => m.clear(),
    getItem: (k: string) => m.get(k) ?? null,
    key: (i: number) => [...m.keys()][i] ?? null,
    removeItem: (k: string) => { m.delete(k); },
    setItem: (k: string, v: string) => { m.set(k, v); },
  };
}

describe('theme', () => {
  beforeEach(() => vi.stubGlobal('localStorage', memoryStorage()));
  afterEach(() => {
    vi.unstubAllGlobals();
    document.documentElement.removeAttribute('data-theme');
  });

  it('defaults to following the OS', () => {
    expect(storedTheme()).toBe('system');
    applyTheme(storedTheme());
    expect(document.documentElement.hasAttribute('data-theme')).toBe(false);
  });

  it('applies and remembers a manual choice, and system clears it', () => {
    setTheme('dark');
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
    expect(storedTheme()).toBe('dark');
    setTheme('light');
    expect(document.documentElement.getAttribute('data-theme')).toBe('light');
    setTheme('system');
    expect(document.documentElement.hasAttribute('data-theme')).toBe(false);
    expect(storedTheme()).toBe('system');
  });

  it('ignores unknown stored values and blocked storage', () => {
    localStorage.setItem('hejje.theme', 'neon');
    expect(storedTheme()).toBe('system');
    const blocked = () => { throw new Error('blocked'); };
    vi.stubGlobal('localStorage', { getItem: blocked, setItem: blocked, removeItem: blocked });
    expect(storedTheme()).toBe('system');
    setTheme('dark');
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
  });
});
