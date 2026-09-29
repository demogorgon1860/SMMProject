import {
  ACESFilmicToneMapping,
  AdditiveBlending,
  BackSide,
  BoxGeometry,
  BufferAttribute,
  BufferGeometry,
  CanvasTexture,
  CatmullRomCurve3,
  Color,
  CylinderGeometry,
  DirectionalLight,
  DoubleSide,
  ExtrudeGeometry,
  Float32BufferAttribute,
  Group,
  HemisphereLight,
  MathUtils,
  Mesh,
  MeshBasicMaterial,
  MeshPhysicalMaterial,
  PerspectiveCamera,
  PMREMGenerator,
  PointLight,
  Points,
  PointsMaterial,
  QuadraticBezierCurve3,
  RingGeometry,
  Scene,
  ShaderMaterial,
  Shape,
  SphereGeometry,
  Sprite,
  SpriteMaterial,
  SRGBColorSpace,
  TorusGeometry,
  TubeGeometry,
  Vector2,
  Vector3,
  WebGLRenderer,
  type WebGLRenderTarget,
  type Curve,
  type Material,
  type MeshPhysicalMaterialParameters,
  type Object3D,
  type Texture,
} from 'three';
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js';
import { RoomEnvironment } from 'three/examples/jsm/environments/RoomEnvironment.js';
import { Font, type FontData } from 'three/examples/jsm/loaders/FontLoader.js';
import { FONT_SMM, FONT_WORLD } from './fontData';

// =====================================================================
// SMM World 3D logo — a planet wrapped in a glowing network grid, social
// icons orbiting it, and the "SMM World" lettering floating in front.
//
// Ported from the approved standalone design (three r147). Differences, all
// deliberate: the canvas is transparent (the page provides the sky), there is
// no on-canvas UI, zoom/pan are off so the hero never hijacks page scroll, and
// every GPU resource is released on dispose().
//
// This module pulls in three.js, so it must only ever be loaded through a
// dynamic import() — see Logo3D.tsx.
// =====================================================================

export interface LogoSceneOptions {
  /** Play the fly-in intro; otherwise the logo starts in its resting pose. */
  playIntro: boolean;
  /** prefers-reduced-motion: no intro and no ambient motion. */
  reducedMotion: boolean;
  /** Drag-to-rotate. Only for fine pointers — on touch it would trap page scroll. */
  interactive: boolean;
  /** Upper bound for devicePixelRatio (GPU cost grows with its square). */
  maxPixelRatio: number;
}

export interface LogoSceneHandle {
  /** The canvas the scene draws into; the caller mounts it. */
  readonly canvas: HTMLCanvasElement;
  setSize(width: number, height: number): void;
  /** Runs the render loop while true. The intro timeline only advances while active. */
  setActive(active: boolean): void;
  /**
   * Called once if the WebGL context is lost (GPU reset, driver crash, context limit). Settable
   * because a scene kept alive across remounts changes owner.
   */
  onContextLost: (() => void) | null;
  dispose(): void;
}

// The design was authored on three r147 (legacy lights, older PMREM/BRDF). Rendered on r186
// with its intensities as-is, the glossy icons come out washed-out and pastel. These factors
// were calibrated by rendering both versions with identical framing and minimising the
// per-pixel difference (mean colour within ~3/255 of the original). Don't "fix" them to π.
const DESIGN_LIGHT_SCALE = 2.6;
/** Environment intensity for the planet, grid and icons; the lettering keeps 1. */
const DESIGN_ENV_INTENSITY = 0.35;

const NAVY_DEEP = 0x0b1230;
const WHITE = 0xffffff;
const GLOW = 0x4f7dff;
const R = 1.6;
const ORB = R + 0.6;
const HOME = new Vector3(0, 0.3, 10.5);
const START_CAM = new Vector3(0, 3.4, 17);
const ORIGIN = new Vector3(0, 0, 0);
const INTRO_END = 3.6;

// Geometry detail. The standalone design used far more segments than a ~500 px hero can show:
// ~1.1M vertices and a 1.4 s main-thread build (text alone 680k). These keep every silhouette the
// same at hero size for a fraction of the vertices.
const DETAIL = {
  iconCurve: 10,
  iconBevel: 3,
  textCurve: 6,
  textBevel: 5, // the letters' bevel shading is what reads as "3D" — kept as designed
  meridian: 256,
  parallel: 160,
  sphereWidth: 96,
  sphereHeight: 64,
  shellWidth: 64,
  shellHeight: 48,
  torus: 160,
} as const;
const MAX_FRAME_SECONDS = 0.05;
/** Frame cap (~90 fps): halves the GPU cost on 120/144 Hz screens, invisible for this scene. */
const MIN_FRAME_MS = 1000 / 90;

const clamp01 = (x: number): number => Math.min(1, Math.max(0, x));
const seg = (t: number, start: number, dur: number): number => clamp01((t - start) / dur);
const easeOutCubic = (x: number): number => 1 - Math.pow(1 - x, 3);
const easeInOutCubic = (x: number): number =>
  x < 0.5 ? 4 * x * x * x : 1 - Math.pow(-2 * x + 2, 3) / 2;
const easeOutExpo = (x: number): number => (x === 1 ? 1 : 1 - Math.pow(2, -10 * x));
const easeOutBack = (x: number): number => {
  const c1 = 1.70158;
  const c3 = c1 + 1;
  return 1 + c3 * Math.pow(x - 1, 3) + c1 * Math.pow(x - 1, 2);
};

function canvasTexture(
  width: number,
  height: number,
  paint: (g: CanvasRenderingContext2D) => void,
  srgb: boolean,
): CanvasTexture {
  const c = document.createElement('canvas');
  c.width = width;
  c.height = height;
  const g = c.getContext('2d');
  if (!g) throw new Error('2D canvas unavailable');
  paint(g);
  const tex = new CanvasTexture(c);
  if (srgb) tex.colorSpace = SRGBColorSpace;
  return tex;
}

function spherePoint(latDeg: number, lonDeg: number, r: number): Vector3 {
  const lat = MathUtils.degToRad(latDeg);
  const lon = MathUtils.degToRad(lonDeg);
  return new Vector3(
    r * Math.cos(lat) * Math.sin(lon),
    r * Math.sin(lat),
    r * Math.cos(lat) * Math.cos(lon),
  );
}

function meridianCurve(lonDeg: number, r: number): CatmullRomCurve3 {
  const pts: Vector3[] = [];
  for (let i = 0; i <= 256; i++) pts.push(spherePoint(-90 + (180 * i) / 256, lonDeg, r));
  for (let i = 1; i <= 256; i++) pts.push(spherePoint(90 - (180 * i) / 256, lonDeg + 180, r));
  return new CatmullRomCurve3(pts, true);
}

