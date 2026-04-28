import { describe, expect, it } from 'vitest';
import { toNum } from '../utils';

describe('toNum', () => {
  it('returns finite numbers unchanged', () => {
    expect(toNum(0)).toBe(0);
    expect(toNum(1)).toBe(1);
    expect(toNum(-12.5)).toBe(-12.5);
    expect(toNum(123.456)).toBe(123.456);
  });

  it('parses BigDecimal-style strings', () => {
    // The reason this helper exists — backend serializes BigDecimal as a JSON string.
    expect(toNum('252.18')).toBeCloseTo(252.18);
    expect(toNum('0')).toBe(0);
    expect(toNum('-1.5')).toBe(-1.5);
  });

  it('returns 0 for null / undefined', () => {
    expect(toNum(null)).toBe(0);
    expect(toNum(undefined)).toBe(0);
  });

  it('returns 0 for non-finite numbers (NaN / Infinity) — never propagates NaN', () => {
    expect(toNum(NaN)).toBe(0);
    expect(toNum(Infinity)).toBe(0);
    expect(toNum(-Infinity)).toBe(0);
  });

  it('returns 0 for unparseable strings', () => {
    expect(toNum('abc')).toBe(0);
    expect(toNum('')).toBe(0);
    expect(toNum('   ')).toBe(0);
  });

  it('returns 0 for objects / arrays / booleans', () => {
    expect(toNum({})).toBe(0);
    expect(toNum([])).toBe(0);
    expect(toNum(true)).toBe(0);
    expect(toNum(false)).toBe(0);
  });
});
