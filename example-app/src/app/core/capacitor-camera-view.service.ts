import { Injectable } from '@angular/core';
import {
  CameraDevice,
  CameraSessionConfiguration,
  CameraView,
  CameraViewPlugin,
  FlashMode,
  PermissionStatus,
} from 'capacitor-camera-view';

@Injectable({
  providedIn: 'root',
})
export class CapacitorCameraViewService {
  private cameraView: CameraViewPlugin;

  constructor() {
    this.cameraView = CameraView;
  }

  /**
   * Start the camera view
   * @param options Configuration options for the camera session
   */
  async start(options: CameraSessionConfiguration = {}): Promise<void> {
    return this.cameraView.start(options);
  }

  /**
   * Stop the camera view
   */
  async stop(): Promise<void> {
    return this.cameraView.stop();
  }

  /**
   * Check if the camera view is running
   */
  async isRunning(): Promise<boolean> {
    return (await this.cameraView.isRunning()).isRunning;
  }

  /**
   * Capture a photo from the camera view
   * @param quality The quality of the photo (0-100)
   * @returns A base64 encoded string of the captured photo
   */
  async capture(quality: number = 90): Promise<string> {
    return (await this.cameraView.capture({ quality })).photo;
  }

  /**
   * Get a list of available camera devices
   * @returns Array of available camera devices
   */
  async getAvailableDevices(): Promise<Array<CameraDevice>> {
    return (await this.cameraView.getAvailableDevices()).devices;
  }
  /**
   * Switch between front and back camera
   */
  async flipCamera(): Promise<void> {
    await this.cameraView.flipCamera();
  }

  /**
   * Get current zoom capabilities and level
   * @returns Object with min, max and current zoom levels
   */
  async getZoom(): Promise<{ min: number; max: number; current: number }> {
    return this.cameraView.getZoom();
  }

  /**
   * Set the zoom level
   * @param level The zoom level to set
   * @param ramp Whether to animate the zoom level change, defaults to true (iOS / Android only)
   */
  async setZoom(level: number, ramp?: boolean): Promise<void> {
    return this.cameraView.setZoom({ level, ramp });
  }

  /**
   * Get the current flash mode
   * @returns The current flash mode
   */
  async getFlashMode(): Promise<FlashMode> {
    return (await this.cameraView.getFlashMode()).flashMode;
  }

  /**
   * Get all supported flash modes for the current device
   * @returns Array of supported flash modes
   */
  async getSupportedFlashModes(): Promise<FlashMode[]> {
    return (await this.cameraView.getSupportedFlashModes()).flashModes;
  }

  /**
   * Set the flash mode
   * @param mode The flash mode to set
   */
  async setFlashMode(mode: FlashMode): Promise<void> {
    return this.cameraView.setFlashMode({ mode });
  }

  /**
   * Check camera permission status
   * @returns The current permission status
   */
  async checkPermissions(): Promise<PermissionStatus> {
    return (await this.cameraView.checkPermissions()).camera;
  }

  /**
   * Request camera permissions
   * @returns The updated permission status after request
   */
  async requestPermissions(): Promise<PermissionStatus> {
    return (await this.cameraView.requestPermissions()).camera;
  }
}
