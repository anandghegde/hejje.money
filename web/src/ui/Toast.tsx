import { ReactNode } from 'react';

export type ToastTone = 'info' | 'warning' | 'loss' | 'profit';

export interface ToastItem { id: string; tone: ToastTone; title: ReactNode; body?: ReactNode; }

export function Toast({ tone, title, body }: Omit<ToastItem, 'id'>) {
  return (
    <div className={`toast toast-${tone}`}>
      <b>{title}</b>
      {body && <div className="toast-body">{body}</div>}
    </div>
  );
}

/** The stack of toasts in the bottom-right corner, announced politely to screen readers. */
export function ToastStack({ toasts, 'data-testid': testId }: { toasts: ToastItem[]; 'data-testid'?: string }) {
  return (
    <div className="toast-stack" data-testid={testId} aria-live="polite">
      {toasts.map(({ id, ...t }) => <Toast key={id} {...t} />)}
    </div>
  );
}
