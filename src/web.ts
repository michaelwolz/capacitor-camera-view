import { WebPlugin } from '@capacitor/core';

import type {
  CameraSessionConfiguration,
  CameraViewPlugin,
  GetAvailableDevicesResponse,
  GetFlashModeResponse,
  GetSupportedFlashModesResponse,
  GetZoomResponse,
  IsRunningResponse,
  PermissionStatus,
  CaptureResponse,
  FlashMode,
} from './definitions';
import { calculateVisibleArea, canvasToBase64, drawVisibleAreaToCanvas, transformBarcodeBoundingBox } from './utils';

/**
 * Web implementation of the CameraViewPlugin.
 * Optimized for performance and battery efficiency.
 */
export class CameraViewWeb extends WebPlugin implements CameraViewPlugin {
  // DOM elements
  private videoElement: HTMLVideoElement | null = null;
  private canvasElement: HTMLCanvasElement | null = null;

  // Stream state
  private stream: MediaStream | null = null;
  #isRunning: boolean = false;

  // Configuration state
  private currentCamera: string = 'environment'; // Default to back camera
  private currentZoom: number = 1.0;
  private currentFlashMode: FlashMode = 'off';

  // Barcode detection support
  private barcodeDetectionSupported: boolean = false;
  private barcodeDetector: BarcodeDetector | null = null;

  constructor() {
    super();
    this.checkBarcodeDetectionSupport();
  }

  /**
   * Start the camera with the given configuration
   */
  async start(options?: CameraSessionConfiguration): Promise<void> {
    if (this.#isRunning) {
      return;
    }

    const permissionStatus = await this.requestPermissions();
    if (permissionStatus.camera !== 'granted') {
      throw new Error('Camera permission was not granted');
    }

    try {
      // Set up video element if it doesn't exist
      if (!this.videoElement) {
        await this.setupVideoElement(options?.containerElementId);
      }

      // Set up video constraints based on options
      let videoConstraints: MediaTrackConstraints = {};

      // Prefer deviceId if specified
      if (options?.deviceId) {
        videoConstraints.deviceId = { exact: options.deviceId };
        // Remember the current camera mode (though we're using a specific device)
        this.currentCamera = options?.position === 'front' ? 'user' : 'environment';
      } else {
        // Fall back to facing mode
        const facingMode = options?.position === 'front' ? 'user' : 'environment';
        this.currentCamera = facingMode;
        videoConstraints.facingMode = facingMode;
      }

      const constraints: MediaStreamConstraints = {
        video: videoConstraints,
        audio: false,
      };

      this.stream = await navigator.mediaDevices.getUserMedia(constraints);

      if (this.videoElement) {
        this.videoElement.srcObject = this.stream;
        this.videoElement.play();
        this.#isRunning = true;

        // If barcode detection is enabled and supported, start detection
        if (options?.enableBarcodeDetection && this.barcodeDetectionSupported) {
          this.startBarcodeDetection();
        }
      }
    } catch (err) {
      throw new Error(`Failed to start camera: ${err instanceof Error ? err.message : String(err)}`);
    }
  }

  /**
   * Stop the camera and release resources
   */
  async stop(): Promise<void> {
    if (!this.#isRunning) {
      return;
    }

    try {
      // Stop all tracks in the stream
      if (this.stream) {
        this.stream.getTracks().forEach((track) => track.stop());
        this.stream = null;
      }

      // Clear video source
      if (this.videoElement) {
        this.videoElement = null;
      }

      this.#isRunning = false;
    } catch (err) {
      throw new Error(`Failed to stop camera: ${err instanceof Error ? err.message : String(err)}`);
    }
  }

