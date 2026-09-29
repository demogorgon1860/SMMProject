import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { cn } from '../../../lib/utils';
import type { LogoSceneHandle, LogoSceneOptions } from './createLogoScene';

export interface Logo3DProps {
  /**
   * Asked once, right before a scene is built: whether to play the fly-in intro. Lets the page
   * play it on arrival but not when a theme switch remounts the hero. Defaults to always.
   */
  shouldPlayIntro?: () => boolean;
  className?: string;
}

type Status = 'loading' | 'ready' | 'fallback';
type SceneSettings = Omit<LogoSceneOptions, 'playIntro'>;

// The standalone design's sky glowed around the planet. The page paints the sky (SpaceSky); this
// glow follows the logo wherever the layout puts it, and fades out into the page's navy (#080d24).
const PLANET_GLOW = 'radial-gradient(closest-side, #24377a 5%, #131e48 39%, rgb(8 13 36 / 0) 100%)';

/**
 * How long a scene outlives its component. A theme switch remounts the hero immediately, so it
 * picks the same scene back up — no rebuild, no stutter, the planet keeps turning.
 */
const PARK_MS = 2000;

// ---------------------------------------------------------------------------------------------
// One scene kept alive across remounts. Building it is the expensive part (up to ~0.8 s of main
// thread on a first visit on Windows, see bakeEnvironment), so it is never rebuilt just because
// React remounted.
// ---------------------------------------------------------------------------------------------

interface ParkedScene {
  scene: LogoSceneHandle;
  key: string;
  timer: ReturnType<typeof setTimeout>;
}

let parked: ParkedScene | null = null;

const settingsKey = (s: SceneSettings): string =>
  `${s.reducedMotion}|${s.interactive}|${s.maxPixelRatio}`;

function discardParked(): void {
  if (!parked) return;
  clearTimeout(parked.timer);
  parked.scene.dispose();
  parked = null;
}

function park(scene: LogoSceneHandle, key: string): void {
  discardParked();
  scene.setActive(false);
  scene.onContextLost = null;
  scene.canvas.remove();
  parked = { scene, key, timer: setTimeout(discardParked, PARK_MS) };
}

/** Takes the parked scene if it was built with the same settings; otherwise frees it. */
function unpark(key: string): LogoSceneHandle | null {
  if (!parked) return null;
  const { scene, key: parkedKey, timer } = parked;
  clearTimeout(timer);
  parked = null;
  if (parkedKey === key) return scene;
  scene.dispose();
  return null;
}

// ---------------------------------------------------------------------------------------------

function useMediaQuery(query: string): boolean {
  const subscribe = useCallback(
    (onChange: () => void) => {
      if (typeof window.matchMedia !== 'function') return () => undefined;
      const list = window.matchMedia(query);
      list.addEventListener('change', onChange);
      return () => list.removeEventListener('change', onChange);
    },
    [query],
  );
  return useSyncExternalStore(
    subscribe,
    () => typeof window.matchMedia === 'function' && window.matchMedia(query).matches,
    () => false,
  );
}

/** Resolves when the browser is idle (or after 1.5 s at the latest). */
function whenIdle(): Promise<void> {
  return new Promise((resolve) => {
    if (typeof window.requestIdleCallback === 'function') {
      window.requestIdleCallback(() => resolve(), { timeout: 1500 });
    } else {
      setTimeout(resolve, 200);
    }
  });
}

/**
 * The SMM World logo as an interactive 3D scene (three.js), with a fly-in intro. It floats on a
 * dark sky the page paints (SpaceSky) and adds the glow around the planet itself.
 *
 * three.js (~160 KB gzip) is loaded with a dynamic import and the scene is built once the page is
 * idle, so it never delays the landing page's content. The scene only runs while on screen,
 * holds still for prefers-reduced-motion, never captures touch or wheel scrolling, survives theme
 * switches without a rebuild, and falls back to the static logo when WebGL is unavailable,
 * software-rendered, or lost twice.
 */
