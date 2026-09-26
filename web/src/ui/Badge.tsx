import { HTMLAttributes, ReactNode } from 'react';

export type BadgeTone = 'neutral' | 'profit' | 'loss' | 'warning' | 'info' | 'live' | 'paper' | 'sim';

interface BadgeProps extends HTMLAttributes<HTMLSpanElement> {
  tone?: BadgeTone;
  children: ReactNode;
}

export function Badge({ tone = 'neutral', children, className, ...rest }: BadgeProps) {
  return <span className={`badge badge-${tone}${className ? ` ${className}` : ''}`} {...rest}>{children}</span>;
}
