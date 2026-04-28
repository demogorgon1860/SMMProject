import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useDebouncedValue } from '../useDebouncedValue';

describe('useDebouncedValue', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('returns the initial value synchronously on mount', () => {
    const { result } = renderHook(() => useDebouncedValue('hi', 250));
    expect(result.current).toBe('hi');
  });

  it('delays propagation of value changes by `delayMs`', () => {
    const { result, rerender } = renderHook(({ v }) => useDebouncedValue(v, 250), {
      initialProps: { v: 'a' },
    });

    rerender({ v: 'b' });
    expect(result.current).toBe('a');

    act(() => {
      vi.advanceTimersByTime(249);
    });
    expect(result.current).toBe('a');

    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(result.current).toBe('b');
  });

  it('only emits the final value when the input churns within the window', () => {
    const { result, rerender } = renderHook(({ v }) => useDebouncedValue(v, 250), {
      initialProps: { v: '' },
    });

    rerender({ v: 'a' });
    act(() => {
      vi.advanceTimersByTime(100);
    });
    rerender({ v: 'ab' });
    act(() => {
      vi.advanceTimersByTime(100);
    });
    rerender({ v: 'abc' });
    act(() => {
      vi.advanceTimersByTime(249);
    });
    // 449 ms total elapsed; the latest pending timer started at the third rerender, 100 ms after
    // the second. We've advanced 249 ms past that → still 1 ms short.
    expect(result.current).toBe('');

    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(result.current).toBe('abc');
  });
});
