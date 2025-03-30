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
  private barcodeDetector: any = null; // Will be initialized if supported

  constructor() {
    super();

    // Check for BarcodeDetector support (part of Shape Detection API)
    this.checkBarcodeDetectionSupport();
  }

  /**
   * Check if barcode detection is supported in this browser
   */
  private async checkBarcodeDetectionSupport() {
    if ('BarcodeDetector' in window) {
      try {
        // @ts-ignore - BarcodeDetector is not in TypeScript DOM lib yet
        this.barcodeDetector = new BarcodeDetector();
        this.barcodeDetectionSupported = true;
      } catch (e) {
        console.warn('BarcodeDetector is not supported by this browser.');
        this.barcodeDetectionSupported = false;
      }
    }
  }

  /**
   * Start the camera with the given configuration
   */
  async start(options?: CameraSessionConfiguration): Promise<void> {
    // Don't restart if already running
    if (this.#isRunning) {
      return;
    }

    // Try to get user permissions
    const permissionStatus = await this.requestPermissions();
    if (permissionStatus.camera !== 'granted') {
      throw new Error('Camera permission was not granted');
    }

    try {
      // Set up video element if it doesn't exist
      if (!this.videoElement) {
        await this.setupVideoElement();
      }

      // Apply configuration
      const facingMode = options?.position === 'front' ? 'user' : 'environment';
      this.currentCamera = facingMode;

      // Start media stream
      const constraints: MediaStreamConstraints = {
        video: {
          facingMode,
        },
        audio: false,
      };

      // Get media stream
      this.stream = await navigator.mediaDevices.getUserMedia(constraints);

      // Set stream to video element
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
        this.videoElement.srcObject = null;
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
   * Capture a photo from the current camera view
   */
  async capture(options: { quality: number }): Promise<CaptureResponse> {
    if (!this.#isRunning || !this.videoElement) {
      throw new Error('Camera is not running');
    }

    try {
      // Create canvas if it doesn't exist
      if (!this.canvasElement) {
        this.canvasElement = document.createElement('canvas');
      }

      const video = this.videoElement;
      const canvas = this.canvasElement;

      // Set canvas size to match video dimensions
      canvas.width = video.videoWidth;
      canvas.height = video.videoHeight;

      const ctx = canvas.getContext('2d');
      if (!ctx) {
        throw new Error('Could not get canvas context');
      }

      // Draw video frame to canvas
      ctx.drawImage(video, 0, 0, canvas.width, canvas.height);

      // Convert to base64 with specified quality
      const quality = Math.min(1.0, Math.max(0.1, options.quality / 100));
      const dataUrl = canvas.toDataURL('image/jpeg', quality);

      // Extract base64 data from data URL
      const base64Data = dataUrl.split(',')[1];

      return { photo: base64Data };
    } catch (err) {
      throw new Error(`Failed to capture photo: ${err instanceof Error ? err.message : String(err)}`);
    }
  }

  /**
   * Flip between front and back camera
   */
  async flipCamera(): Promise<void> {
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
  async getAvailableDevices(): Promise<GetAvailableDevicesResponse> {
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
  async getZoom(): Promise<GetZoomResponse> {
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
  async setZoom(options: { level: number; ramp?: boolean }): Promise<void> {
    // Store the requested zoom level even if we can't apply it
    this.currentZoom = options.level;

    // Most browsers don't support zoom via MediaTrackConstraints yet
    console.warn('Zoom is not fully supported in the web implementation');
  }

  /**
   * Get current flash mode
   */
  async getFlashMode(): Promise<GetFlashModeResponse> {
    return { flashMode: this.currentFlashMode };
  }

  /**
   * Get supported flash modes
   */
  async getSupportedFlashModes(): Promise<GetSupportedFlashModesResponse> {
    // Web has limited flash control
    return { flashModes: ['off'] };
  }

  /**
   * Set flash mode (limited support in web)
   */
  async setFlashMode(options: { mode: FlashMode }): Promise<void> {
    this.currentFlashMode = options.mode;
    console.warn('Flash mode control is not fully supported in the web implementation');
  }

  /**
   * Check camera permission without requesting
   */
  async checkPermissions(): Promise<PermissionStatus> {
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
  async requestPermissions(): Promise<PermissionStatus> {
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
    if (!this.barcodeDetectionSupported || !this.barcodeDetector || !this.videoElement) {
      return;
    }

    // Set up periodic frame analysis for barcode detection
    const detectFrame = async () => {
      if (!this.#isRunning || !this.videoElement || !this.barcodeDetector) {
        return;
      }

      try {
        // @ts-ignore - BarcodeDetector is not in TypeScript DOM lib yet
        const barcodes = await this.barcodeDetector.detect(this.videoElement);

        if (barcodes.length > 0) {
          // Process the first barcode
          const barcode = barcodes[0];

          // Normalize bounding box coordinates (relative to video size)
          const videoWidth = this.videoElement.videoWidth;
          const videoHeight = this.videoElement.videoHeight;

          const boundingRect = {
            x: barcode.boundingBox.x / videoWidth,
            y: barcode.boundingBox.y / videoHeight,
            width: barcode.boundingBox.width / videoWidth,
            height: barcode.boundingBox.height / videoHeight,
          };

          // Notify listeners
          this.notifyListeners('barcodeDetected', {
            value: barcode.rawValue,
            type: barcode.format.toLowerCase(),
            boundingRect,
          });
        }
      } catch (err) {
        console.error('Barcode detection error', err);
      }

      // Continue detection if still running
      if (this.#isRunning) {
        requestAnimationFrame(detectFrame);
      }
    };

    // Start detection
    requestAnimationFrame(detectFrame);
  }

  /**
   * Set up the video element for the camera view
   */
  private async setupVideoElement() {
    // Create video element if it doesn't exist
    this.videoElement = document.createElement('video');
    this.videoElement.playsInline = true;
    this.videoElement.autoplay = true;
    this.videoElement.muted = true;
    this.videoElement.style.width = '100%';
    this.videoElement.style.height = '100%';
    this.videoElement.style.objectFit = 'cover';

    // Add to DOM
    document.body.appendChild(this.videoElement);
  }

  /**
   * Clean up resources when the plugin is disposed
   */
  async handleOnDestroy() {
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
}