function parallelCurve(latDeg: number, r: number): CatmullRomCurve3 {
  const pts: Vector3[] = [];
  for (let i = 0; i < 256; i++) pts.push(spherePoint(latDeg, (360 * i) / 256, r));
  return new CatmullRomCurve3(pts, true);
}

// ---------- 2D shapes ----------

function roundedRect(w: number, h: number, r: number, cx = 0, cy = 0): Shape {
  const s = new Shape();
  const x = cx - w / 2;
  const y = cy - h / 2;
  s.moveTo(x + r, y);
  s.lineTo(x + w - r, y);
  s.quadraticCurveTo(x + w, y, x + w, y + r);
  s.lineTo(x + w, y + h - r);
  s.quadraticCurveTo(x + w, y + h, x + w - r, y + h);
  s.lineTo(x + r, y + h);
  s.quadraticCurveTo(x, y + h, x, y + h - r);
  s.lineTo(x, y + r);
  s.quadraticCurveTo(x, y, x + r, y);
  return s;
}

function transformShape(shape: Shape, angle: number, tx: number, ty: number): Shape {
  const cos = Math.cos(angle);
  const sin = Math.sin(angle);
  return new Shape(
    shape
      .getPoints(24)
      .map((p) => new Vector2(p.x * cos - p.y * sin + tx, p.x * sin + p.y * cos + ty)),
  );
}

function extrude(shapes: Shape[], depth: number, bevel = 0.03, bevelSize = bevel): ExtrudeGeometry {
  const g = new ExtrudeGeometry(shapes, {
    depth,
    bevelEnabled: true,
    bevelThickness: bevel,
    bevelSize,
    bevelSegments: DETAIL.iconBevel,
    curveSegments: DETAIL.iconCurve,
  });
  g.translate(0, 0, -depth / 2);
  return g;
}

function heartShape(s: number): Shape {
  const h = new Shape();
  h.moveTo(0, -0.4 * s);
  h.bezierCurveTo(-0.15 * s, -0.25 * s, -0.5 * s, -0.05 * s, -0.5 * s, 0.18 * s);
  h.bezierCurveTo(-0.5 * s, 0.38 * s, -0.32 * s, 0.48 * s, -0.2 * s, 0.48 * s);
  h.bezierCurveTo(-0.08 * s, 0.48 * s, 0, 0.4 * s, 0, 0.3 * s);
  h.bezierCurveTo(0, 0.4 * s, 0.08 * s, 0.48 * s, 0.2 * s, 0.48 * s);
  h.bezierCurveTo(0.32 * s, 0.48 * s, 0.5 * s, 0.38 * s, 0.5 * s, 0.18 * s);
  h.bezierCurveTo(0.5 * s, -0.05 * s, 0.15 * s, -0.25 * s, 0, -0.4 * s);
  return h;
}

function tailShape(grow: number): Shape {
  const t = new Shape();
  t.moveTo(-0.22 - grow, -0.2);
  t.lineTo(-0.34 - grow * 1.4, -0.52 - grow * 1.6);
  t.lineTo(0.02 + grow, -0.2);
  t.lineTo(-0.22 - grow, -0.2);
  return t;
}

function starShape(size: number): Shape {
  const s = new Shape();
  const inner = size * 0.26;
  for (let i = 0; i < 8; i++) {
    const a = (i / 8) * Math.PI * 2 + Math.PI / 2;
    const r = i % 2 === 0 ? size : inner;
    if (i === 0) s.moveTo(Math.cos(a) * r, Math.sin(a) * r);
    else s.lineTo(Math.cos(a) * r, Math.sin(a) * r);
  }
  return s;
}

// ---------- Shaders ----------

const FRESNEL_VERTEX = `
  varying vec3 vN; varying vec3 vV;
  void main() {
    vec4 mv = modelViewMatrix * vec4(position, 1.0);
    vN = normalize(normalMatrix * normal);
    vV = normalize(-mv.xyz);
    gl_Position = projectionMatrix * mv;
  }`;

interface IconEntry {
  pivot: Group;
  obj: Object3D;
  scale: number;
  target: Vector3;
  phase: number;
  wobble: number;
  twinkle: boolean;
}

/**
 * Builds the scene into a fresh canvas. Throws if WebGL 2 is unavailable (the
 * caller shows a static fallback). Resolves once shaders are compiled, so the
 * first frame renders without a compile stall.
 */
