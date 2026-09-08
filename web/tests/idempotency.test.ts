import { describe, expect, it } from 'vitest';
import { newIdempotencyKey } from '../src/lib/idempotency';

describe('newIdempotencyKey', () => {
  it('generates unique UUID-shaped keys', () => {
    const a = newIdempotencyKey();
    const b = newIdempotencyKey();
    expect(a).not.toBe(b);
    expect(a).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i);
  });
});
