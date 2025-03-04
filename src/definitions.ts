import type { PermissionState } from '@capacitor/core';

export class CameraNotStartedError extends Error {
  constructor() {
    super('Camera view is not started.');
    this.name = 'CameraNotStartedError';
  }
}

export interface PermissionStatus {
  camera: PermissionState;
}

export enum CameraPosition {
  FRONT = 'front',
  BACK = 'back',
}

export enum FlashMode {
  OFF = 'off',
  ON = 'on',
  AUTO = 'auto',
}

type Range<N extends number, Result extends number[] = []> = Result['length'] extends N
  ? Result[number] | N
  : Range<N, [...Result, Result['length']]>;

export interface CameraViewPlugin {
  /**
   * Start the camera view
   */
  start(options: { cameraPosition: CameraPosition }): Promise<void>;

  /**
   * Stop the camera view
   */
  stop(): Promise<void>;

  /**
   * Check if the camera view is running.
   */
  isRunning(): Promise<boolean>;

  /**
   * Capture a photo.
   *
   * @note
   * Camera view must be started before calling this method.
   *
   * @param options.quality - The JPEG quality of the captured photo on a scale of 0-100.
   *
   * @returns A base64 encoded string of the captured photo.
   * @throws {CameraNotStartedError} If camera view is not started.
   */
  capture(options: { quality: Range<100> }): Promise<string>;

  /**
   * Switches between front and back camera.
   *
   * @note
   * Camera view must be started before calling this method.
   *
   * @throws {CameraNotStartedError} If camera view is not started.
   */
  switchCamera(): Promise<void>;

  /**
   * Get zoom levels options and current zoom level.
   *
   * @note
   * Camera view must be started before calling this method.
   *
   * @returns An object containing min, max and current zoom levels.
   * @throws {CameraNotStartedError} If camera view is not started.
   */
  getZoom(): Promise<{ min: number; max: number; current: number }>;

  /**
   * Set zoom level.
   *
   * @note
   * Camera view must be started before calling this method.
   *
   * @param options.level - The zoom level to set.
   * @throws {CameraNotStartedError} If camera view is not started.
   */
  setZoom(options: { level: number }): Promise<void>;

  /**
   * Get flash mode.
   *
   * @note
   * Camera view must be started before calling this method.
   *
   * @returns The current flash mode.
   * @throws {CameraNotStartedError} If camera view is not started.
   */
  getFlashMode(): Promise<FlashMode>;

  /**
   * Get supported flash modes.
   *
   * @note
   * Camera view must be started before calling this method.
   *
   * @returns An array of supported flash modes.
   * @throws {CameraNotStartedError} If camera view is not started.
   */
  getSupportedFlashModes(): Promise<FlashMode[]>;

  /**
   * Set flash mode.
   *
   * @note
   * Camera view must be started before calling this method.
   *
   * @param options.mode - The flash mode to set.
   * @throws {CameraNotStartedError} If camera view is not started.
   */
  setFlashMode(options: { mode: FlashMode }): Promise<void>;

  /**
   * Check camera permission.
   */
  checkPermissions(): Promise<PermissionStatus>;

  /**
   * Request camera permission.
   */
  requestPermissions(): Promise<PermissionStatus>;
}
