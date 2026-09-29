import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { SpaceSky } from '../SpaceSky';

describe('SpaceSky', () => {
  it('is decorative: hidden from assistive technology and never catches the pointer', () => {
    const { container } = render(<SpaceSky />);

    const sky = container.firstElementChild;
    expect(sky).toHaveAttribute('aria-hidden', 'true');
    expect(sky).toHaveClass('pointer-events-none');
  });

  it('fills every star layer from a pattern that exists, with ids unique per instance', () => {
    const { container } = render(
      <>
        <SpaceSky />
        <SpaceSky />
      </>,
    );

    const ids = Array.from(container.querySelectorAll('pattern'), (p) => p.id);
    expect(ids).toHaveLength(4);
    expect(new Set(ids).size).toBe(4);
    for (const rect of Array.from(container.querySelectorAll('rect'))) {
      const ref = /^url\(#([\w-]+)\)$/.exec(rect.getAttribute('fill') ?? '')?.[1];
      expect(ids).toContain(ref);
    }
  });

  it('draws the same sky on every render', () => {
    const stars = (): string[] =>
      Array.from(render(<SpaceSky />).container.querySelectorAll('circle'), (c) => c.outerHTML);

    expect(stars()).toEqual(stars());
  });
});
