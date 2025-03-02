import { registerPlugin } from '@capacitor/core';

import type { CameraViewPlugin } from './definitions';

const CameraView = registerPlugin<CameraViewPlugin>('CameraView', {
  web: () => import('./web').then((m) => new m.CameraViewWeb()),
});

export * from './definitions';
export { CameraView };
