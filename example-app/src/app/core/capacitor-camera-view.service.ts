import { Injectable } from '@angular/core';
import { CameraPosition, CameraView, CameraViewPlugin, FlashMode, PermissionStatus } from 'capacitor-camera-view';

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
   * @param cameraPosition The position of the camera (front or back)
   */
  async start(cameraPosition: CameraPosition = CameraPosition.BACK): Promise<void> {
    return this.cameraView.start({ cameraPosition });
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
    return this.cameraView.isRunning();
  }

  /**
   * Capture a photo from the camera view
   * @param quality The quality of the photo (0-100)
   * @returns A base64 encoded string of the captured photo
   */
  async capture(quality: number = 90): Promise<string> {
    return this.cameraView.capture({ quality });
  }

  /**
   * Switch between front and back camera
   */
  async switchCamera(): Promise<void> {
    return this.cameraView.switchCamera();
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
   */
  async setZoom(level: number): Promise<void> {
    return this.cameraView.setZoom({ level });
  }

  /**
   * Get the current flash mode
   * @returns The current flash mode
   */
  async getFlashMode(): Promise<FlashMode> {
    return this.cameraView.getFlashMode();
  }

  /**
   * Get all supported flash modes for the current device
   * @returns Array of supported flash modes
   */
  async getSupportedFlashModes(): Promise<FlashMode[]> {
    return this.cameraView.getSupportedFlashModes();
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
    return this.cameraView.checkPermissions();
  }

  /**
   * Request camera permissions
   * @returns The updated permission status after request
   */
  async requestPermissions(): Promise<PermissionStatus> {
    return this.cameraView.requestPermissions();
  }
}