export async function createLogoScene(options: LogoSceneOptions): Promise<LogoSceneHandle> {
  const { reducedMotion } = options;
  const canvas = document.createElement('canvas');
  // failIfMajorPerformanceCaveat: on software rendering (VMs, remote desktops, blocklisted GPUs)
  // the scene would pin the CPU; failing here shows the static logo instead.
  const renderer = new WebGLRenderer({
    canvas,
    antialias: true,
    alpha: true,
    failIfMajorPerformanceCaveat: true,
  });
  renderer.setClearColor(0x000000, 0);
  // Shader error checks read the compile logs back synchronously, which forces every program to
  // finish compiling on the spot (and prints driver warnings). three recommends turning them off
  // in production; keep them while developing.
  renderer.debug.checkShaderErrors = import.meta.env.DEV;
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, options.maxPixelRatio));
  renderer.toneMapping = ACESFilmicToneMapping;
  renderer.toneMappingExposure = 1.15;
  renderer.autoClear = false;

  const scene = new Scene();
  await yieldToMain();
  const envTarget = bakeEnvironment(renderer);
  await yieldToMain();
  scene.environment = envTarget.texture;
  scene.environmentIntensity = DESIGN_ENV_INTENSITY;

  const camera = new PerspectiveCamera(35, 1, 0.1, 200);

  const key = new DirectionalLight(0xffffff, 2.2 * DESIGN_LIGHT_SCALE);
  key.position.set(3, 5, 8);
  const rim = new DirectionalLight(0x6f98ff, 3.0 * DESIGN_LIGHT_SCALE);
  rim.position.set(-5, 3, -6);
  const rim2 = new DirectionalLight(0xa9c1ff, 1.6 * DESIGN_LIGHT_SCALE);
  rim2.position.set(6, -2, -4);
  scene.add(key, rim, rim2, new HemisphereLight(0x9fb6ff, 0x0a0f25, 0.5 * DESIGN_LIGHT_SCALE));

  // ---------- Materials ----------
  const mNavyDeep = new MeshPhysicalMaterial({
    color: NAVY_DEEP,
    metalness: 0.2,
    roughness: 0.4,
    clearcoat: 0.6,
    clearcoatRoughness: 0.2,
    envMapIntensity: 0.6,
  });
  const mWhite = new MeshPhysicalMaterial({
    color: WHITE,
    metalness: 0,
    roughness: 0.18,
    clearcoat: 1,
    clearcoatRoughness: 0.05,
    envMapIntensity: 1.1,
  });
  const mChrome = new MeshPhysicalMaterial({
    color: 0xe9eefb,
    metalness: 1,
    roughness: 0.12,
    clearcoat: 1,
    clearcoatRoughness: 0.05,
    envMapIntensity: 1.8,
  });

  // Everything that flies in during the intro lives in one "hero" group.
  const hero = new Group();
  scene.add(hero);
  const globe = new Group();
  hero.add(globe);

  // ---------- Planet body with a painted top-to-bottom sheen ----------
  const bodyTex = canvasTexture(
    16,
    512,
    (g) => {
      const grd = g.createLinearGradient(0, 0, 0, 512);
      grd.addColorStop(0, '#2d4db8');
      grd.addColorStop(0.4, '#15225a');
      grd.addColorStop(1, '#070c24');
      g.fillStyle = grd;
      g.fillRect(0, 0, 16, 512);
    },
    true,
  );
  globe.add(
    new Mesh(
      new SphereGeometry(R, DETAIL.sphereWidth, DETAIL.sphereHeight),
      new MeshPhysicalMaterial({
        map: bodyTex,
        metalness: 0.1,
        roughness: 0.55,
        clearcoat: 0.8,
        clearcoatRoughness: 0.07,
        envMapIntensity: 0.12,
      }),
    ),
  );

  // ---------- Grid: fine glowing mesh (15°), chrome main lines (30°) ----------
  const uTime = { value: 0 };
  const fineMat = new MeshBasicMaterial({
    color: 0x6f9bff,
    transparent: true,
    opacity: 0.35,
    blending: AdditiveBlending,
    depthWrite: false,
    toneMapped: false,
  });
  for (let lon = 0; lon < 180; lon += 15) {
    if (lon % 30 === 0) continue;
    globe.add(
      new Mesh(
        new TubeGeometry(meridianCurve(lon, R + 0.004), DETAIL.meridian, 0.006, 6, true),
        fineMat,
      ),
    );
  }
  for (let lat = -75; lat <= 75; lat += 15) {
    if (lat % 30 === 0) continue;
    globe.add(
      new Mesh(
        new TubeGeometry(parallelCurve(lat, R + 0.004), DETAIL.parallel, 0.006, 6, true),
        fineMat,
      ),
    );
  }

  const haloMat = new MeshBasicMaterial({
    color: 0x3f74ff,
    transparent: true,
    opacity: 0.2,
    blending: AdditiveBlending,
    depthWrite: false,
    toneMapped: false,
  });
  const mainCurves: { curve: Curve<Vector3>; seg: number; thick: number }[] = [];
  for (let lon = 0; lon < 180; lon += 30) {
    mainCurves.push({ curve: meridianCurve(lon, R + 0.01), seg: DETAIL.meridian, thick: 1 });
  }
  [-60, -30, 0, 30, 60].forEach((lat) =>
    mainCurves.push({
      curve: parallelCurve(lat, R + 0.01),
      seg: DETAIL.parallel,
      thick: lat === 0 ? 1.5 : 1,
    }),
  );
  mainCurves.forEach(({ curve, seg: segments, thick }) => {
    globe.add(new Mesh(new TubeGeometry(curve, segments, 0.011 * thick, 10, true), mChrome));
    globe.add(new Mesh(new TubeGeometry(curve, segments, 0.03 * thick, 8, true), haloMat));
  });

  // ---------- Energy pulses: a bright head with a fading tail running along a line ----------
  function pulseMaterial(
    color: number,
    speed: number,
    offset: number,
    count: number,
  ): ShaderMaterial {
    return new ShaderMaterial({
      uniforms: {
        uTime,
        uColor: { value: new Color(color) },
        uSpeed: { value: speed },
        uOffset: { value: offset },
        uCount: { value: count },
      },
      vertexShader: `
        varying vec2 vUv;
        void main() { vUv = uv; gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0); }`,
      fragmentShader: `
        uniform float uTime, uSpeed, uOffset, uCount; uniform vec3 uColor; varying vec2 vUv;
        void main() {
          float p = fract(vUv.x * uCount - uTime * uSpeed + uOffset);
          float tail = pow(p, 3.5);
          float head = smoothstep(0.965, 1.0, p);
          float edge = 1.0 - abs(vUv.y - 0.5) * 2.0;
          float a = (tail * 0.9 + head * 1.6) * (0.4 + 0.6 * edge);
          gl_FragColor = vec4(mix(uColor, vec3(1.0), head) * a, a);
        }`,
      transparent: true,
      blending: AdditiveBlending,
      depthWrite: false,
    });
  }
  const pulses: [Curve<Vector3>, number, number, number, number, number][] = [
    [meridianCurve(0, R + 0.012), DETAIL.meridian, 0x9fd0ff, 0.12, 0.0, 2],
    [meridianCurve(60, R + 0.012), DETAIL.meridian, 0x9fd0ff, 0.09, 0.5, 2],
    [meridianCurve(120, R + 0.012), DETAIL.meridian, 0xb7a4ff, 0.1, 0.25, 2],
    [parallelCurve(30, R + 0.012), DETAIL.parallel, 0x7fe7ff, 0.08, 0.1, 2],
    [parallelCurve(-30, R + 0.012), DETAIL.parallel, 0x9fd0ff, 0.11, 0.7, 2],
    [parallelCurve(0, R + 0.014), DETAIL.parallel, 0xffffff, 0.06, 0.3, 3],
  ];
  pulses.forEach(([curve, segments, color, speed, off, count]) => {
    globe.add(
      new Mesh(
        new TubeGeometry(curve, segments, 0.028, 8, true),
        pulseMaterial(color, speed, off, count),
      ),
    );
  });

  await yieldToMain();

  // ---------- Network arcs lifting off the surface between two points ----------
  const arcBase = new MeshBasicMaterial({
    color: 0x8fb2ff,
    transparent: true,
    opacity: 0.5,
    blending: AdditiveBlending,
    depthWrite: false,
    toneMapped: false,
  });
  const arcHubs: Vector3[] = [];
  const arcs: [[number, number], [number, number]][] = [
    [
      [45, -20],
      [20, 70],
    ],
    [
      [20, 70],
      [-25, 130],
    ],
    [
      [-10, -60],
      [40, -140],
    ],
    [
      [-40, 20],
      [15, -30],
    ],
    [
      [55, 100],
      [10, 170],
    ],
    [
      [-20, -120],
      [-50, 160],
    ],
    [
      [30, 30],
      [-15, 80],
    ],
    [
      [0, -100],
      [35, -60],
    ],
  ];
  arcs.forEach(([[la1, lo1], [la2, lo2]], i) => {
    const a = spherePoint(la1, lo1, R + 0.02);
    const b = spherePoint(la2, lo2, R + 0.02);
    const lift = 1 + a.distanceTo(b) * 0.38;
    const mid = a
      .clone()
      .add(b)
      .normalize()
      .multiplyScalar(R * lift);
    const curve = new QuadraticBezierCurve3(a, mid, b);
    globe.add(new Mesh(new TubeGeometry(curve, 96, 0.01, 6, false), arcBase));
    globe.add(
      new Mesh(
        new TubeGeometry(curve, 96, 0.03, 8, false),
        pulseMaterial(i % 3 === 0 ? 0xffb8e6 : 0xaee3ff, 0.35 + (i % 4) * 0.08, i * 0.23, 1),
      ),
    );
    arcHubs.push(a, b);
  });

  // ---------- Glowing nodes with breathing halos ----------
  const haloTex = canvasTexture(
    128,
    128,
    (g) => {
      const grd = g.createRadialGradient(64, 64, 0, 64, 64, 64);
      grd.addColorStop(0, 'rgba(255,255,255,1)');
      grd.addColorStop(0.18, 'rgba(190,215,255,0.85)');
      grd.addColorStop(0.45, 'rgba(90,140,255,0.25)');
      grd.addColorStop(1, 'rgba(60,100,255,0)');
      g.fillStyle = grd;
      g.fillRect(0, 0, 128, 128);
    },
    false,
  );
  const nodeCore = new MeshBasicMaterial({ color: 0xffffff, toneMapped: false });
  const nodeGeo = new SphereGeometry(0.028, 12, 8);
  const haloMaterial = new SpriteMaterial({
    map: haloTex,
    color: 0xffffff,
    transparent: true,
    depthWrite: false,
    blending: AdditiveBlending,
    toneMapped: false,
  });
  const halos: { sprite: Sprite; size: number; phase: number }[] = [];
  function addNode(p: Vector3, size: number): void {
    const core = new Mesh(nodeGeo, nodeCore);
    core.position.copy(p);
    globe.add(core);
    const sprite = new Sprite(haloMaterial);
    sprite.position.copy(p).multiplyScalar(1.004);
    sprite.scale.setScalar(size);
    globe.add(sprite);
    halos.push({ sprite, size, phase: Math.random() * Math.PI * 2 });
  }
  [-60, -30, 0, 30, 60].forEach((lat) => {
    for (let lon = 0; lon < 360; lon += 30) addNode(spherePoint(lat, lon, R + 0.012), 0.22);
  });
  arcHubs.forEach((p) => addNode(p, 0.42));

  // ---------- Atmosphere (fresnel glow shell) and planet rim light ----------
  hero.add(
    new Mesh(
      new SphereGeometry(R * 1.32, DETAIL.shellWidth, DETAIL.shellHeight),
      new ShaderMaterial({
        uniforms: { uColor: { value: new Color(GLOW) } },
        vertexShader: FRESNEL_VERTEX,
        fragmentShader: `
          uniform vec3 uColor; varying vec3 vN; varying vec3 vV;
          void main() {
            float d = abs(dot(vN, vV));
            float i = pow(d, 3.0) * 1.1;
            gl_FragColor = vec4(uColor * i, i);
          }`,
        side: BackSide,
        blending: AdditiveBlending,
        transparent: true,
        depthWrite: false,
      }),
    ),
  );
  hero.add(
    new Mesh(
      new SphereGeometry(R * 1.005, DETAIL.shellWidth, DETAIL.shellHeight),
      new ShaderMaterial({
        uniforms: { uColor: { value: new Color(0x8fb0ff) } },
        vertexShader: FRESNEL_VERTEX,
        fragmentShader: `
          uniform vec3 uColor; varying vec3 vN; varying vec3 vV;
          void main() {
            float f = pow(1.0 - max(dot(vN, vV), 0.0), 3.0);
            gl_FragColor = vec4(uColor * f * 1.4, f);
          }`,
        blending: AdditiveBlending,
        transparent: true,
        depthWrite: false,
      }),
    ),
  );

  // ---------- Orbit ring with travelling lights ----------
  const orbitRig = new Group();
  orbitRig.rotation.set(Math.PI / 2 - 0.32, 0.22, 0);
  hero.add(orbitRig);
  orbitRig.add(new Mesh(new TorusGeometry(ORB, 0.014, 16, DETAIL.torus), mChrome));
  orbitRig.add(
    new Mesh(
      new TorusGeometry(ORB, 0.05, 16, DETAIL.torus),
      new MeshBasicMaterial({
        color: GLOW,
        transparent: true,
        opacity: 0.22,
        blending: AdditiveBlending,
        depthWrite: false,
      }),
    ),
  );
  const cometMat = new MeshBasicMaterial({ color: 0xffffff, toneMapped: false });
  const cometGeo = new SphereGeometry(0.06, 16, 12);
  const comets: { mesh: Mesh; angle: number }[] = [];
  for (let i = 0; i < 3; i++) {
    const mesh = new Mesh(cometGeo, cometMat);
    orbitRig.add(mesh);
    comets.push({ mesh, angle: (i / 3) * Math.PI * 2 });
  }

  // ---------- Starfield ----------
  {
    const n = 1400;
    const pos = new Float32Array(n * 3);
    const col = new Float32Array(n * 3);
    for (let i = 0; i < n; i++) {
      const r = 30 + Math.random() * 50;
      const th = Math.random() * Math.PI * 2;
      const ph = Math.acos(2 * Math.random() - 1);
      pos[i * 3] = r * Math.sin(ph) * Math.cos(th);
      pos[i * 3 + 1] = r * Math.cos(ph);
      pos[i * 3 + 2] = r * Math.sin(ph) * Math.sin(th);
      const b = 0.5 + Math.random() * 0.5;
      const blue = Math.random() < 0.3;
      col[i * 3] = blue ? b * 0.7 : b;
      col[i * 3 + 1] = blue ? b * 0.8 : b;
      col[i * 3 + 2] = b;
    }
    const geo = new BufferGeometry();
    geo.setAttribute('position', new BufferAttribute(pos, 3));
    geo.setAttribute('color', new BufferAttribute(col, 3));
    const dot = canvasTexture(
      64,
      64,
      (g) => {
        const grd = g.createRadialGradient(32, 32, 0, 32, 32, 32);
        grd.addColorStop(0, 'rgba(255,255,255,1)');
        grd.addColorStop(0.3, 'rgba(255,255,255,0.6)');
        grd.addColorStop(1, 'rgba(255,255,255,0)');
        g.fillStyle = grd;
        g.fillRect(0, 0, 64, 64);
      },
      false,
    );
    scene.add(
      new Points(
        geo,
        new PointsMaterial({
          size: 0.35,
          map: dot,
          vertexColors: true,
          transparent: true,
          depthWrite: false,
          blending: AdditiveBlending,
          toneMapped: false,
        }),
      ),
    );
  }

  await yieldToMain();

  // ---------- Social icons ----------
  function glossy(color: number, extra: MeshPhysicalMaterialParameters = {}): MeshPhysicalMaterial {
    return new MeshPhysicalMaterial({
      color,
      metalness: 0,
      roughness: 0.5,
      clearcoat: 0.7,
      clearcoatRoughness: 0.08,
      envMapIntensity: 0.2,
      toneMapped: false,
      ...extra,
    });
  }
  const mFacebook = glossy(0x0b4fc0);
  const mFacebookDeep = glossy(0x0a3f9c);
  const mYouTube = glossy(0xb00000);
  const mTelegram = glossy(0x1586c9);
  const mTelegramDeep = glossy(0x0e6aad);
  const mTikTokCyan = glossy(0x25f4ee, { emissive: 0x25f4ee, emissiveIntensity: 0.15 });
  const mTikTokPink = glossy(0xfe2c55, { emissive: 0xfe2c55, emissiveIntensity: 0.15 });
  const mMegaphone = glossy(0xd9480c);

  // Instagram gradient, mapped in the badge's own shape coordinates.
  function instagramMaterial(
    minX: number,
    minY: number,
    w: number,
    h: number,
  ): MeshPhysicalMaterial {
    const tex = canvasTexture(
      256,
      256,
      (g) => {
        const grd = g.createLinearGradient(0, 256, 256, 0);
        grd.addColorStop(0, '#feda75');
        grd.addColorStop(0.25, '#fa7e1e');
        grd.addColorStop(0.5, '#d62976');
        grd.addColorStop(0.75, '#962fbf');
        grd.addColorStop(1, '#4f5bd5');
        g.fillStyle = grd;
        g.fillRect(0, 0, 256, 256);
      },
      true,
    );
    tex.repeat.set(1 / w, 1 / h);
    tex.offset.set(-minX / w, -minY / h);
    return glossy(0xffffff, { map: tex });
  }

  function makeBadge(w: number, h: number, withTail: boolean, bodyMat: Material): Group {
    const g = new Group();
    const outlineShapes = [roundedRect(w + 0.14, h + 0.14, 0.22)];
    const bodyShapes = [roundedRect(w, h, 0.17)];
    if (withTail) {
      outlineShapes.push(tailShape(0.07));
      bodyShapes.push(tailShape(0));
    }
    const outline = new Mesh(extrude(outlineShapes, 0.12), mWhite);
    outline.position.z = -0.05;
    const body = new Mesh(extrude(bodyShapes, 0.2, 0.04), bodyMat);
    g.add(outline, body);
    return g;
  }

  function heartIcon(): Group {
    const g = makeBadge(0.9, 0.62, true, instagramMaterial(-0.45, -0.72, 0.9, 1.03));
    const heart = new Mesh(extrude([heartShape(0.5)], 0.1, 0.03), mWhite);
    heart.position.set(0, 0.03, 0.16);
    g.add(heart);
    return g;
  }

  function playIcon(): Group {
    const g = makeBadge(0.86, 0.62, false, mYouTube);
    const tri = new Shape();
    tri.moveTo(-0.12, -0.17);
    tri.lineTo(0.2, 0);
    tri.lineTo(-0.12, 0.17);
    tri.lineTo(-0.12, -0.17);
    const play = new Mesh(extrude([tri], 0.1, 0.03), mWhite);
    play.position.z = 0.16;
    g.add(play);
    return g;
  }

  function thumbIcon(): Group {
    const g = new Group();
    const cuff = new Mesh(
      extrude([roundedRect(0.26, 0.56, 0.06, -0.36, -0.06)], 0.22, 0.03),
      mFacebook,
    );
    const cuffOutline = new Mesh(
      extrude([roundedRect(0.38, 0.68, 0.1, -0.36, -0.06)], 0.12),
      mWhite,
    );
    cuffOutline.position.z = -0.05;
    const dot = new Mesh(new SphereGeometry(0.035, 16, 12), mWhite);
    dot.position.set(-0.36, -0.24, 0.15);
    const palm = roundedRect(0.44, 0.5, 0.12, 0.02, -0.08);
    const thumb = transformShape(roundedRect(0.17, 0.42, 0.085), -0.3, -0.02, 0.3);
    const hand = new Mesh(extrude([palm, thumb], 0.22, 0.035), mWhite);
    const handOutline = new Mesh(
      extrude(
        [
          roundedRect(0.56, 0.62, 0.17, 0.02, -0.08),
          transformShape(roundedRect(0.29, 0.54, 0.14), -0.3, -0.02, 0.3),
        ],
        0.12,
      ),
      mFacebookDeep,
    );
    handOutline.position.z = -0.05;
    g.add(cuffOutline, handOutline, cuff, hand, dot);
    const lineGeo = new BoxGeometry(0.16, 0.024, 0.02);
    [0.06, -0.06, -0.18].forEach((y) => {
      const line = new Mesh(lineGeo, mFacebook);
      line.position.set(0.16, y, 0.15);
      g.add(line);
    });
    return g;
  }

  function megaphoneIcon(): Group {
    const g = new Group();
    const hornMat = mWhite.clone();
    hornMat.side = DoubleSide;
    const horn = new Mesh(new CylinderGeometry(0.4, 0.14, 0.62, 56, 1, true), hornMat);
    horn.rotation.z = -Math.PI / 2;
    const inner = new Mesh(
      new CylinderGeometry(0.37, 0.12, 0.6, 56, 1, true),
      new MeshPhysicalMaterial({
        color: 0xff9a66,
        roughness: 0.45,
        envMapIntensity: 0.3,
        clearcoat: 1,
        side: BackSide,
      }),
    );
    inner.rotation.z = -Math.PI / 2;
    const lip = new Mesh(new TorusGeometry(0.4, 0.05, 20, 56), mMegaphone);
    lip.rotation.y = Math.PI / 2;
    lip.position.x = 0.31;
    const body = new Mesh(new CylinderGeometry(0.16, 0.16, 0.34, 40), mMegaphone);
    body.rotation.z = Math.PI / 2;
    body.position.x = -0.46;
    const cap = new Mesh(
      new SphereGeometry(0.16, 32, 16, 0, Math.PI * 2, 0, Math.PI / 2),
      mMegaphone,
    );
    cap.rotation.z = Math.PI / 2;
    cap.position.x = -0.63;
    const handle = new Mesh(new BoxGeometry(0.11, 0.34, 0.12), mMegaphone);
    handle.position.set(-0.4, -0.25, 0);
    handle.rotation.z = 0.2;
    g.add(horn, inner, lip, body, cap, handle);
    g.rotation.z = 0.45;
    return g;
  }

  function planeIcon(): Group {
    const g = new Group();
    const nose = [0.55, 0.1, 0];
    const tailTop = [-0.45, 0.42, 0.08];
    const mid = [-0.3, 0.02, -0.14];
    const tailLow = [-0.45, -0.32, 0.08];
    function tri(a: number[], b: number[], c: number[], mat: Material): Mesh {
      const geo = new BufferGeometry();
      geo.setAttribute('position', new Float32BufferAttribute([...a, ...b, ...c], 3));
      geo.computeVertexNormals();
      return new Mesh(geo, mat);
    }
    const mW = mTelegram.clone();
    mW.side = DoubleSide;
    const mN = mTelegramDeep.clone();
    mN.side = DoubleSide;
    g.add(tri(nose, tailTop, mid, mW));
    g.add(tri(nose, mid, tailLow, mN));
    g.rotation.z = 0.35;
    return g;
  }

  function hashLayer(mat: Material): Group {
    const g = new Group();
    const bars: [number, number, number, number, number][] = [
      [-0.08, 0, 0.08, 0.52, -0.2],
      [0.1, 0, 0.08, 0.52, -0.2],
      [0, 0.09, 0.46, 0.08, 0],
      [0, -0.09, 0.46, 0.08, 0],
    ];
    bars.forEach(([x, y, w, h, rot]) => {
      const b = new Mesh(extrude([roundedRect(w, h, 0.03)], 0.08, 0.02), mat);
      b.position.set(x, y, 0);
      b.rotation.z = rot;
      g.add(b);
    });
    return g;
  }

  function hashIcon(): Group {
    const g = new Group();
    const cyan = hashLayer(mTikTokCyan);
    cyan.position.set(-0.035, 0.03, -0.06);
    const pink = hashLayer(mTikTokPink);
    pink.position.set(0.035, -0.03, -0.06);
    g.add(cyan, pink, hashLayer(mWhite));
    return g;
  }

  const sparkleMat = new MeshBasicMaterial({ color: 0xffffff, toneMapped: false });
  function sparkle(size: number): Mesh {
    return new Mesh(extrude([starShape(size)], 0.04, 0.01), sparkleMat);
  }

  const orbit = new Group();
  hero.add(orbit);
  const icons: IconEntry[] = [];
  function place(
    obj: Object3D,
    x: number,
    y: number,
    z: number,
    scale: number,
    wobble: number,
    twinkle = false,
  ): void {
    const pivot = new Group();
    pivot.position.set(x, y, z);
    obj.scale.setScalar(scale);
    pivot.add(obj);
    orbit.add(pivot);
    icons.push({
      pivot,
      obj,
      scale,
      target: new Vector3(x, y, z),
      phase: Math.random() * Math.PI * 2,
      wobble,
      twinkle,
    });
  }
  place(heartIcon(), -1.3, 1.6, 1.1, 1.0, 0.35);
  place(thumbIcon(), 1.5, 1.5, 1.0, 1.1, 0.35);
  place(playIcon(), -2.2, -0.6, 0.8, 1.0, 0.35);
  place(megaphoneIcon(), 1.8, -1.35, 1.0, 1.05, 0.5);
  place(planeIcon(), 2.3, 0.25, 0.4, 0.9, 0.6);
  place(hashIcon(), -2.0, 1.1, -0.2, 0.9, 0.4);
  const sparkles: [number, number, number, number][] = [
    [2.1, 1.0, 0.2, 0.18],
    [2.25, 0.62, -0.3, 0.11],
    [-2.4, 0.2, 0.3, 0.15],
    [-2.25, -0.1, -0.4, 0.1],
    [-0.4, -2.15, -1.2, 0.12],
    [0.6, 2.15, -1.0, 0.1],
    [-1.2, -1.9, 0.9, 0.09],
    [1.1, 2.0, 0.8, 0.08],
  ];
  sparkles.forEach(([x, y, z, s]) => place(sparkle(s), x, y, z, 1, 0.8, true));

  await yieldToMain();

  // ---------- 3D lettering ----------
  // Drawn from its own scene after the main one (depth cleared), so icons flying
  // around the planet pass behind the letters instead of through them.
  const textScene = new Scene();
  textScene.environment = scene.environment;
  textScene.add(
    key.clone(),
    rim.clone(),
    rim2.clone(),
    new HemisphereLight(0x9fb6ff, 0x0a0f25, 0.5 * DESIGN_LIGHT_SCALE),
  );
  const textRig = new Group(); // follows the camera with a soft lag
  textScene.add(textRig);
  const textGroup = new Group();
  textRig.add(textGroup);
  const sideNavy = new MeshPhysicalMaterial({
    color: 0x223472,
    metalness: 0.2,
    roughness: 0.3,
    clearcoat: 1,
    clearcoatRoughness: 0.1,
  });

  function word(fontData: FontData, text: string, size: number, depth: number, y: number): Group {
    const shapes = new Font(fontData).generateShapes(text, size);
    const make = (d: number, bevelT: number, bevelS: number, mats: Material | Material[]): Mesh => {
      const geo = new ExtrudeGeometry(shapes, {
        depth: d,
        bevelEnabled: true,
        bevelThickness: bevelT,
        bevelSize: bevelS,
        bevelSegments: DETAIL.textBevel,
        curveSegments: DETAIL.textCurve,
      });
      geo.computeBoundingBox();
      const bb = geo.boundingBox;
      if (bb) geo.translate(-(bb.max.x + bb.min.x) / 2, -(bb.max.y + bb.min.y) / 2, -d / 2);
      return new Mesh(geo, mats);
    };
    const g = new Group();
    const front = make(depth, 0.03, 0.02, [mWhite, sideNavy]);
    const outline = make(depth * 0.9, 0.02, size * 0.075, mNavyDeep);
    outline.position.z = -depth * 0.55;
    const sticker = make(depth * 0.6, 0.02, size * 0.13, mWhite);
    sticker.position.z = -depth * 1.05;
    g.add(sticker, outline, front);
    g.position.y = y;
    return g;
  }
  textGroup.add(word(FONT_SMM, 'SMM', 1.05, 0.28, 0.5));
  textGroup.add(word(FONT_WORLD, 'World', 0.95, 0.22, -0.55));
  {
    // Swoosh under "World"
    const curve = new QuadraticBezierCurve3(
      new Vector3(-0.85, -1.12, 0.05),
      new Vector3(0, -1.32, 0.12),
      new Vector3(0.95, -1.08, 0.05),
    );
    const swoosh = new Mesh(new TubeGeometry(curve, 64, 0.045, 12), mWhite);
    const swooshOutline = new Mesh(new TubeGeometry(curve, 64, 0.085, 12), mNavyDeep);
    swooshOutline.position.z = -0.06;
    textGroup.add(swooshOutline, swoosh);
  }

  await yieldToMain();

  // ---------- Intro: shockwave ring and flash when the planet arrives ----------
  const shock = new Mesh(
    new RingGeometry(0.975, 1.0, 160),
    new MeshBasicMaterial({
      color: 0x9fc0ff,
      transparent: true,
      opacity: 0,
      blending: AdditiveBlending,
      depthWrite: false,
      toneMapped: false,
      side: DoubleSide,
    }),
  );
  scene.add(shock);
  const flash = new Sprite(
    new SpriteMaterial({
      map: haloTex,
      color: 0x8fb0ff,
      transparent: true,
      opacity: 0,
      blending: AdditiveBlending,
      depthWrite: false,
      toneMapped: false,
    }),
  );
  scene.add(flash);

  // ---------- Controls ----------
  const controls = options.interactive ? new OrbitControls(camera, canvas) : null;
  if (controls) {
    controls.dampingFactor = 0.07;
    controls.enablePan = false;
    // Zoom stays off: a wheel over the hero must scroll the page, not the camera.
    controls.enableZoom = false;
    // OrbitControls sets touch-action: none, which would trap page scroll on touch-screen
    // laptops; pan-y lets a vertical swipe scroll the page while a sideways drag still rotates.
    canvas.style.touchAction = 'pan-y';
    canvas.style.cursor = 'grab';
  }
  const target = controls ? controls.target : ORIGIN;

  // ---------- Timeline ----------
  // The intro plays from introTime 0 to INTRO_END: the planet flies in from deep
  // space with a spin, the camera swings into place, a shockwave rings out on
  // arrival, the icons burst out of the planet, then the lettering pops in.
  let introTime = options.playIntro && !reducedMotion ? 0 : INTRO_END;
  let introDone = false;
  camera.position.copy(introTime < INTRO_END ? START_CAM : HOME);
  camera.lookAt(target);
  if (controls) controls.enabled = false;

  // Grabbing the logo to rotate it skips the rest of the intro. Only where it can be rotated
  // (mouse): on a touch screen a pointerdown is usually the start of a page scroll.
  function onPointerDown(): void {
    if (!introDone) introTime = INTRO_END;
    canvas.style.cursor = 'grabbing';
  }
  function onPointerUp(): void {
    canvas.style.cursor = 'grab';
  }
  if (controls) {
    canvas.addEventListener('pointerdown', onPointerDown, { passive: true });
    canvas.addEventListener('pointerup', onPointerUp, { passive: true });
    canvas.addEventListener('pointercancel', onPointerUp, { passive: true });
  }

  function applyIntro(it: number): void {
    const fly = easeOutExpo(seg(it, 0, 1.7));
    hero.position.set(0, (1 - fly) * 4, (1 - fly) * -45);
    hero.scale.setScalar(0.15 + 0.85 * fly);
    hero.rotation.y = (1 - fly) * 5.5;
    hero.rotation.x = (1 - fly) * 0.7;

    if (!introDone) camera.position.lerpVectors(START_CAM, HOME, easeInOutCubic(seg(it, 0, 2.7)));
    // OrbitControls aims the camera at the planet on every update; without them, do it here.
    if (!controls) camera.lookAt(target);

    orbitRig.scale.setScalar(Math.max(0.0001, easeOutBack(seg(it, 1.2, 0.9))));

    const sw = seg(it, 1.3, 1.1);
    shock.visible = sw > 0 && sw < 1;
    (shock.material as MeshBasicMaterial).opacity = Math.pow(1 - sw, 1.5) * 0.8;
    shock.scale.setScalar(1.7 + easeOutCubic(sw) * 3.3);
    shock.quaternion.copy(camera.quaternion);
    const fl = seg(it, 1.25, 0.9);
    flash.visible = fl > 0 && fl < 1;
    flash.material.opacity = Math.sin(fl * Math.PI) * 0.9;
    flash.scale.setScalar(4 + fl * 6);

    const tx = seg(it, 2.2, 0.9);
    textGroup.scale.setScalar(Math.max(0.0001, easeOutBack(tx)));
    textGroup.position.y = (1 - easeOutCubic(tx)) * 1.4;

    if (it >= INTRO_END && !introDone) {
      introDone = true;
      if (controls) controls.enabled = true;
    }
  }

  // ---------- Frame ----------
  const dir = new Vector3();
  let elapsed = 0;
  let lastNow = 0;
  let firstFrame = true;
  let active = false;
  let disposed = false;
  let stillFrame = 0; // pending requestAnimationFrame id for an on-demand redraw

  /** Ambient motion runs unless the viewer prefers reduced motion. */
  const animating = !reducedMotion;

  /**
   * Advances the scene by the time since the last frame and draws it. On-demand frames (resize,
   * rotation under reduced motion) draw the current state without advancing time.
   */
  function frame(now: number, onDemand: boolean): void {
    if (!onDemand && lastNow && now - lastNow < MIN_FRAME_MS) return;
    const dt = !onDemand && lastNow ? Math.min((now - lastNow) / 1000, MAX_FRAME_SECONDS) : 0;
    if (!onDemand) lastNow = now;
    elapsed += dt;
    const t = elapsed;

    uTime.value = reducedMotion ? 0 : t;
    halos.forEach((h) => {
      const k = reducedMotion ? 1 : 0.75 + 0.35 * Math.sin(t * 2.2 + h.phase);
      h.sprite.scale.setScalar(h.size * k);
    });
    if (!reducedMotion) {
      globe.rotation.y += dt * 0.25;
      orbit.rotation.y += dt * 0.16;
    }
    comets.forEach((c, i) => {
      c.angle += dt * (reducedMotion ? 0 : 0.6);
      c.mesh.position.set(Math.cos(c.angle) * ORB, Math.sin(c.angle) * ORB, 0);
      c.mesh.scale.setScalar(reducedMotion ? 1 : 0.8 + 0.4 * Math.sin(t * 3 + i));
    });

    introTime += dt;
    const it = introTime;
    applyIntro(it);

    icons.forEach((ic, i) => {
      // Icons burst out of the planet one after another
      const e = seg(it, 1.45 + i * 0.07, 0.85);
      ic.pivot.position.copy(ic.target).multiplyScalar(easeOutCubic(e));
      let s = ic.scale;
      if (!reducedMotion) {
        ic.obj.rotation.y = Math.sin(t * 0.9 + ic.phase) * ic.wobble + (1 - e) * 4;
        ic.pivot.position.y += Math.sin(t * 1.2 + ic.phase) * 0.06;
        if (ic.twinkle) s *= 0.7 + 0.45 * Math.abs(Math.sin(t * 1.6 + ic.phase));
      }
      ic.obj.scale.setScalar(Math.max(0.0001, s * easeOutBack(e)));
      ic.pivot.lookAt(camera.position);
    });

    // The lettering floats in front of the planet and turns to face the viewer with a slight
    // lag, so its depth shows while the view rotates. Without a running loop there is no next
    // frame to finish the lag, so it snaps.
    dir.copy(camera.position).sub(target).normalize();
    textRig.position.copy(target).addScaledVector(dir, R + 0.7);
    if (firstFrame || !animating) {
      textRig.quaternion.copy(camera.quaternion);
      firstFrame = false;
    } else {
      textRig.quaternion.slerp(camera.quaternion, 0.06);
    }
    if (!reducedMotion) {
      textGroup.rotation.y = Math.sin(t * 0.6) * 0.12;
      textGroup.rotation.x = Math.sin(t * 0.45) * 0.05;
    }

    controls?.update();
    renderer.clear();
    renderer.render(scene, camera);
    renderer.clearDepth();
    renderer.render(textScene, camera);
  }

  const loopFrame = (now: number): void => frame(now, false);

  function renderStill(): void {
    if (!disposed) frame(performance.now(), true);
  }

  function requestStill(): void {
    if (stillFrame || disposed) return;
    stillFrame = requestAnimationFrame(() => {
      stillFrame = 0;
      renderStill();
    });
  }

  /** Runs the frame loop only while on screen and animating; otherwise draws on demand. */
  function syncLoop(): void {
    const run = active && !disposed && animating;
    // Damping only settles inside a running loop; on-demand rotation must stop where released.
    if (controls) controls.enableDamping = run;
    if (run) {
      lastNow = 0; // resume without a jump in the timeline
      renderer.setAnimationLoop(loopFrame);
    } else {
      renderer.setAnimationLoop(null);
    }
  }
  controls?.addEventListener('change', () => {
    if (active && !animating) requestStill();
  });

  // Pre-compile every program (in parallel where the driver supports it) so the
  // first visible frame doesn't stall on shader compilation.
  applyIntro(introTime);
  await precompile(renderer, scene, camera);
  await precompile(renderer, textScene, camera);

  function onContextLost(): void {
    if (disposed) return;
    active = false;
    renderer.setAnimationLoop(null);
    handle.onContextLost?.();
  }
  canvas.addEventListener('webglcontextlost', onContextLost);

  const handle: LogoSceneHandle = {
    canvas,
    onContextLost: null,
    setSize(width: number, height: number): void {
      if (disposed || width <= 0 || height <= 0) return;
      // Re-read the pixel ratio on every resize: browser zoom changes it.
      renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, options.maxPixelRatio));
      renderer.setSize(width, height, false);
      camera.aspect = width / height;
      camera.fov = camera.aspect < 0.8 ? 54 : 35;
      camera.updateProjectionMatrix();
      // Resizing clears the drawing buffer: redraw now rather than flash blank until the next
      // tick.
      renderStill();
    },
    setActive(next: boolean): void {
      if (disposed || next === active) return;
      active = next;
      syncLoop();
      if (next && !animating) renderStill();
    },
    dispose(): void {
      if (disposed) return;
      disposed = true;
      renderer.setAnimationLoop(null);
      cancelAnimationFrame(stillFrame);
      canvas.removeEventListener('pointerdown', onPointerDown);
      canvas.removeEventListener('pointerup', onPointerUp);
      canvas.removeEventListener('pointercancel', onPointerUp);
      canvas.removeEventListener('webglcontextlost', onContextLost);
      controls?.dispose();
      disposeGraph([scene, textScene]);
      envTarget.dispose();
      renderer.dispose();
      // Release the context now rather than whenever GC gets to it — browsers cap
      // live WebGL contexts, and SPA navigation can mount this repeatedly.
      renderer.forceContextLoss();
    },
  };
  return handle;
}

