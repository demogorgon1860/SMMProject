import { describe, expect, it } from 'vitest';
import { unwrapList, unwrapPage } from '../api';

describe('unwrapList', () => {
  it('returns bare arrays untouched', () => {
    expect(unwrapList([1, 2, 3])).toEqual([1, 2, 3]);
    expect(unwrapList([])).toEqual([]);
  });

  it('returns [] for null / undefined / primitives', () => {
    expect(unwrapList(null)).toEqual([]);
    expect(unwrapList(undefined)).toEqual([]);
    expect(unwrapList('hi')).toEqual([]);
    expect(unwrapList(42)).toEqual([]);
  });

  it('extracts named keys (controller-specific envelopes win first)', () => {
    expect(unwrapList({ orders: [1, 2] }, ['orders'])).toEqual([1, 2]);
    expect(unwrapList({ users: [1] }, ['users'])).toEqual([1]);
    expect(unwrapList({ deposits: [{ id: 1 }] }, ['deposits'])).toEqual([{ id: 1 }]);
  });

  it('falls back to Spring Page `content`', () => {
    expect(unwrapList({ content: [1, 2], totalElements: 2 })).toEqual([1, 2]);
  });

  it('unwraps PerfectPanelResponse `data`', () => {
    expect(unwrapList({ success: true, data: [1, 2] })).toEqual([1, 2]);
  });

  it('unwraps PerfectPanelResponse wrapping a Spring Page', () => {
    expect(
      unwrapList({ success: true, data: { content: [1, 2], totalElements: 2 } }),
    ).toEqual([1, 2]);
  });

  it('unwraps PerfectPanelResponse wrapping a named-key envelope', () => {
    expect(
      unwrapList({ success: true, data: { orders: [{ id: 1 }] } }, ['orders']),
    ).toEqual([{ id: 1 }]);
  });

  it('prefers named-key over generic `content` when both exist', () => {
    // If a controller ever returns both, the per-controller key is the more specific signal.
    expect(
      unwrapList({ orders: [{ id: 1 }], content: [{ id: 99 }] }, ['orders']),
    ).toEqual([{ id: 1 }]);
  });

  it('returns [] when no shape matches', () => {
    expect(unwrapList({ foo: 'bar' })).toEqual([]);
    expect(unwrapList({ data: 'not-an-array' })).toEqual([]);
  });
});

describe('unwrapPage', () => {
  it('reads totalElements off Spring Page', () => {
    expect(unwrapPage({ content: [1, 2, 3], totalElements: 42 })).toEqual({
      items: [1, 2, 3],
      totalElements: 42,
    });
  });

  it('reads totalElements off PerfectPanelResponse-wrapped Spring Page', () => {
    expect(
      unwrapPage({ success: true, data: { content: [1, 2], totalElements: 99 } }),
    ).toEqual({ items: [1, 2], totalElements: 99 });
  });

  it('reads totalElements off named-key envelope when provided alongside list', () => {
    expect(
      unwrapPage({ orders: [{ id: 1 }], totalElements: 5 }, ['orders']),
    ).toEqual({ items: [{ id: 1 }], totalElements: 5 });
  });

  it('falls back to items.length when no pagination metadata exists', () => {
    expect(unwrapPage([1, 2, 3])).toEqual({ items: [1, 2, 3], totalElements: 3 });
    expect(unwrapPage({ orders: [1] }, ['orders'])).toEqual({
      items: [1],
      totalElements: 1,
    });
  });

  it('returns {[], 0} for unrecognized shapes', () => {
    expect(unwrapPage(null)).toEqual({ items: [], totalElements: 0 });
    expect(unwrapPage({ foo: 'bar' })).toEqual({ items: [], totalElements: 0 });
  });
});
