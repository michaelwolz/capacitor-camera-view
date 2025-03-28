import { registerPlugin } from '@capacitor/core';

import type { CameraViewPlugin } from './definitions';

/**
 * The main Capacitor Camera View plugin instance.
 */
const CameraView = registerPlugin<CameraViewPlugin>('CameraView', {
  web: () => import('./web').then((m) => new m.CameraViewWeb()),
});

export * from './definitions';
export { CameraView };
