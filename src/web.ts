import { WebPlugin } from '@capacitor/core';

import type { CameraViewPlugin } from './definitions';

export class CameraViewWeb extends WebPlugin implements CameraViewPlugin {
  async echo(options: { value: string }): Promise<{ value: string }> {
    console.log('ECHO', options);
    return options;
  }
}