  /**
   * Check if the camera is currently running
   */
  async isRunning(): Promise<IsRunningResponse> {
    return { isRunning: this.#isRunning };
  }

  /**
   * Capture a photo using the camera and return it as a base64-encoded JPEG image.
   * Preserves what the user actually sees in the UI, including cropping from object-fit: cover.
   */
  async capture(options: { quality: number }): Promise<CaptureResponse> {
    const videoElement = this.videoElement;

    if (!this.#isRunning || !videoElement) {
      throw new Error('Camera is not running');
    }

    try {
      const canvas = this.getCanvasElement();
      const visibleArea = calculateVisibleArea(videoElement);

      drawVisibleAreaToCanvas(canvas, videoElement, visibleArea);

      const quality = Math.min(1.0, Math.max(0.1, options.quality / 100));
      const base64Data = canvasToBase64(canvas, quality);

      return { photo: base64Data };
    } catch (err) {
      throw new Error(`Failed to capture photo: ${err instanceof Error ? err.message : String(err)}`);
    }
  }

  /**
   * Flip between front and back camera
   */
  public async flipCamera(): Promise<void> {
    if (!this.#isRunning) {
      throw new Error('Camera is not running');
    }

    try {
      // Switch current camera
      this.currentCamera = this.currentCamera === 'user' ? 'environment' : 'user';

      // Stop current stream
      if (this.stream) {
        this.stream.getTracks().forEach((track) => track.stop());
      }

      // Restart with new facing mode
      const constraints: MediaStreamConstraints = {
        video: {
          facingMode: this.currentCamera,
        },
        audio: false,
      };

      this.stream = await navigator.mediaDevices.getUserMedia(constraints);

      if (this.videoElement) {
        this.videoElement.srcObject = this.stream;
      }
    } catch (err) {
      throw new Error(`Failed to flip camera: ${err instanceof Error ? err.message : String(err)}`);
    }
  }

  /**
   * Get available camera devices
   */
  public async getAvailableDevices(): Promise<GetAvailableDevicesResponse> {
    try {
      const devices = await navigator.mediaDevices.enumerateDevices();
      const videoDevices = devices.filter((device) => device.kind === 'videoinput');

      return {
        devices: videoDevices.map((device) => ({
          id: device.deviceId,
          name: device.label || `Camera ${device.deviceId.substring(0, 5)}`,
          position: device.label.toLowerCase().includes('front') ? 'front' : 'back',
        })),
      };
    } catch (err) {
      console.error('Failed to get available devices', err);
      return { devices: [] };
    }
  }

  /**
   * Get current zoom information (web has limited zoom support)
   */
  public async getZoom(): Promise<GetZoomResponse> {
    // Web has limited zoom capabilities in most browsers
    return {
      min: 1.0,
      max: 1.0,
      current: this.currentZoom,
    };
  }

  /**
   * Set zoom level (limited support in web)
   */
  public async setZoom(options: { level: number; ramp?: boolean }): Promise<void> {
    // Store the requested zoom level even if we can't apply it
    this.currentZoom = options.level;

    // Most browsers don't support zoom via MediaTrackConstraints yet
    console.warn('Zoom is not fully supported in the web implementation');
  }

  /**
   * Get current flash mode
   */
  public async getFlashMode(): Promise<GetFlashModeResponse> {
    return { flashMode: this.currentFlashMode };
  }

  /**
   * Get supported flash modes
   */
  public async getSupportedFlashModes(): Promise<GetSupportedFlashModesResponse> {
    // Web has limited flash control
    return { flashModes: ['off'] };
  }

  /**
   * Set flash mode (limited support in web)
   */
  public async setFlashMode(options: { mode: FlashMode }): Promise<void> {
    this.currentFlashMode = options.mode;
    console.warn('Flash mode control is not fully supported in the web implementation');
  }

  /**
   * Check camera permission without requesting
   */
  public async checkPermissions(): Promise<PermissionStatus> {
    try {
      // Use Permissions API if available
      if (navigator.permissions) {
        const result = await navigator.permissions.query({ name: 'camera' as PermissionName });
        return {
          camera: result.state === 'granted' ? 'granted' : result.state === 'denied' ? 'denied' : 'prompt',
        };
      }

      // If Permissions API is not available, check if we have an active stream
      return {
        camera: this.stream ? 'granted' : 'prompt',
      };
    } catch (err) {
      // If permissions API is not supported or fails
      return {
        camera: 'prompt',
      };
    }
  }

  /**
   * Request camera permission from the user
   */
  public async requestPermissions(): Promise<PermissionStatus> {
    try {
      // Try to access the camera to trigger the permission prompt
      const stream = await navigator.mediaDevices.getUserMedia({ video: true });

      // If we get here, permission was granted
      // Clean up the test stream
      stream.getTracks().forEach((track) => track.stop());

      return { camera: 'granted' };
    } catch (err) {
      // Permission denied or other error
      return { camera: 'denied' };
    }
  }

  /**
   * Start barcode detection if supported
   */
  private async startBarcodeDetection() {
    const barcodeDetector = this.barcodeDetector;
    const videoElement = this.videoElement;

    if (!this.barcodeDetectionSupported || !barcodeDetector || !videoElement) {
      return;
    }

    // Make sure video is fully loaded before starting detection
    if (videoElement.readyState < 2) {
      await new Promise<void>((resolve) => {
        const loadHandler = () => {
          videoElement.removeEventListener('loadeddata', loadHandler);
          resolve();
        };
        videoElement.addEventListener('loadeddata', loadHandler);
      });
    }

    // Set up periodic frame analysis for barcode detection
    const detectFrame = async () => {
      if (!this.#isRunning || !videoElement || !barcodeDetector) {
        return;
      }

      try {
        const barcodes = await barcodeDetector.detect(videoElement);

        if (barcodes.length > 0) {
          const barcode = barcodes[0];

          // Transform barcode coordinates using the utility function
          const boundingRect = transformBarcodeBoundingBox(barcode.boundingBox, videoElement);

          this.notifyListeners('barcodeDetected', {
            value: barcode.rawValue,
            type: barcode.format.toLowerCase(),
            boundingRect,
          });
        }
      } catch (err) {
        console.error('Barcode detection error', err);
      }

      if (this.#isRunning) {
        requestAnimationFrame(detectFrame);
      }
    };

    requestAnimationFrame(detectFrame);
  }

  /**
   * Clean up resources when the plugin is disposed
   */
  public async handleOnDestroy() {
    await this.stop();

    // Remove elements from DOM
    if (this.videoElement && this.videoElement.parentNode) {
      this.videoElement.parentNode.removeChild(this.videoElement);
      this.videoElement = null;
    }

    if (this.canvasElement) {
      this.canvasElement = null;
    }

    this.barcodeDetector = null;
  }

  /**
   * Check if barcode detection is supported in this browser
   */
  private async checkBarcodeDetectionSupport() {
    if ('BarcodeDetector' in window) {
      try {
        this.barcodeDetector = new BarcodeDetector();
        this.barcodeDetectionSupported = true;
      } catch (e) {
        console.warn('BarcodeDetector is not supported by this browser.');
        this.barcodeDetectionSupported = false;
      }
    }
  }

  /**
   * Set up the video element for the camera view
   */
  private async setupVideoElement(containerElementId?: string) {
    this.videoElement = document.createElement('video');
    this.videoElement.playsInline = true;
    this.videoElement.autoplay = true;
    this.videoElement.muted = true;
    this.videoElement.style.width = '100%';
    this.videoElement.style.height = '100%';
    this.videoElement.style.objectFit = 'cover';

    // If a container ID is provided, find that element and append the video to it
    if (containerElementId) {
      const container = document.getElementById(containerElementId);
      if (!container) {
        throw new Error(`Container element with ID ${containerElementId} not found`);
      }
      container.appendChild(this.videoElement);
    } else {
      // Otherwise, append to body as fallback
      document.body.appendChild(this.videoElement);
    }
  }

  /**
   * Ensures canvas element exists and returns it
   */
  private getCanvasElement(): HTMLCanvasElement {
    if (!this.canvasElement) {
      this.canvasElement = document.createElement('canvas');
    }
    return this.canvasElement;
  }
}
