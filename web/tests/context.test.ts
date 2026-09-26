import { describe, expect, it } from 'vitest';
import { AnalogMatch, AnalogOutcome, Base } from '../src/api/types';
import { bandPoints, buildFilter, chartBase, describeFilter, pathPoints, rateWithCount, sessionLine, signedPct, sma, sortMatches, surveillanceLabel } from '../src/lib/context';

const outcome = (over: Partial<AnalogOutcome>): AnalogOutcome => ({
  forward: '5', count: 40, winRate: 0.675, mean: 1, median: 1.24, p25: -0.8, p75: 2.1, best: 6, worst: -4, maeMedian: -1.1, maeP25: -2.3, mfeMedian: 1.9,
  mfeP75: 3.2, avgPath: [0.2, 0.6, 1.0], p25Path: [-0.4, -0.6, -0.8], p75Path: [0.8, 1.5, 2.1], distinctSymbols: 22, distinctYears: 6, direction: 'BULLISH_STRONG',
  consistency: 'NORMAL', reliability: 'HIGH', risk: 'MODERATE', outlier: false, ...over,
});

describe('daily context helpers', () => {
  it('never shows a rate without its count', () => {
    expect(rateWithCount(0.675, 40)).toBe('27 of 40 (68 %)');
    expect(rateWithCount(0, 0)).toBe('no matches');
  });

  it('labels NSE surveillance flags and shows nothing for NONE or unknown flags', () => {
    expect(surveillanceLabel('ASM_LT_2')).toBe('ASM LT 2');
    expect(surveillanceLabel('ASM_ST_1')).toBe('ASM ST 1');
    expect(surveillanceLabel('GSM_0')).toBe('GSM 0');
    expect(surveillanceLabel('NONE')).toBeNull();
    expect(surveillanceLabel(null)).toBeNull();
    expect(surveillanceLabel('ESM_1')).toBeNull();
  });

  it('formats signed percentages and missing values', () => {
    expect(signedPct(1.236)).toBe('+1.24 %');
    expect(signedPct(-0.5, 1)).toBe('-0.5 %');
    expect(signedPct(undefined)).toBe('—');
  });

  it('builds filters with numbers, text and lists', () => {
    expect(buildFilter('rsRating', 'gte', ' 80 ')).toEqual({ field: 'rsRating', op: 'gte', value: 80 });
    expect(buildFilter('baseStatus', 'in', 'IN_BUY_ZONE, NEAR_PIVOT')).toEqual({ field: 'baseStatus', op: 'in', value: ['IN_BUY_ZONE', 'NEAR_PIVOT'] });
    expect(buildFilter('adGrade', 'eq', 'A+')).toEqual({ field: 'adGrade', op: 'eq', value: 'A+' });
    expect(buildFilter('rsRating', 'gte', '')).toBeNull();
    expect(describeFilter({ field: 'rsRating', op: 'gte', value: 80 })).toBe('rsRating ≥ 80');
  });

  it('draws the open base before a reversal setup and nothing once all are closed', () => {
    const base = (type: string, status: string) => ({ type, status } as Base);
    expect(chartBase([base('MA_REVERSAL', 'NEAR_PIVOT'), base('FLAT_BASE', 'IN_BUY_ZONE')])?.type).toBe('FLAT_BASE');
    expect(chartBase([base('MA_REVERSAL', 'NEAR_PIVOT'), base('CUP', 'STOPPED')])?.type).toBe('MA_REVERSAL');
    expect(chartBase([base('CUP', 'HIT_GOAL')])).toBeUndefined();
  });

  it('computes moving averages aligned with the closes', () => {
    expect(sma([1, 2, 3, 4, 5], 3)).toEqual([undefined, undefined, 2, 3, 4]);
  });

  it('sorts matches client-side, by a column or by a forward return', () => {
    const m = (symbol: string, similarity: number, r5: number): AnalogMatch => ({ symbol, endDate: '2024-01-01', similarity, quality: 4, components: {},
      scores: {}, returns: { '5': r5 }, path: [] });
    const matches = [m('B', 0.5, 2), m('A', 0.3, -1), m('C', 0.4, 5)];
    expect(sortMatches(matches, 'similarity', false).map((x) => x.symbol)).toEqual(['A', 'C', 'B']);
    expect(sortMatches(matches, '5', true).map((x) => x.symbol)).toEqual(['C', 'B', 'A']);
    expect(matches.map((x) => x.symbol)).toEqual(['B', 'A', 'C']); // the input is left alone
  });

  it('scales paths and the 25-75 band into the same box, starting from 0 %', () => {
    expect(pathPoints([0, 1, 2], 100, 50)).toBe('0.0,50.0 50.0,25.0 100.0,0.0');
    const b = bandPoints(outcome({}), 120, 60);
    expect(b.min).toBe(-0.8);
    expect(b.max).toBe(2.1);
    expect(b.average.split(' ')).toHaveLength(4);
    expect(b.band.split(' ')).toHaveLength(8);
  });

  it('summarises session analogs in one line with the count', () => {
    expect(sessionLine('10:15', outcome({ forward: 'close', count: 50, winRate: 0.62, median: 0.31, direction: 'BULLISH' })))
      .toBe('Session analogs 10:15: bullish, higher 31 of 50 (62 %), median +0.31 % to 15:10');
    expect(sessionLine('09:45', outcome({ count: 6, direction: 'INSUFFICIENT' }))).toBe('Session analogs 09:45: 6 matches, too few to read');
    expect(sessionLine('09:45', undefined)).toBe('Session analogs 09:45: no matches');
  });
});