export function Logo3D({ shouldPlayIntro, className }: Logo3DProps) {
  const hostRef = useRef<HTMLDivElement>(null);
  const sceneRef = useRef<LogoSceneHandle | null>(null);
  const [status, setStatus] = useState<Status>('loading');
  const reducedMotion = useMediaQuery('(prefers-reduced-motion: reduce)');
  // Drag-to-rotate needs a mouse; on touch screens it would swallow the page scroll.
  const finePointer = useMediaQuery('(hover: hover) and (pointer: fine)');

  // Latest values for the scene effect, which must not re-run (and rebuild) when they change.
  const shouldPlayIntroRef = useRef(shouldPlayIntro);
  useEffect(() => {
    shouldPlayIntroRef.current = shouldPlayIntro;
  }, [shouldPlayIntro]);

  useEffect(() => {
    const host = hostRef.current;
    if (!host) return undefined;

    const settings: SceneSettings = {
      reducedMotion,
      interactive: finePointer,
      // GPU cost grows with the square of the pixel ratio; phones get a lower cap.
      maxPixelRatio: finePointer ? 2 : 1.5,
    };
    const key = settingsKey(settings);
    let cancelled = false;
    let scene: LogoSceneHandle | null = null;
    let resizeObserver: ResizeObserver | null = null;
    let visibilityObserver: IntersectionObserver | null = null;
    let retryTimer: ReturnType<typeof setTimeout> | undefined;
    let retried = false;

    const detach = (): void => {
      resizeObserver?.disconnect();
      visibilityObserver?.disconnect();
      resizeObserver = null;
      visibilityObserver = null;
      sceneRef.current = null;
    };

    const destroy = (): void => {
      detach();
      if (scene) {
        scene.dispose();
        scene.canvas.remove();
        scene = null;
      }
    };

    const attach = (next: LogoSceneHandle, fresh: boolean): void => {
      scene = next;
      sceneRef.current = next;
      next.onContextLost = onContextLost;

      const { canvas } = next;
      canvas.setAttribute('aria-hidden', 'true');
      canvas.style.display = 'block';
      canvas.style.width = '100%';
      canvas.style.height = '100%';
      if (fresh) {
        canvas.style.opacity = '0';
        canvas.style.transition = 'opacity 0.6s ease';
      }
      host.appendChild(canvas);

      const resize = (): void => next.setSize(host.clientWidth, host.clientHeight);
      resize();
      if (typeof ResizeObserver === 'function') {
        resizeObserver = new ResizeObserver(resize);
        resizeObserver.observe(host);
      }

      // Run only while on screen: the intro waits until it can be seen, and an off-screen hero
      // costs no GPU time. requestAnimationFrame already pauses in background tabs.
      if (typeof IntersectionObserver === 'function') {
        visibilityObserver = new IntersectionObserver(([entry]) => {
          if (entry) sceneRef.current?.setActive(entry.isIntersecting);
        });
        visibilityObserver.observe(host);
      } else {
        next.setActive(true);
      }

      if (fresh) {
        requestAnimationFrame(() => {
          if (scene === next) canvas.style.opacity = '1';
        });
      }
      setStatus('ready');
    };

    function onContextLost(): void {
      if (cancelled) return;
      destroy();
      if (retried) {
        setStatus('fallback');
        return;
      }
      // Contexts are usually lost to a transient GPU reset: one rebuild, then the static logo.
      retried = true;
      setStatus('loading');
      retryTimer = setTimeout(() => void start(), 1000);
    }

    async function start(): Promise<void> {
      try {
        const reused = unpark(key);
        if (reused) {
          attach(reused, false);
          return;
        }
        const [{ createLogoScene }] = await Promise.all([import('./createLogoScene'), whenIdle()]);
        if (cancelled) return;
        const created = await createLogoScene({
          ...settings,
          playIntro: shouldPlayIntroRef.current?.() ?? true,
        });
        if (cancelled) {
          created.dispose();
          return;
        }
        attach(created, true);
      } catch {
        // No WebGL 2, a software renderer, or the chunk failed to load: the static logo stands in.
        if (cancelled) return;
        destroy();
        setStatus('fallback');
      }
    }

    void start();

    return () => {
      cancelled = true;
      clearTimeout(retryTimer);
      detach();
      if (scene) {
        park(scene, key); // a remount right after (theme switch) picks it back up
        scene = null;
      }
    };
  }, [reducedMotion, finePointer]);

  return (
    <div className={cn('relative mx-auto aspect-square w-full select-none', className)}>
      <div
        aria-hidden="true"
        className="pointer-events-none absolute -inset-[20%]"
        style={{ background: PLANET_GLOW }}
      />
      {/* The mask feathers whatever flies near the canvas edge (the intro, the orbiting icons)
          into the sky, so the square canvas never shows. */}
      <div
        ref={hostRef}
        role="img"
        aria-label="SMM World logo"
        data-status={status}
        className="absolute inset-0 [mask-image:radial-gradient(circle_closest-side,#000_88%,transparent_100%)]"
      >
        {status === 'fallback' && (
          <div className="absolute inset-0 grid place-items-center">
            <div className="grid aspect-square w-[62%] place-items-center rounded-full bg-white shadow-pop">
              <img src="/logo-v2.png" alt="" className="w-[88%] object-contain" draggable={false} />
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
