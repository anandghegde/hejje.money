import { describe, expect, it } from 'vitest';
import { canFailover, executorLine, ExecutorStatus } from '../src/lib/executor';

const base: ExecutorStatus = { instance: 'vm-a', role: 'ACTIVE', required: true, held: true, epoch: 3, activeInstance: 'vm-a', activeEpoch: 3 };

describe('executor status', () => {
  it('describes each role', () => {
    expect(executorLine(base)).toBe('ACTIVE vm-a (epoch 3)');
    expect(executorLine({ ...base, role: 'STANDBY', held: false, activeInstance: 'vm-b', activeEpoch: 4 })).toBe('STANDBY vm-a — active: vm-b (epoch 4)');
    expect(executorLine({ ...base, role: 'STANDBY', held: false, activeInstance: null })).toContain('none (lease expired)');
    expect(executorLine({ ...base, role: 'NOT_REQUIRED', required: false })).toBe('single instance vm-a (lease not required)');
  });
  it('offers failover only on the active instance', () => {
    expect(canFailover(base)).toBe(true);
    expect(canFailover({ ...base, role: 'STANDBY' })).toBe(false);
    expect(canFailover({ ...base, role: 'NOT_REQUIRED', required: false })).toBe(false);
    expect(canFailover(undefined)).toBe(false);
  });
});
