import {
  Component,
  effect,
  ElementRef,
  inject,
  input,
  OnInit,
  signal,
} from '@angular/core';
import { Capacitor } from '@capacitor/core';
import {
  IonButton,
  IonButtons,
  IonFab,
  IonFabButton,
  IonHeader,
  IonIcon,
  IonSpinner,
  IonTitle,
  IonToolbar,
  ModalController,
} from '@ionic/angular/standalone';
import { CameraPosition, CameraPreset } from 'capacitor-camera-view';
import { CapacitorCameraViewService } from '../../core/capacitor-camera-view.service';

import type { FlashMode } from 'capacitor-camera-view';

@Component({
  selector: 'app-camera-modal',
  templateUrl: './camera-modal.component.html',
  styleUrls: ['./camera-modal.component.scss'],
  imports: [
    IonButton,
    IonButtons,
    IonFab,
    IonFabButton,
    IonHeader,
    IonIcon,
    IonToolbar,
    IonTitle,
    IonSpinner,
  ],
  host: {
    class: 'camera-modal',
  },
})
export class CameraModalComponent implements OnInit {
  readonly #cameraViewService = inject(CapacitorCameraViewService);
  readonly #elementRef = inject(ElementRef);
  readonly #modalController = inject(ModalController);

  public readonly deviceId = input<string>();
  public readonly position = input<CameraPosition>('back');
  public readonly preset = input<CameraPreset>('photo');
  public readonly quality = input<number>(85);
  public readonly useTripleCameraIfAvailable = input<boolean>(false);
  public readonly initialZoomFactor = input<number>(1.0);

  protected readonly cameraRunning = signal(true);
  protected readonly flashMode = signal<FlashMode>('auto');
  protected readonly isCapturingPhoto = signal(false);

  protected readonly isWeb = Capacitor.getPlatform() === 'web';

  #supportedFlashModes = ['auto'];

  #currentZoomFactor = this.initialZoomFactor();
  #touchStartDistance = 0;
  #initialZoomFactorOnPinch = 1.0;
  #minZoom = 1.0;
  #maxZoom = 10.0;

  constructor() {
    effect(() => {
      document.body.classList.toggle('camera-running', this.cameraRunning());
    });
  }

  public ngOnInit() {
    this.#cameraViewService
      .getSupportedFlashModes()
      .then((supportedFlashModes) => {
        this.#supportedFlashModes = supportedFlashModes;
        if (!supportedFlashModes.includes(this.flashMode())) {
          this.flashMode.set(supportedFlashModes[0] as FlashMode);
        }
      });

    this.startCamera().catch((error) => {
      console.error('Failed to start camera', error);
      this.#modalController.dismiss();
    });

    this.initializeZoomLimits();
    this.setupPinchZoom();
  }

  protected async startCamera(): Promise<void> {
    await this.#cameraViewService.start({
      deviceId: this.deviceId(),
      position: this.position(),
      preset: this.preset(),
      useTripleCameraIfAvailable: this.useTripleCameraIfAvailable(),
      zoomFactor: this.initialZoomFactor(),
    });
    this.cameraRunning.set(true);
  }

  protected async stopCamera(): Promise<void> {
    try {
      await this.#cameraViewService.stop();
      this.cameraRunning.set(false);
    } finally {
      this.#modalController.dismiss();
    }
  }

  protected async capturePhoto(): Promise<void> {
    this.isCapturingPhoto.set(true);
    try {
      const photo = await this.#cameraViewService.capture(this.quality());
      this.#modalController.dismiss({ photo });
    } catch (error) {
      console.error('Failed to capture photo', error);
      this.#modalController.dismiss();
    }

    this.stopCamera().catch((error) => {
      console.error('Failed to stop camera', error);
    });

    this.isCapturingPhoto.set(false);
  }

  protected async flipCamera(): Promise<void> {
    await this.#cameraViewService.flipCamera();
  }

  protected async nextFlashMode(): Promise<void> {
    const currentFlashMode = this.flashMode();
    const supportedModes = this.#supportedFlashModes;

    if (supportedModes.length <= 1) {
      // No alternative modes to switch to
      return;
    }

    // Find current index in supported modes
    const currentIndex = supportedModes.indexOf(currentFlashMode);

    // Get next mode (wrapping around to the beginning if needed)
    const nextIndex = (currentIndex + 1) % supportedModes.length;
    const nextFlashMode = supportedModes[nextIndex] as FlashMode;

    this.flashMode.set(nextFlashMode);
    await this.#cameraViewService.setFlashMode(nextFlashMode);
  }

  private async initializeZoomLimits(): Promise<void> {
    try {
      const zoomRange = await this.#cameraViewService.getZoom();
      if (zoomRange) {
        this.#minZoom = zoomRange.min;
        this.#maxZoom = zoomRange.max;
        console.log(
          `Camera zoom range: min=${this.#minZoom}, max=${this.#maxZoom}`,
        );
      }
    } catch (error) {
      console.warn('Failed to get zoom range, using default values', error);
    }
  }

  private async setZoom(zoomFactor: number): Promise<void> {
    this.#currentZoomFactor = zoomFactor;
    await this.#cameraViewService.setZoom(zoomFactor, false);
  }

  private setupPinchZoom(): void {
    const element = this.#elementRef.nativeElement;

    element.addEventListener('touchstart', (event: TouchEvent) => {
      if (event.touches.length >= 2) {
        this.#touchStartDistance = this.getDistance(
          event.touches[0],
          event.touches[1],
        );
        this.#initialZoomFactorOnPinch = this.#currentZoomFactor;
      }
    });

    element.addEventListener('touchmove', (event: TouchEvent) => {
      if (event.touches.length >= 2) {
        const currentDistance = this.getDistance(
          event.touches[0],
          event.touches[1],
        );

        if (this.#touchStartDistance > 0) {
          // Calculate new zoom factor
          const scale = currentDistance / this.#touchStartDistance;
          const newZoomFactor = Math.max(
            this.#minZoom,
            Math.min(this.#maxZoom, this.#initialZoomFactorOnPinch * scale),
          );

          this.setZoom(newZoomFactor);
        }

        // Prevent default behavior to avoid scrolling
        event.preventDefault();
      }
    });
  }

  private getDistance(touch1: Touch, touch2: Touch): number {
    const dx = touch1.clientX - touch2.clientX;
    const dy = touch1.clientY - touch2.clientY;
    return Math.sqrt(dx * dx + dy * dy);
  }
}
