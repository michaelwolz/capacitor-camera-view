import { WebPlugin } from '@capacitor/core';

import type { CameraViewPlugin, FlashMode } from './definitions';

export class CameraViewWeb extends WebPlugin implements CameraViewPlugin {
  /** @inheritdoc */
  start(): Promise<void> {
    throw new Error('Method not implemented.');
  }

  /** @inheritdoc */
  stop(): Promise<void> {
    throw new Error('Method not implemented.');
  }

  /** @inheritdoc */
  isRunning(): Promise<boolean> {
    throw new Error('Method not implemented.');
  }

  /** @inheritdoc */
  capture(): Promise<string> {
    throw new Error('Method not implemented.');
  }

  /** @inheritdoc */
  switchCamera(): Promise<void> {
    throw new Error('Method not implemented.');
  }

  /** @inheritdoc */
  getZoom(): Promise<{ min: number; max: number; current: number }> {
    throw new Error('Method not implemented.');
  }

  /** @inheritdoc */
  setZoom(options: { level: number }): Promise<void> {
    throw new Error('Method not implemented.');
  }

  /** @inheritdoc */
  getFlashMode(): Promise<FlashMode> {
    throw new Error('Method not implemented.');
  }

  /** @inheritdoc */
  getSupportedFlashModes(): Promise<FlashMode[]> {
    throw new Error('Method not implemented.');
  }

  /** @inheritdoc */
  setFlashMode(options: { mode: FlashMode }): Promise<void> {
    throw new Error('Method not implemented.');
  }
}
