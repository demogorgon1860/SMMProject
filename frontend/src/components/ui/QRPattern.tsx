import { useMemo } from 'react';

// =====================================================================
// QRPattern — deterministic 29×29 mock QR. Used on Add Funds payment
// step for placeholder visuals; production replaces with real QR.
// Always shows finder corners + bottom-right alignment block so it
// reads as a QR at a glance.
// =====================================================================

interface QRPatternProps {
  data: string;
  size?: number;
  fg?: string;
  bg?: string;
  className?: string;
}

export function QRPattern({ data, size = 176, fg = '#0a0a0a', bg = '#fff', className }: QRPatternProps) {
  const n = 29;
  const bits = useMemo(() => {
    let h = 2166136261;
    for (let i = 0; i < data.length; i++) {
      h ^= data.charCodeAt(i);
      h = Math.imul(h, 16777619);
    }
    const arr: boolean[] = [];
    for (let i = 0; i < n * n; i++) {
      h = Math.imul(h ^ (h >>> 13), 1274126177);
      arr.push((h & 1) === 1);
    }
    return arr;
  }, [data]);

  const cell = size / n;

  const finderOn = (rr: number, cc: number) => {
    if (rr < 0 || cc < 0 || rr > 6 || cc > 6) return false;
    if (rr === 0 || rr === 6 || cc === 0 || cc === 6) return true;
    if (rr >= 2 && rr <= 4 && cc >= 2 && cc <= 4) return true;
    return false;
  };

  const on = (r: number, c: number): boolean => {
    if (r < 7 && c < 7) return finderOn(r, c);
    if (r < 7 && c >= n - 7) return finderOn(r, c - (n - 7));
    if (r >= n - 7 && c < 7) return finderOn(r - (n - 7), c);
    if (r >= n - 9 && r <= n - 5 && c >= n - 9 && c <= n - 5) {
      const rr = r - (n - 9);
      const cc = c - (n - 9);
      if (rr === 0 || rr === 4 || cc === 0 || cc === 4) return true;
      if (rr === 2 && cc === 2) return true;
      return false;
    }
    return bits[r * n + c];
  };

  const rects: React.ReactElement[] = [];
  for (let r = 0; r < n; r++) {
    for (let c = 0; c < n; c++) {
      if (on(r, c)) {
        rects.push(<rect key={r + '_' + c} x={c * cell} y={r * cell} width={cell} height={cell} fill={fg} />);
      }
    }
  }
  return (
    <svg width={size} height={size} className={className} style={{ display: 'block', background: bg, borderRadius: 8 }}>
      {rects}
    </svg>
  );
}
