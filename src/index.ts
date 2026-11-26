import { registerPlugin } from '@capacitor/core';

import type { CameraViewPlugin } from './definitions.js';

/**
 * The main Capacitor Camera View plugin instance.
 */
const CameraView = registerPlugin<CameraViewPlugin>('CameraView', {
  web: () => import('./web.js').then((m) => new m.CameraViewWeb()),
});

export * from './definitions.js';
export { CameraView };
