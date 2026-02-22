import { Injectable } from '@angular/core';
import { PermissionState } from '@capacitor/core';
import {
  BarcodeDetectionData,
  CameraDevice,
  CameraSessionConfiguration,
  CameraView,
  CameraViewPlugin,
  CaptureOptions,
  CaptureResponse,
  FlashMode,
  VideoRecordingOptions,
  VideoRecordingResponse,
} from 'capacitor-camera-view';
import { BehaviorSubject, Subject } from 'rxjs';

@Injectable({
  providedIn: 'root',
})
export class CapacitorCameraViewService {
  #cameraView: CameraViewPlugin;
  #barcodeData = new Subject<BarcodeDetectionData>();

  /**
   * Observable for barcode detection events
   */
  readonly barcodeData = this.#barcodeData.asObservable();

  readonly #cameraStarted = new BehaviorSubject<boolean>(false);
  public readonly cameraStarted = this.#cameraStarted.asObservable();

  constructor() {
    this.#cameraView = CameraView;

    // Add event listeners
    this.#cameraView.removeAllListeners().then(() => {
      this.#cameraView.addListener('barcodeDetected', (event) => {
        this.#barcodeData.next(event);
      });
    });

    this.cameraStarted.subscribe((started) => {
      document.body.classList.toggle('camera-running', started);
    });
  }

  /**
   * Start the camera view
   * @param options Configuration options for the camera session
   */
  async start(options: CameraSessionConfiguration = {}): Promise<void> {
    await this.#cameraView.start(options);
    this.#cameraStarted.next(true);
  }

  /**
   * Stop the camera view
   */
  async stop(): Promise<void> {
    await this.#cameraView.stop();
    this.#cameraStarted.next(false);
  }

  /**
   * Check if the camera view is running
   */
  async isRunning(): Promise<boolean> {
    return (await this.#cameraView.isRunning()).isRunning;
  }

  /**
   * Capture a photo from the camera view
   * @returns Object containing either photo (base64) or path (file URL)
   */
  async capture<T extends CaptureOptions>(
    options: T,
  ): Promise<CaptureResponse<T>> {
    return await this.#cameraView.capture(options);
  }

  /**
   * Capture a sample from the camera view
   * @returns Object containing either photo (base64) or path (file URL)
   */
  async captureSample<T extends CaptureOptions>(
    options: T,
  ): Promise<CaptureResponse<T>> {
    return await this.#cameraView.captureSample(options);
  }

  /**
   * Get a list of available camera devices
   * @returns Array of available camera devices
   */
  async getAvailableDevices(): Promise<Array<CameraDevice>> {
    return (await this.#cameraView.getAvailableDevices()).devices;
  }
  /**
   * Switch between front and back camera
   */
  async flipCamera(): Promise<void> {
    await this.#cameraView.flipCamera();
  }

  /**
   * Get current zoom capabilities and level
   * @returns Object with min, max and current zoom levels
   */
  async getZoom(): Promise<{ min: number; max: number; current: number }> {
    return this.#cameraView.getZoom();
  }

  /**
   * Set the zoom level
   * @param level The zoom level to set
   * @param ramp Whether to animate the zoom level change, defaults to true (iOS / Android only)
   */
  async setZoom(level: number, ramp?: boolean): Promise<void> {
    return this.#cameraView.setZoom({ level, ramp });
  }

  /**
   * Get the current flash mode
   * @returns The current flash mode
   */
  async getFlashMode(): Promise<FlashMode> {
    return (await this.#cameraView.getFlashMode()).flashMode;
  }

  /**
   * Get all supported flash modes for the current device
   * @returns Array of supported flash modes
   */
  async getSupportedFlashModes(): Promise<FlashMode[]> {
    return (await this.#cameraView.getSupportedFlashModes()).flashModes;
  }

  /**
   * Set the flash mode
   * @param mode The flash mode to set
   */
  async setFlashMode(mode: FlashMode): Promise<void> {
    return this.#cameraView.setFlashMode({ mode });
  }

  /**
   * Check if torch is available on the device
   * @returns True if torch is available, false otherwise
   */
  async isTorchAvailable(): Promise<boolean> {
    return (await this.#cameraView.isTorchAvailable()).available;
  }

  /**
   * Get the current torch mode and level
   * @returns The current torch state
   */
  async getTorchMode(): Promise<{ enabled: boolean; level: number }> {
    const result = await this.#cameraView.getTorchMode();
    return { enabled: result.enabled, level: result.level };
  }

  /**
   * Set the torch mode and level
   * @param enabled Whether to enable the torch
   * @param level The torch intensity level (0.0 to 1.0, iOS only)
   */
  async setTorchMode(enabled: boolean, level: number = 1.0): Promise<void> {
    return this.#cameraView.setTorchMode({ enabled, level });
  }

  /**
   * Start video recording
   * @param options Optional recording configuration
   */
  async startRecording(options?: VideoRecordingOptions): Promise<void> {
    await this.#cameraView.startRecording(options);
  }

  /**
   * Stop the current video recording
   * @returns The recorded video file path
   */
  async stopRecording(): Promise<VideoRecordingResponse> {
    return this.#cameraView.stopRecording();
  }

  /**
   * Check camera permission status
   * @returns The current permission status
   */
  async checkPermissions(): Promise<PermissionState> {
    return (await this.#cameraView.checkPermissions()).camera;
  }

  /**
   * Request camera permissions
   * @returns The updated permission status after request
   */
  async requestPermissions(): Promise<PermissionState> {
    return (await this.#cameraView.requestPermissions()).camera;
  }
}
