import { WebPlugin } from '@capacitor/core';

import type {
  CameraSessionConfiguration,
  CameraViewPlugin,
  CameraPermissionType,
  GetAvailableDevicesResponse,
  GetFlashModeResponse,
  GetSupportedFlashModesResponse,
  GetTorchModeResponse,
  GetZoomResponse,
  IsTorchAvailableResponse,
  IsRunningResponse,
  PermissionStatus,
  CaptureResponse,
  FlashMode,
  CaptureOptions,
  VideoRecordingOptions,
  VideoRecordingResponse,
  BarcodeType,
} from './definitions';
import { calculateVisibleArea, canvasToBase64, drawVisibleAreaToCanvas, transformBarcodeBoundingBox } from './utils';

export const BARCODE_TYPE_TO_WEB_FORMAT: Readonly<Record<BarcodeType, BarcodeFormat | null>> = {
  qr: 'qr_code',
  code128: 'code_128',
  code39: 'code_39',
  code39Mod43: null,
  code93: 'code_93',
  ean8: 'ean_8',
  ean13: 'ean_13',
  interleaved2of5: 'itf',
  itf14: 'itf',
  pdf417: 'pdf417',
  aztec: 'aztec',
  dataMatrix: 'data_matrix',
  upce: 'upc_e',
} satisfies Record<BarcodeType, BarcodeFormat | null>;

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
  #isRunning = false;

  // Configuration state
  private currentCamera = 'environment'; // Default to back camera
  private currentZoom = 1.0;
  private currentFlashMode: FlashMode = 'off';

  // Barcode detection support
  private barcodeDetectionSupported = false;
  private barcodeDetector: BarcodeDetector | null = null;

  // Recording state
  private mediaRecorder: MediaRecorder | null = null;
  private recordedChunks: Blob[] = [];
  private recordingAudioTrack: MediaStreamTrack | null = null;
  private recordingResolve: ((response: VideoRecordingResponse) => void) | null = null;
  private recordingReject: ((error: Error) => void) | null = null;

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
      const videoConstraints: MediaTrackConstraints = {};

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
        if (options?.enableBarcodeDetection) {
          await this.checkBarcodeDetectionSupport();

          if (this.barcodeDetectionSupported) {
            await this.configureBarcodeDetector(options?.barcodeTypes);
            this.startBarcodeDetection();
          }
        }
      }
    } catch (err) {
      throw new Error(`Failed to start camera: ${this.formatError(err)}`);
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
      // Stop any active recording
      if (this.mediaRecorder && this.mediaRecorder.state !== 'inactive') {
        // Reject any pending stopRecording promise since we're force-stopping
        this.recordingReject?.(new Error('Camera session stopped while recording'));
        this.recordingResolve = null;
        this.recordingReject = null;
        this.mediaRecorder.stop();
        this.mediaRecorder = null;
      }
      this.recordedChunks = [];
      if (this.recordingAudioTrack) {
        this.recordingAudioTrack.stop();
        this.recordingAudioTrack = null;
      }

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
      throw new Error(`Failed to stop camera: ${this.formatError(err)}`);
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
  async capture<T extends CaptureOptions>(options: T): Promise<CaptureResponse<T>> {
    const videoElement = this.videoElement;

    if (!this.#isRunning || !videoElement) {
      throw new Error('Camera is not running');
    }

    try {
      const canvas = this.getCanvasElement();
      const visibleArea = calculateVisibleArea(videoElement);

      drawVisibleAreaToCanvas(canvas, videoElement, visibleArea);

      const quality = Math.min(1.0, Math.max(0.1, options.quality / 100));

      if (options.saveToFile) {
        // Create a blob from canvas and return a blob URL
        return new Promise((resolve, reject) => {
          canvas.toBlob(
            (blob) => {
              if (!blob) {
                reject(new Error('Failed to create blob from canvas'));
                return;
              }

              const url = URL.createObjectURL(blob);
              resolve({ webPath: url } as CaptureResponse<T>);
            },
            'image/jpeg',
            quality,
          );
        });
      } else {
        // Return base64 data
        const base64Data = canvasToBase64(canvas, quality);
        return { photo: base64Data } as CaptureResponse<T>;
      }
    } catch (err) {
      throw new Error(`Failed to capture photo: ${this.formatError(err)}`);
    }
  }

  /**
   * Web implementation already uses images from the video stream, so this is the same as `capture()`
   */
  async captureSample<T extends CaptureOptions>(options: T): Promise<CaptureResponse<T>> {
    return this.capture(options);
  }

  /**
   * Start recording video using MediaRecorder API
   */
  async startRecording(options?: VideoRecordingOptions): Promise<void> {
    if (!this.#isRunning || !this.videoElement) {
      throw new Error('Camera is not running');
    }

    if (this.mediaRecorder) {
      throw new Error('Recording is already in progress');
    }

    try {
      let stream = this.stream;

      // If audio is requested, get a new stream with audio track
      if (options?.enableAudio && stream) {
        const audioStream = await navigator.mediaDevices.getUserMedia({ audio: true });
        const audioTrack = audioStream.getAudioTracks()[0];
        this.recordingAudioTrack = audioTrack;
        const videoTracks = stream.getVideoTracks();
        stream = new MediaStream([...videoTracks, audioTrack]);
      }

      if (!stream) {
        throw new Error('No camera stream available');
      }

      this.recordedChunks = [];

      const mimeType = ['video/webm;codecs=vp9', 'video/webm', 'video/mp4'].find((type) =>
        MediaRecorder.isTypeSupported(type),
      );

      if (!mimeType) {
        throw new Error('No supported video recording format found');
      }

      this.mediaRecorder = new MediaRecorder(stream, { mimeType });

      this.mediaRecorder.ondataavailable = (event) => {
        if (event.data && event.data.size > 0) {
          this.recordedChunks.push(event.data);
        }
      };

      this.mediaRecorder.onstop = () => {
        // Stop audio track if it was added for recording
        if (this.recordingAudioTrack) {
          this.recordingAudioTrack.stop();
          this.recordingAudioTrack = null;
        }
        const blob = new Blob(this.recordedChunks, { type: mimeType });
        const url = URL.createObjectURL(blob);
        this.recordedChunks = [];
        this.mediaRecorder = null;
        this.recordingResolve?.({ webPath: url });
        this.recordingResolve = null;
        this.recordingReject = null;
      };

      this.mediaRecorder.onerror = (event) => {
        if (this.recordingAudioTrack) {
          this.recordingAudioTrack.stop();
          this.recordingAudioTrack = null;
        }
        this.mediaRecorder = null;
        this.recordedChunks = [];
        const errorMessage = (event as ErrorEvent).error?.message ?? 'Unknown recording error';
        this.recordingReject?.(new Error('Recording error: ' + errorMessage));
        this.recordingResolve = null;
        this.recordingReject = null;
      };

      this.mediaRecorder.start(100); // Collect data in 100ms chunks
    } catch (err) {
      throw new Error(`Failed to start recording: ${this.formatError(err)}`);
    }
  }

  /**
   * Stop the current video recording
   */
  async stopRecording(): Promise<VideoRecordingResponse> {
    if (!this.mediaRecorder) {
      throw new Error('No recording is in progress');
    }

    return new Promise<VideoRecordingResponse>((resolve, reject) => {
      this.recordingResolve = resolve;
      this.recordingReject = reject;
      this.mediaRecorder?.stop();
    });
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
      throw new Error(`Failed to flip camera: ${this.formatError(err)}`);
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
    // Web has limited zoom capabilities in most browsers,
    // we fake zoomin by scaling the video element
    return {
      min: 1.0,
      max: 3.0,
      current: this.currentZoom,
    };
  }

  /**
   * Set zoom level (limited support in web)
   */
  public async setZoom(options: { level: number; ramp?: boolean }): Promise<void> {
    // Store the requested zoom level
    this.currentZoom = options.level;

    // Apply visual zoom using CSS transform when native zoom isn't supported
    if (this.videoElement) {
      this.videoElement.style.transition = options.ramp ? 'transform 0.2s ease-in-out' : 'none';
      const scale = Math.max(1.0, Math.min(options.level, 3.0)); // Limit scale to reasonable bounds
      this.videoElement.style.transform = `scale(${scale})`;
      this.videoElement.style.transformOrigin = 'center';
    }
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
   * Check if torch is available (not supported in web)
   */
  public async isTorchAvailable(): Promise<IsTorchAvailableResponse> {
    // Torch is not supported in web implementation
    return { available: false };
  }

  /**
   * Get torch mode (not supported in web)
   */
  public async getTorchMode(): Promise<GetTorchModeResponse> {
    // Torch is not supported in web implementation
    return { enabled: false, level: 0.0 };
  }

  /**
   * Set torch mode (not supported in web)
   */
  public async setTorchMode(): Promise<void> {
    // Torch is not supported in web implementation
    throw this.unimplemented('Torch control is not supported in web implementation.');
  }

  /**
   * Check camera and microphone permission without requesting
   */
  public async checkPermissions(): Promise<PermissionStatus> {
    try {
      // Use Permissions API if available
      if (navigator.permissions) {
        const [cameraResult, microphoneResult] = await Promise.all([
          navigator.permissions.query({ name: 'camera' as PermissionName }),
          navigator.permissions.query({ name: 'microphone' as PermissionName }),
        ]);
        return {
          camera: cameraResult.state === 'granted' ? 'granted' : cameraResult.state === 'denied' ? 'denied' : 'prompt',
          microphone:
            microphoneResult.state === 'granted'
              ? 'granted'
              : microphoneResult.state === 'denied'
                ? 'denied'
                : 'prompt',
        };
      }

      // If Permissions API is not available, fall back to checking the active stream
      return {
        camera: this.stream ? 'granted' : 'prompt',
        microphone: 'prompt',
      };
    } catch (err) {
      // If permissions API is not supported or fails
      return {
        camera: 'prompt',
        microphone: 'prompt',
      };
    }
  }

  /**
   * Request camera and/or microphone permissions from the user.
   * By default, only camera permission is requested.
   */
  public async requestPermissions(options?: { permissions?: CameraPermissionType[] }): Promise<PermissionStatus> {
    const permissions = options?.permissions ?? ['camera'];
    const result: PermissionStatus = { camera: 'prompt', microphone: 'prompt' };

    // Request camera permission if included
    if (permissions.includes('camera')) {
      try {
        const stream = await navigator.mediaDevices.getUserMedia({ video: true });
        stream.getTracks().forEach((track) => track.stop());
        result.camera = 'granted';
      } catch {
        result.camera = 'denied';
      }
    } else {
      // Still report current status even if not requesting
      result.camera = (await this.checkPermissions()).camera;
    }

    // Request microphone permission only if explicitly included
    if (permissions.includes('microphone')) {
      try {
        const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
        stream.getTracks().forEach((track) => track.stop());
        result.microphone = 'granted';
      } catch {
        result.microphone = 'denied';
      }
    } else {
      result.microphone = (await this.checkPermissions()).microphone;
    }

    return result;
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

    // Add throttling to reduce CPU usage
    let lastDetectionTime = 0;
    const minTimeBetweenDetections = 100; // ms

    // Set up periodic frame analysis for barcode detection
    const detectFrame = async () => {
      if (!this.#isRunning || !videoElement || !barcodeDetector) {
        return;
      }

      const now = Date.now();
      if (now - lastDetectionTime >= minTimeBetweenDetections) {
        try {
          const barcodes = await barcodeDetector.detect(videoElement);
          lastDetectionTime = now;

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
  public async handleOnDestroy(): Promise<void> {
    await this.stop();

    // Remove elements from DOM
    if (this.videoElement?.parentNode) {
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
   * Configure the barcode detector with requested barcode formats.
   * Unsupported formats are ignored and logged.
   */
  private async configureBarcodeDetector(barcodeTypes?: BarcodeType[]): Promise<void> {
    if (!this.barcodeDetectionSupported) {
      return;
    }

    if (!barcodeTypes?.length) {
      this.barcodeDetector = new BarcodeDetector();
      return;
    }

    const requestedFormats = barcodeTypes
      .map((barcodeType) => {
        const webFormat = BARCODE_TYPE_TO_WEB_FORMAT[barcodeType];
        if (!webFormat) {
          console.warn(`[CameraView] Barcode type "${barcodeType}" is not supported by the web BarcodeDetector API.`);
        }
        return webFormat;
      })
      .filter((format): format is BarcodeFormat => format !== null);

    if (!requestedFormats.length) {
      console.warn(
        '[CameraView] No requested barcode types are supported on web. Falling back to all supported formats.',
      );
      this.barcodeDetector = new BarcodeDetector();
      return;
    }

    const uniqueRequestedFormats = Array.from(new Set(requestedFormats));

    try {
      const supportedFormats = await BarcodeDetector.getSupportedFormats();
      const configuredFormats = uniqueRequestedFormats.filter((format) => supportedFormats.includes(format));
      const ignoredFormats = uniqueRequestedFormats.filter((format) => !supportedFormats.includes(format));

      if (ignoredFormats.length) {
        console.warn(
          `[CameraView] Ignoring unsupported barcode formats for this browser: ${ignoredFormats.join(', ')}.`,
        );
      }

      if (!configuredFormats.length) {
        console.warn(
          '[CameraView] No requested barcode formats are available in this browser. Falling back to all supported formats.',
        );
        this.barcodeDetector = new BarcodeDetector();
        return;
      }

      this.barcodeDetector = new BarcodeDetector({ formats: configuredFormats });
    } catch (error) {
      console.warn(
        '[CameraView] Failed to resolve supported barcode formats; falling back to unfiltered detector.',
        error,
      );
      this.barcodeDetector = new BarcodeDetector();
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

  /**
   * Format error message
   */
  private formatError(err: unknown): string {
    return err instanceof Error ? err.message : String(err);
  }
}
