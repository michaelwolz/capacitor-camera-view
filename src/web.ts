import { WebPlugin } from '@capacitor/core';

import type {
  CameraViewPlugin,
  CaptureResponse,
  FlashMode,
  GetAvailableDevicesResponse,
  GetFlashModeResponse,
  GetSupportedFlashModesResponse,
  GetZoomResponse,
  IsRunningResponse,
  PermissionStatus,
} from './definitions';

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
  isRunning(): Promise<IsRunningResponse> {
    throw new Error('Method not implemented.');
  }

  /** @inheritdoc */
  capture(): Promise<CaptureResponse> {
    throw new Error('Method not implemented.');
  }

  /** @inheritdoc */
  getAvailableDevices(): Promise<GetAvailableDevicesResponse> {
    throw new Error('Method not implemented.');
  }

  /** @inheritdoc */
  flipCamera(): Promise<void> {
    throw new Error('Method not implemented.');
  }

  /** @inheritdoc */
  getZoom(): Promise<GetZoomResponse> {
    throw new Error('Method not implemented.');
  }

  /** @inheritdoc */
  setZoom(options: { level: number }): Promise<void> {
    console.log(options);
    throw new Error('Method not implemented.');
  }

  /** @inheritdoc */
  getFlashMode(): Promise<GetFlashModeResponse> {
    throw new Error('Method not implemented.');
  }

  /** @inheritdoc */
  getSupportedFlashModes(): Promise<GetSupportedFlashModesResponse> {
    throw new Error('Method not implemented.');
  }

  /** @inheritdoc */
  setFlashMode(options: { mode: FlashMode }): Promise<void> {
    console.log(options);
    throw new Error('Method not implemented.');
  }

  /** @inheritdoc */
  checkPermissions(): Promise<PermissionStatus> {
    throw new Error('Method not implemented.');
  }

  /** @inheritdoc */
  requestPermissions(): Promise<PermissionStatus> {
    throw new Error('Method not implemented.');
  }
}
