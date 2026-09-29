// Only the lightweight React wrapper is exported: the scene module (three.js) is
// loaded on demand by Logo3D itself and must never be imported statically.
export { Logo3D } from './Logo3D';
export type { Logo3DProps } from './Logo3D';
