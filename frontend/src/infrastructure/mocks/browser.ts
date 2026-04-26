// Browser-side MSW worker singleton.
//
// Imported dynamically from `src/main.tsx` only when
// `VITE_USE_MSW === 'true'`, so the `msw/browser` chunk and every
// transitive handler ship only with preview builds. Production
// builds never load this module — Vite's static analysis sees the
// dynamic `import()` is dead code under a falsy literal env value
// and tree-shakes it out. See ADR-0007 §5 for the posture and
// `vite.config.ts` for the virtual-module fixture loader.

import { setupWorker } from 'msw/browser';

import { handlers } from './handlers';

export const worker = setupWorker(...handlers);