/**
 * Bakes the studio environment into a prefiltered (PMREM) map for the glossy materials.
 *
 * Cost note: fromScene() compiles PMREM's GGX prefilter shader. That takes a few ms on most GPUs,
 * but on Windows ANGLE -> D3D11 unrolls its 256-sample loop and compiles at first draw, and the main
 * thread stalls on it for ~0.5 s (the scene's first build blocks ~0.8 s in all). Chrome caches the
 * compiled programs, so it is paid once: repeat visits block ~0.1 s. Shipping the baked map as an
 * asset instead would mean downloading 768x1024 RGBA16F (6 MB raw) on every first visit. Logo3D
 * therefore starts the scene when the browser is idle and keeps one scene alive across theme
 * switches.
 */
function bakeEnvironment(renderer: WebGLRenderer): WebGLRenderTarget {
  const pmrem = new PMREMGenerator(renderer);
  const room = designRoomEnvironment();
  try {
    return pmrem.fromScene(room, 0.03);
  } finally {
    room.dispose();
    pmrem.dispose();
  }
}

/**
 * The studio environment the design was lit with (three r147's RoomEnvironment). Since r155 the
 * room sits 3.5 lower and its main light uses physical units with inverse-square falloff, which
 * brightens the walls 1.4-3.4x. Moving the room back and giving the light linear falloff (decay 1,
 * intensity 66) approximates r147's legacy falloff within ~20%; the area lights are unchanged
 * between versions. DESIGN_ENV_INTENSITY was calibrated on top of this environment.
 */
