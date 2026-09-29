import { useId } from 'react';
import { cn } from '../../lib/utils';

// The night sky of the 3D logo's design, stretched over a whole section: a deep navy base and two
// tiled star layers. The glow around the planet belongs to Logo3D, which knows where the logo is.

const SKY = 'radial-gradient(ellipse 100% 90% at 60% 45%, #080d24 35%, #04060f 100%)';

interface Star {
  x: number;
  y: number;
  r: number;
  opacity: number;
  blue: boolean;
}

interface StarTile {
  size: number;
  stars: Star[];
}

/** mulberry32: a tiny seeded PRNG, so the sky is the same on every render and every visit. */
function seeded(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function starTile(seed: number, size: number, count: number, rMin: number, rMax: number): StarTile {
  const random = seeded(seed);
  const stars = Array.from({ length: count }, () => ({
    x: random() * size,
    y: random() * size,
    r: rMin + random() * (rMax - rMin),
    opacity: 0.2 + random() * 0.5,
    blue: random() < 0.3,
  }));
  return { size, stars };
}

// Two layers with unrelated tile sizes, so the repetition never lines up into a visible grid.
const TILES: StarTile[] = [starTile(7, 420, 16, 0.4, 0.8), starTile(19, 677, 9, 0.7, 1.1)];

export interface SpaceSkyProps {
  className?: string;
}

export function SpaceSky({ className }: SpaceSkyProps) {
  // useId() contains characters that are awkward inside url(#...) references.
  const id = `sky${useId().replace(/[^a-zA-Z0-9_-]/g, '')}`;
  return (
    <div
      aria-hidden="true"
      className={cn('pointer-events-none absolute inset-0', className)}
      style={{ background: SKY }}
    >
      <svg className="absolute inset-0 h-full w-full">
        <defs>
          {TILES.map((tile, i) => (
            <pattern
              key={tile.size}
              id={`${id}-${i}`}
              width={tile.size}
              height={tile.size}
              patternUnits="userSpaceOnUse"
            >
              {tile.stars.map((star, j) => (
                <circle
                  key={j}
                  cx={star.x}
                  cy={star.y}
                  r={star.r}
                  fill={star.blue ? '#b8c8ff' : '#ffffff'}
                  fillOpacity={star.opacity}
                />
              ))}
            </pattern>
          ))}
        </defs>
        {TILES.map((tile, i) => (
          <rect key={tile.size} width="100%" height="100%" fill={`url(#${id}-${i})`} />
        ))}
      </svg>
    </div>
  );
}
