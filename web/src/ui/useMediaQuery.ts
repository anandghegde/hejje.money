import { useEffect, useState } from 'react';

/** The phone breakpoint: below it, wide tables become cards. */
export const PHONE = '(max-width: 639px)';

/** Whether a media query matches, updated live (false where matchMedia is missing, e.g. jsdom). */
export function useMediaQuery(query: string): boolean {
  const get = () => typeof window !== 'undefined' && typeof window.matchMedia === 'function' && window.matchMedia(query).matches;
  const [matches, setMatches] = useState(get);
  useEffect(() => {
    if (typeof window.matchMedia !== 'function') return;
    const mql = window.matchMedia(query);
    const onChange = () => setMatches(mql.matches);
    onChange();
    mql.addEventListener('change', onChange);
    return () => mql.removeEventListener('change', onChange);
  }, [query]);
  return matches;
}
