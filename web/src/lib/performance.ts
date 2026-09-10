/** First day of the month to today, as ISO dates (local calendar). */
export function monthToDate(today: Date): { from: string; to: string } {
  const pad = (n: number) => String(n).padStart(2, '0');
  const y = today.getFullYear();
  const m = pad(today.getMonth() + 1);
  return { from: `${y}-${m}-01`, to: `${y}-${m}-${pad(today.getDate())}` };
}

export function rupees(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  const sign = value < 0 ? '−' : '';
  return `${sign}₹${Math.abs(value).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

export function toggle(list: string[], value: string): string[] {
  return list.includes(value) ? list.filter((v) => v !== value) : [...list, value];
}
