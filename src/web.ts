import { WebPlugin } from '@capacitor/core';

import type { CameraDevice, CameraViewPlugin, FlashMode, PermissionStatus } from './definitions';

export class CameraNotStartedError extends Error {
  constructor() {
    super('Camera view is not started.');
    this.name = 'CameraNotStartedError';
  }
}

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
  isRunning(): Promise<{ isRunning: boolean }> {
    throw new Error('Method not implemented.');
  }

  /** @inheritdoc */
  capture(): Promise<{ photo: string }> {
    throw new Error('Method not implemented.');
  }

  /** @inheritdoc */
  getAvailableDevices(): Promise<{ devices: Array<CameraDevice> }> {
    throw new Error('Method not implemented.');
  }

  /** @inheritdoc */
  flipCamera(): Promise<void> {
    throw new Error('Method not implemented.');
  }

  /** @inheritdoc */
  getZoom(): Promise<{ min: number; max: number; current: number }> {
    throw new Error('Method not implemented.');
  }

  /** @inheritdoc */
  setZoom(options: { level: number }): Promise<void> {
    console.log(options);
    throw new Error('Method not implemented.');
  }

  /** @inheritdoc */
  getFlashMode(): Promise<{ flashMode: FlashMode }> {
    throw new Error('Method not implemented.');
  }

  /** @inheritdoc */
  getSupportedFlashModes(): Promise<{ flashModes: FlashMode[] }> {
    throw new Error('Method not implemented.');
  }

  /** @inheritdoc */
  setFlashMode(options: { mode: FlashMode }): Promise<void> {
    console.log(options);
    throw new Error('Method not implemented.');
  }

  /** @inheritdoc */
  checkPermissions(): Promise<{ camera: PermissionStatus }> {
    throw new Error('Method not implemented.');
  }

  /** @inheritdoc */
  requestPermissions(): Promise<{ camera: PermissionStatus }> {
    throw new Error('Method not implemented.');
  }
}
