/** Colour theme: follow the OS (`system`) or force light or dark. Stored per browser; applied as `data-theme` on <html>. */
export type ThemeChoice = 'system' | 'light' | 'dark';

export const THEME_CHOICES: ThemeChoice[] = ['system', 'light', 'dark'];
const KEY = 'hejje.theme';

export function storedTheme(): ThemeChoice {
  try {
    const v = localStorage.getItem(KEY);
    return v === 'light' || v === 'dark' ? v : 'system';
  } catch {
    return 'system'; // storage blocked (private window, site data off)
  }
}

export function applyTheme(choice: ThemeChoice, root: HTMLElement = document.documentElement): void {
  if (choice === 'system') root.removeAttribute('data-theme');
  else root.setAttribute('data-theme', choice);
}

/** Applies the theme at once (tokens are CSS custom properties, so no reload) and remembers it. */
export function setTheme(choice: ThemeChoice): void {
  try {
    if (choice === 'system') localStorage.removeItem(KEY);
    else localStorage.setItem(KEY, choice);
  } catch {
    // not remembered, still applied for this page
  }
  applyTheme(choice);
}
