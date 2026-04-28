import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useServerPagination } from '../useServerPagination';

/**
 * Flush microtasks AND any timers the effect chain may have queued. We can't use waitFor here:
 * waitFor polls on a real-time interval and would deadlock against vi.useFakeTimers().
 */
const flush = async () => {
  await act(async () => {
    await vi.runOnlyPendingTimersAsync();
  });
};

interface Row {
  id: number;
}

const samplePage = (ids: number[], totalElements = ids.length) => ({
  content: ids.map((id) => ({ id })),
  totalElements,
});

describe('useServerPagination', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('fetches the first page on mount and reports loading transitions', async () => {
    const fetcher = vi.fn().mockResolvedValue(samplePage([1, 2, 3], 50));
    const { result } = renderHook(() =>
      useServerPagination<Row>({ fetcher, refreshIntervalMs: 0 }),
    );

    expect(result.current.loading).toBe(true);
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(fetcher).toHaveBeenCalledWith(expect.objectContaining({ page: 0, size: 100 }));

    await flush();

    expect(result.current.items.map((r) => r.id)).toEqual([1, 2, 3]);
    expect(result.current.totalElements).toBe(50);
    expect(result.current.loading).toBe(false);
  });

  it('clears items + totalElements on a foreground fetch failure', async () => {
    const fetcher = vi.fn().mockRejectedValue(new Error('boom'));
    const { result } = renderHook(() =>
      useServerPagination<Row>({ fetcher, refreshIntervalMs: 0 }),
    );

    await flush();

    expect(result.current.items).toEqual([]);
    expect(result.current.totalElements).toBe(0);
    expect(result.current.loading).toBe(false);
  });

  it('debounces search and resets to page 1 when debounced value changes', async () => {
    const fetcher = vi.fn().mockResolvedValue(samplePage([10]));
    const { rerender } = renderHook(
      ({ s }) => useServerPagination<Row>({ fetcher, search: s, refreshIntervalMs: 0 }),
      { initialProps: { s: '' } },
    );

    await flush();
    fetcher.mockClear();

    rerender({ s: 'foo' });
    // No fetch fires until the debounce window elapses.
    await act(async () => {
      vi.advanceTimersByTime(100);
    });
    expect(fetcher).not.toHaveBeenCalled();

    await act(async () => {
      vi.advanceTimersByTime(150);
    });
    await flush();
    expect(fetcher).toHaveBeenCalled();
    expect(fetcher).toHaveBeenLastCalledWith(
      expect.objectContaining({ search: 'foo', page: 0 }),
    );
  });

  it('passes baseParams + page through to the fetcher', async () => {
    const fetcher = vi.fn().mockResolvedValue(samplePage([1]));
    const baseParams = { status: 'COMPLETED' };
    const { result } = renderHook(() =>
      useServerPagination<Row>({
        fetcher,
        baseParams,
        pageSize: 25,
        refreshIntervalMs: 0,
      }),
    );

    await flush();

    expect(fetcher).toHaveBeenLastCalledWith({
      page: 0,
      size: 25,
      status: 'COMPLETED',
    });

    act(() => {
      result.current.setPage(3);
    });

    await flush();

    expect(fetcher).toHaveBeenLastCalledWith({
      page: 2,
      size: 25,
      status: 'COMPLETED',
    });
  });

  it('refresh() triggers a re-fetch without changing the page', async () => {
    const fetcher = vi.fn().mockResolvedValue(samplePage([1]));
    const { result } = renderHook(() =>
      useServerPagination<Row>({ fetcher, refreshIntervalMs: 0 }),
    );

    await flush();
    fetcher.mockClear();

    act(() => {
      result.current.refresh();
    });
    await flush();

    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(result.current.page).toBe(1);
  });

  it('does NOT refetch when baseParams identity changes but value stays equal', async () => {
    const fetcher = vi.fn().mockResolvedValue(samplePage([1]));
    const { rerender } = renderHook(
      ({ p }: { p: Record<string, unknown> }) =>
        useServerPagination<Row>({ fetcher, baseParams: p, refreshIntervalMs: 0 }),
      { initialProps: { p: { status: 'COMPLETED' } } },
    );

    await flush();
    expect(fetcher).toHaveBeenCalledTimes(1);

    // New object identity, same content — caller forgot useMemo or status really didn't change.
    rerender({ p: { status: 'COMPLETED' } });
    await flush();
    expect(fetcher).toHaveBeenCalledTimes(1);

    // Real value change — must refetch.
    rerender({ p: { status: 'PENDING' } });
    await flush();
    expect(fetcher).toHaveBeenCalledTimes(2);
    expect(fetcher).toHaveBeenLastCalledWith(
      expect.objectContaining({ status: 'PENDING', page: 0 }),
    );
  });

  it('resets page to 1 atomically on filter change — single refetch, not two', async () => {
    const fetcher = vi.fn().mockResolvedValue(samplePage([1, 2, 3], 500));
    const { result, rerender } = renderHook(
      ({ p }: { p: Record<string, unknown> }) =>
        useServerPagination<Row>({ fetcher, baseParams: p, refreshIntervalMs: 0 }),
      { initialProps: { p: { status: 'COMPLETED' } } },
    );

    await flush();
    fetcher.mockClear();

    // Move to page 5.
    act(() => {
      result.current.setPage(5);
    });
    await flush();
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(fetcher).toHaveBeenLastCalledWith(expect.objectContaining({ page: 4 }));
    fetcher.mockClear();

    // Change filter — exactly ONE fetch should fire (page=0, new filter), not two.
    rerender({ p: { status: 'PENDING' } });
    await flush();
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(fetcher).toHaveBeenLastCalledWith(
      expect.objectContaining({ page: 0, status: 'PENDING' }),
    );
    expect(result.current.page).toBe(1);
  });

  it('treats baseParams key order as semantically equal (sorted serialization)', async () => {
    const fetcher = vi.fn().mockResolvedValue(samplePage([1]));
    const { rerender } = renderHook(
      ({ p }: { p: Record<string, unknown> }) =>
        useServerPagination<Row>({ fetcher, baseParams: p, refreshIntervalMs: 0 }),
      { initialProps: { p: { status: 'COMPLETED', dateFrom: '2025-01-01' } } },
    );

    await flush();
    expect(fetcher).toHaveBeenCalledTimes(1);

    // Same fields, different declaration order — must NOT refetch.
    rerender({ p: { dateFrom: '2025-01-01', status: 'COMPLETED' } });
    await flush();
    expect(fetcher).toHaveBeenCalledTimes(1);
  });

  it('extracts items via envelopeKey for controller-specific envelopes', async () => {
    const fetcher = vi.fn().mockResolvedValue({
      orders: [{ id: 7 }, { id: 8 }],
      totalElements: 2,
    });
    const { result } = renderHook(() =>
      useServerPagination<Row>({
        fetcher,
        envelopeKey: 'orders',
        refreshIntervalMs: 0,
      }),
    );

    await flush();

    expect(result.current.items.map((r) => r.id)).toEqual([7, 8]);
    expect(result.current.totalElements).toBe(2);
  });
});
