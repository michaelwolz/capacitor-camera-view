import type { PermissionState } from '@capacitor/core';

/**
 * Permission status for the camera.
 */
export interface PermissionStatus {
  camera: PermissionState;
}

/**
 * Position options for the camera session.
 */
export enum CameraPosition {
  FRONT = 'front',
  BACK = 'back',
}

/**
 * Flash mode options for the camera session.
 */
export enum FlashMode {
  OFF = 'off',
  ON = 'on',
  AUTO = 'auto',
}

export interface CameraDevice {
  id: string;
  name: string;
  position: CameraPosition;
}

export type CameraPreset = 'low' | 'medium' | 'high' | 'photo';

/**
 * Configuration for the camera session.
 */
export interface CameraSessionConfiguration {
  /** Position of the camera (front or back) */
  cameraPosition?: CameraPosition;

  /** The device ID of the camera to use */
  deviceId?: string;

  /** The preset to use for the camera session */
  preset?: CameraPreset;

  /** Whether to use the triple camera if available (iOS only) */
  useTripleCameraIfAvailable?: boolean;

  /** The initial zoom factor to use for the camera session */
  zoomFactor?: number;
}

export interface CameraViewPlugin {
  /**
   * Start the camera view
   */
  start(options?: CameraSessionConfiguration): Promise<void>;

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
   */
  capture(options: { quality: number }): Promise<string>;

  /**
   * Switches between front and back camera.
   *
   * @note
   * Camera view must be started before calling this method.
   */
  flipCamera(): Promise<void>;

  /**
   * Get available devices for taking photos.
   *
   * @returns An array of available capture devices
   */
  getAvailableDevices(): Promise<Array<CameraDevice>>;

  /**
   * Get zoom levels options and current zoom level.
   *
   * @note
   * Camera view must be started before calling this method.
   *
   * @returns An object containing min, max and current zoom levels.
   */
  getZoom(): Promise<{ min: number; max: number; current: number }>;

  /**
   * Set zoom level.
   *
   * @note
   * Camera view must be started before calling this method.
   *
   * @param options.level - The zoom level to set.
   * @param options.ramp - Whether to animate the zoom level change, defaults to true (iOS / Android only)
   */
  setZoom(options: { level: number; ramp?: boolean }): Promise<void>;

  /**
   * Get flash mode.
   *
   * @note
   * Camera view must be started before calling this method.
   *
   * @returns The current flash mode.
   */
  getFlashMode(): Promise<FlashMode>;

  /**
   * Get supported flash modes.
   *
   * @note
   * Camera view must be started before calling this method.
   *
   * @returns An array of supported flash modes.
   */
  getSupportedFlashModes(): Promise<Array<FlashMode>>;

  /**
   * Set flash mode.
   *
   * @note
   * Camera view must be started before calling this method.
   *
   * @param options.mode - The flash mode to set.
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