function designRoomEnvironment(): RoomEnvironment {
  const room = new RoomEnvironment();
  room.position.y = 0;
  room.traverse((node) => {
    if (node instanceof PointLight) {
      node.intensity = 66;
      node.decay = 1;
    }
  });
  return room;
}

/** Ends the current task so input and paint aren't held up by the whole scene build. */
function yieldToMain(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

/**
 * compileAsync only avoids blocking with KHR_parallel_shader_compile; without it (e.g. Firefox)
 * three logs a warning and the compile blocks anyway, so compile directly instead.
 */
async function precompile(
  renderer: WebGLRenderer,
  scene: Scene,
  camera: PerspectiveCamera,
): Promise<void> {
  if (renderer.extensions.has('KHR_parallel_shader_compile')) {
    await renderer.compileAsync(scene, camera);
  } else {
    renderer.compile(scene, camera);
  }
}

/** Frees every geometry, material and texture reachable from the given roots. */
function disposeGraph(roots: Object3D[]): void {
  const geometries = new Set<BufferGeometry>();
  const materials = new Set<Material>();
  const textures = new Set<Texture>();
  roots.forEach((root) =>
    root.traverse((node) => {
      if (node instanceof Mesh || node instanceof Points || node instanceof Sprite) {
        geometries.add(node.geometry);
        const mats: Material[] = Array.isArray(node.material) ? node.material : [node.material];
        mats.forEach((m) => materials.add(m));
      }
    }),
  );
  materials.forEach((m) => {
    Object.values(m).forEach((value: unknown) => {
      if (value && typeof value === 'object' && (value as Texture).isTexture)
        textures.add(value as Texture);
    });
    m.dispose();
  });
  geometries.forEach((g) => g.dispose());
  textures.forEach((t) => t.dispose());
}
