import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { LogoSceneHandle, LogoSceneOptions } from '../createLogoScene';

// The real scene needs WebGL 2, which jsdom does not have. Each test decides what the
// dynamically imported createLogoScene does.
const { createLogoScene } = vi.hoisted(() => ({
  createLogoScene: vi.fn<[LogoSceneOptions], Promise<LogoSceneHandle>>(),
}));
vi.mock('../createLogoScene', () => ({ createLogoScene }));

// Logo3D keeps a scene alive across remounts in module state, so every test gets a fresh module.
let Logo3D: (typeof import('../Logo3D'))['Logo3D'];

interface FakeScene extends LogoSceneHandle {
  setSize: ReturnType<typeof vi.fn>;
  setActive: ReturnType<typeof vi.fn>;
  dispose: ReturnType<typeof vi.fn>;
}

function fakeScene(): FakeScene {
  return {
    canvas: document.createElement('canvas'),
    setSize: vi.fn(),
    setActive: vi.fn(),
    dispose: vi.fn(),
    onContextLost: null,
  };
}

function stubMedia(matching: (query: string) => boolean): void {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: matching(query),
    media: query,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  }));
}

const logo = () => screen.getByRole('img', { name: 'SMM World logo' });
const ready = () => waitFor(() => expect(logo()).toHaveAttribute('data-status', 'ready'));

describe('Logo3D', () => {
  beforeEach(async () => {
    createLogoScene.mockReset();
    localStorage.clear();
    vi.resetModules();
    ({ Logo3D } = await import('../Logo3D'));
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('mounts the scene canvas, sizes it and runs it (no IntersectionObserver in jsdom)', async () => {
    const scene = fakeScene();
    createLogoScene.mockResolvedValue(scene);

    render(<Logo3D />);

    await ready();
    expect(logo()).toContainElement(scene.canvas);
    expect(scene.canvas).toHaveAttribute('aria-hidden', 'true');
    expect(scene.setSize).toHaveBeenCalled();
    expect(scene.setActive).toHaveBeenCalledWith(true);
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('asks whether to play the intro exactly once and passes the answer on', async () => {
    createLogoScene.mockResolvedValue(fakeScene());
    const shouldPlayIntro = vi.fn(() => false);

    render(<Logo3D shouldPlayIntro={shouldPlayIntro} />);

    await ready();
    expect(shouldPlayIntro).toHaveBeenCalledTimes(1);
    expect(createLogoScene).toHaveBeenCalledWith(expect.objectContaining({ playIntro: false }));
  });

  it('honours prefers-reduced-motion and keeps touch screens non-interactive', async () => {
    stubMedia((query) => query.includes('reduce'));
    createLogoScene.mockResolvedValue(fakeScene());

    render(<Logo3D />);

    await ready();
    expect(createLogoScene).toHaveBeenCalledWith(
      expect.objectContaining({ reducedMotion: true, interactive: false, maxPixelRatio: 1.5 }),
    );
  });

  it('falls back to the static logo when the scene cannot be created', async () => {
    createLogoScene.mockRejectedValue(new Error('Error creating WebGL context.'));

    const { container } = render(<Logo3D />);

    await waitFor(() => expect(logo()).toHaveAttribute('data-status', 'fallback'));
    expect(container.querySelector('img[src="/logo-v2.png"]')).not.toBeNull();
    expect(container.querySelector('canvas')).toBeNull();
  });

  it('rebuilds once after a lost WebGL context, and falls back if it is lost again', async () => {
    const first = fakeScene();
    const second = fakeScene();
    createLogoScene.mockResolvedValueOnce(first).mockResolvedValueOnce(second);

    const { container } = render(<Logo3D />);
    await ready();

    act(() => first.onContextLost?.());
    expect(first.dispose).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(logo()).toContainElement(second.canvas), { timeout: 3000 });
    expect(createLogoScene).toHaveBeenCalledTimes(2);

    act(() => second.onContextLost?.());
    await waitFor(() => expect(logo()).toHaveAttribute('data-status', 'fallback'));
    expect(second.dispose).toHaveBeenCalledTimes(1);
    expect(container.querySelector('canvas')).toBeNull();
  });

  it('reuses the scene when remounted right away (a theme switch) instead of rebuilding it', async () => {
    const scene = fakeScene();
    createLogoScene.mockResolvedValue(scene);

    const { unmount } = render(<Logo3D />);
    await ready();
    unmount();
    render(<Logo3D />);

    await ready();
    expect(createLogoScene).toHaveBeenCalledTimes(1);
    expect(logo()).toContainElement(scene.canvas);
    expect(scene.dispose).not.toHaveBeenCalled();
  });

  it('frees the scene when the component is not remounted', async () => {
    const scene = fakeScene();
    createLogoScene.mockResolvedValue(scene);

    const { unmount } = render(<Logo3D />);
    await ready();
    vi.useFakeTimers();
    unmount();

    expect(scene.canvas.isConnected).toBe(false);
    expect(scene.dispose).not.toHaveBeenCalled();
    vi.advanceTimersByTime(2100);
    expect(scene.dispose).toHaveBeenCalledTimes(1);
  });

  it('disposes a scene that finishes building after the page was left', async () => {
    const scene = fakeScene();
    let finish: (s: LogoSceneHandle) => void = () => undefined;
    createLogoScene.mockImplementation(() => new Promise((resolve) => (finish = resolve)));

    const { unmount } = render(<Logo3D />);
    await waitFor(() => expect(createLogoScene).toHaveBeenCalled());
    unmount();
    finish(scene);

    await waitFor(() => expect(scene.dispose).toHaveBeenCalledTimes(1));
    expect(scene.canvas.isConnected).toBe(false);
    expect(scene.setActive).not.toHaveBeenCalled();
  });
});
