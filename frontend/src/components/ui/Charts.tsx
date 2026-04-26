import { useEffect, useMemo, useState } from 'react';

// =====================================================================
// Charts: tiny SVG vis primitives. Hand-rolled to avoid heavy chart deps
// for the simple shapes we need on KPI cards / dashboards.
//
// Sparkline  — KPI tiny line + area fill (one-color, no axis)
// MiniLine   — 30-day style line with axis labels
// MiniBars   — Stacked bars (completed / partial / cancelled)
// Donut      — Animated progress donut with center label
// =====================================================================

// ---------- Sparkline ----------
interface SparklineProps {
  data: number[];
  width?: number;
  height?: number;
  color?: string;
  fill?: boolean;
  stroke?: number;
  className?: string;
}

export function Sparkline({
  data,
  width = 120,
  height = 36,
  color = 'var(--accent)',
  fill = true,
  stroke = 1.75,
  className,
}: SparklineProps) {
  const id = useMemo(() => 'sp' + Math.random().toString(36).slice(2, 7), []);
  if (!data.length) return null;
  const min = Math.min(...data);
  const max = Math.max(...data);
  const r = max - min || 1;
  const step = width / (data.length - 1 || 1);
  const path = data
    .map((v, i) => `${i === 0 ? 'M' : 'L'} ${i * step} ${height - ((v - min) / r) * (height - 4) - 2}`)
    .join(' ');
  const area = path + ` L ${width} ${height} L 0 ${height} Z`;
  return (
    <svg width={width} height={height} className={className} style={{ display: 'block', overflow: 'visible' }}>
      <defs>
        <linearGradient id={id} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor={color} stopOpacity="0.22" />
          <stop offset="1" stopColor={color} stopOpacity="0" />
        </linearGradient>
      </defs>
      {fill && <path d={area} fill={`url(#${id})`} />}
      <path d={path} fill="none" stroke={color} strokeWidth={stroke} strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

// ---------- MiniLine (30-day style) ----------
interface MiniLineProps {
  data: number[] | Array<{ value: number }>;
  height?: number;
  color?: string;
  fill?: boolean;
  showAxis?: boolean;
  className?: string;
}

export function MiniLine({ data, height = 140, color = 'var(--accent)', fill = true, showAxis = true, className }: MiniLineProps) {
  const values = useMemo(
    () => (typeof data[0] === 'number' ? (data as number[]) : (data as Array<{ value: number }>).map((d) => d.value)),
    [data],
  );
  const width = 600;
  const pad = { t: 8, r: 12, b: showAxis ? 22 : 4, l: showAxis ? 38 : 4 };
  const w = width - pad.l - pad.r;
  const h = height - pad.t - pad.b;
  const max = Math.max(...values) * 1.1 || 1;
  const min = 0;
  const points = values.map((v, i) => {
    const x = pad.l + (i / (values.length - 1)) * w;
    const y = pad.t + h - ((v - min) / (max - min || 1)) * h;
    return [x, y] as const;
  });
  const path = points.map((p, i) => (i === 0 ? 'M' : 'L') + p[0].toFixed(1) + ' ' + p[1].toFixed(1)).join(' ');
  const areaPath = points.length > 0
    ? path + ` L ${points[points.length - 1][0].toFixed(1)} ${pad.t + h} L ${points[0][0].toFixed(1)} ${pad.t + h} Z`
    : '';
  const yTicks = 4;
  return (
    <svg viewBox={`0 0 ${width} ${height}`} className={className} style={{ width: '100%', height, display: 'block' }}>
      {showAxis &&
        Array.from({ length: yTicks + 1 }).map((_, i) => {
          const y = pad.t + (h / yTicks) * i;
          const v = max - (max / yTicks) * i;
          return (
            <g key={i}>
              <line x1={pad.l} x2={pad.l + w} y1={y} y2={y} stroke="var(--border)" strokeDasharray="2,3" />
              <text x={pad.l - 6} y={y + 3} fontSize="10" fill="var(--fg-dim)" textAnchor="end" fontFamily="var(--mono)">
                {v >= 1000 ? (v / 1000).toFixed(1) + 'k' : v.toFixed(0)}
              </text>
            </g>
          );
        })}
      {fill && <path d={areaPath} fill={color} opacity="0.08" />}
      <path d={path} stroke={color} strokeWidth="1.75" fill="none" />
      {points.map((p, i) =>
        i % 5 === 0 ? <circle key={i} cx={p[0]} cy={p[1]} r="2" fill={color} /> : null,
      )}
      {showAxis &&
        values.map((_, i) =>
          i % 5 === 0 ? (
            <text
              key={i}
              x={pad.l + (i / (values.length - 1)) * w}
              y={height - 6}
              fontSize="10"
              fill="var(--fg-dim)"
              textAnchor="middle"
              fontFamily="var(--mono)"
            >
              {-(values.length - 1 - i)}d
            </text>
          ) : null,
        )}
    </svg>
  );
}

// ---------- MiniBars (stacked completed/partial/cancelled) ----------
interface MiniBarsProps {
  data: Array<{ completed: number; partial: number; cancelled: number }>;
  height?: number;
  className?: string;
}

export function MiniBars({ data, height = 140, className }: MiniBarsProps) {
  const width = 600;
  const pad = { t: 8, r: 12, b: 22, l: 38 };
  const w = width - pad.l - pad.r;
  const h = height - pad.t - pad.b;
  const totals = data.map((d) => d.completed + d.partial + d.cancelled);
  const max = Math.max(...totals) * 1.1 || 1;
  const slot = w / data.length;
  const bw = slot * 0.65;
  const gap = slot * 0.35;
  const yTicks = 4;
  return (
    <svg viewBox={`0 0 ${width} ${height}`} className={className} style={{ width: '100%', height, display: 'block' }}>
      {Array.from({ length: yTicks + 1 }).map((_, i) => {
        const y = pad.t + (h / yTicks) * i;
        const v = max - (max / yTicks) * i;
        return (
          <g key={i}>
            <line x1={pad.l} x2={pad.l + w} y1={y} y2={y} stroke="var(--border)" strokeDasharray="2,3" />
            <text x={pad.l - 6} y={y + 3} fontSize="10" fill="var(--fg-dim)" textAnchor="end" fontFamily="var(--mono)">
              {v.toFixed(0)}
            </text>
          </g>
        );
      })}
      {data.map((d, i) => {
        const x = pad.l + slot * i + gap / 2;
        const scale = h / max;
        const hC = d.completed * scale;
        const hP = d.partial * scale;
        const hX = d.cancelled * scale;
        const y0 = pad.t + h;
        return (
          <g key={i}>
            <rect x={x} y={y0 - hC} width={bw} height={hC} fill="#047857" />
            <rect x={x} y={y0 - hC - hP} width={bw} height={hP} fill="#a16207" />
            <rect x={x} y={y0 - hC - hP - hX} width={bw} height={hX} fill="#78716c" />
          </g>
        );
      })}
      {data.map((_, i) =>
        i % 5 === 0 ? (
          <text
            key={i}
            x={pad.l + slot * i + bw / 2 + gap / 2}
            y={height - 6}
            fontSize="10"
            fill="var(--fg-dim)"
            textAnchor="middle"
            fontFamily="var(--mono)"
          >
            {-(data.length - 1 - i)}d
          </text>
        ) : null,
      )}
    </svg>
  );
}

// ---------- Donut (animated) ----------
interface DonutProps {
  /** 0..1 progress fraction */
  progress: number;
  size?: number;
  stroke?: number;
  color?: string;
  trackColor?: string;
  /** Big number displayed in center. */
  label?: React.ReactNode;
  /** Smaller mono caption below the label. */
  sublabel?: React.ReactNode;
  /** Disable animation (e.g. on first paint of test). */
  animate?: boolean;
  className?: string;
}

export function Donut({
  progress,
  size = 180,
  stroke = 14,
  color = 'var(--accent)',
  trackColor = 'var(--border)',
  label,
  sublabel,
  animate = true,
  className,
}: DonutProps) {
  const r = (size - stroke) / 2;
  const c = 2 * Math.PI * r;
  const [p, setP] = useState(animate ? 0 : progress);

  useEffect(() => {
    if (!animate) {
      setP(progress);
      return;
    }
    let raf = 0;
    const t0 = performance.now();
    const dur = 900;
    const from = 0;
    const to = progress;
    const loop = (now: number) => {
      const k = Math.min(1, (now - t0) / dur);
      const e = 1 - Math.pow(1 - k, 3); // ease-out cubic
      setP(from + (to - from) * e);
      if (k < 1) raf = requestAnimationFrame(loop);
    };
    raf = requestAnimationFrame(loop);
    return () => cancelAnimationFrame(raf);
  }, [progress, animate]);

  return (
    <div className={className} style={{ position: 'relative', width: size, height: size }}>
      <svg width={size} height={size} style={{ transform: 'rotate(-90deg)' }}>
        <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke={trackColor} strokeWidth={stroke} />
        <circle
          cx={size / 2}
          cy={size / 2}
          r={r}
          fill="none"
          stroke={color}
          strokeWidth={stroke}
          strokeLinecap="round"
          strokeDasharray={c}
          strokeDashoffset={c * (1 - p)}
        />
      </svg>
      <div
        style={{
          position: 'absolute',
          inset: 0,
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
          textAlign: 'center',
        }}
      >
        {label != null && (
          <div style={{ fontSize: size * 0.22, fontWeight: 700, letterSpacing: '-0.02em' }}>{label}</div>
        )}
        {sublabel && (
          <div style={{ fontSize: 11, color: 'var(--fg-subtle)', fontFamily: 'var(--mono)', marginTop: 2 }}>{sublabel}</div>
        )}
      </div>
    </div>
  );
}
