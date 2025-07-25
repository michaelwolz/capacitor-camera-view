import {
  Component,
  computed,
  effect,
  ElementRef,
  inject,
  input,
  OnDestroy,
  OnInit,
  signal,
  viewChild,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { Capacitor } from '@capacitor/core';
import {
  IonButton,
  IonButtons,
  IonChip,
  IonFab,
  IonFabButton,
  IonHeader,
  IonIcon,
  IonLabel,
  IonSpinner,
  IonTitle,
  IonToolbar,
  ModalController,
} from '@ionic/angular/standalone';
import { type CameraPosition, type FlashMode } from 'capacitor-camera-view';
import { concat, map, of, switchMap, tap, timer } from 'rxjs';
import { CapacitorCameraViewService } from '../../core/capacitor-camera-view.service';

function getDistance(touch1: Touch, touch2: Touch): number {
  const dx = touch1.clientX - touch2.clientX;
  const dy = touch1.clientY - touch2.clientY;
  return Math.sqrt(dx * dx + dy * dy);
}

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
    IonLabel,
    IonChip,
  ],
  host: {
    class: 'camera-modal',
  },
})
export class CameraModalComponent implements OnInit, OnDestroy {
  readonly #cameraViewService = inject(CapacitorCameraViewService);
  readonly #elementRef = inject(ElementRef);
  readonly #modalController = inject(ModalController);

  protected barcodeRect =
    viewChild.required<ElementRef<HTMLDivElement>>('barcodeRect');

  public readonly deviceId = input<string>();
  public readonly enableBarcodeDetection = input<boolean>(false);
  public readonly position = input<CameraPosition>('back');
  public readonly quality = input<number>(85);
  public readonly useTripleCameraIfAvailable = input<boolean>(false);
  public readonly initialZoomFactor = input<number>(1.0);
  public readonly saveToFile = input<boolean>(false);

  protected readonly cameraStarted = toSignal(
    this.#cameraViewService.cameraStarted,
    {
      requireSync: true,
    },
  );

  protected readonly flashMode = signal<FlashMode>('auto');
  protected readonly isCapturingPhoto = signal(false);
  protected readonly currentZoomFactor = signal(1.0);
  protected readonly minZoom = signal(1.0);
  protected readonly maxZoom = signal(10.0);

  protected readonly canZoomIn = computed(() => {
    return this.currentZoomFactor() + 0.5 <= this.maxZoom();
  });

  protected readonly canZoomOut = computed(() => {
    return this.currentZoomFactor() - 0.5 >= this.minZoom();
  });

  protected readonly detectedBarcode = toSignal(
    this.#cameraViewService.barcodeData.pipe(
      tap((value) => console.log('Barcode detected:', value)),
      switchMap((value) =>
        concat(of(value), timer(1000).pipe(map(() => undefined))),
      ),
    ),
    { initialValue: undefined },
  );

  protected readonly isWeb = Capacitor.getPlatform() === 'web';

  #supportedFlashModes = signal<Array<FlashMode>>(['off']);

  #touchStartDistance = 0;
  #initialZoomFactorOnPinch = 1.0;

  constructor() {
    effect(() => {
      const flashModes = this.#supportedFlashModes();
      if (!flashModes.includes(this.flashMode())) {
        this.flashMode.set((flashModes[0] as FlashMode) ?? 'off');
      }
    });

    effect(() => {
      const barcodeData = this.detectedBarcode();
      const element = this.barcodeRect().nativeElement;

      if (barcodeData) {
        const boundingRect = barcodeData.boundingRect;

        element.style.visibility = 'visible';
        element.style.opacity = '1';
        element.style.left = `${boundingRect.x - 5}px`;
        element.style.top = `${boundingRect.y - 5}px`;
        element.style.width = `${boundingRect.width + 10}px`;
        element.style.height = `${boundingRect.height + 10}px`;
      } else {
        element.style.opacity = '0';
        element.style.width = `0`;
        element.style.height = `0`;
        element.style.visibility = 'hidden';
      }
    });
  }

  public ngOnInit() {
    this.startCamera().catch((error) => {
      console.error('Failed to start camera', error);
      this.#modalController.dismiss();
    });

    this.#initializeEventListeners();
  }

  public ngOnDestroy(): void {
    this.#destroyEventListeners();
  }

  protected async startCamera(): Promise<void> {
    await this.#cameraViewService.start({
      deviceId: this.deviceId(),
      enableBarcodeDetection: this.enableBarcodeDetection(),
      position: this.position(),
      useTripleCameraIfAvailable: this.useTripleCameraIfAvailable(),
      zoomFactor: this.initialZoomFactor(),
      containerElementId: 'cameraView',
    });

    await Promise.all([
      this.#initializeZoomLimits(),
      this.#initializeFlashModes(),
    ]);

    this.currentZoomFactor.set(this.initialZoomFactor());
  }

  protected async stopCamera(): Promise<void> {
    try {
      await this.#cameraViewService.stop();
    } catch (error) {
      console.error('Failed to stop camera', error);
    }
  }

  protected async close(): Promise<void> {
    this.stopCamera().catch((error) =>
      console.error('Failed to stop camera', error),
    );
    await this.#modalController.dismiss();
  }

  protected async capturePhoto(): Promise<void> {
    this.isCapturingPhoto.set(true);
    try {
      const saveToFile = this.saveToFile();

      if (saveToFile) {
        const result = await this.#cameraViewService.capture({
          quality: this.quality(),
          saveToFile: true,
        });
        this.#modalController.dismiss({
          photo: undefined,
          webPath: result.webPath,
        });
      } else {
        const result = await this.#cameraViewService.capture({
          quality: this.quality(),
          saveToFile: false,
        });
        this.#modalController.dismiss({
          photo: result.photo,
          webPath: undefined,
        });
      }
    } catch (error) {
      console.error('Failed to capture photo', error);
      this.#modalController.dismiss();
    }

    this.stopCamera().catch((error) => {
      console.error('Failed to stop camera', error);
    });

    this.isCapturingPhoto.set(false);
  }

  protected async captureSample(): Promise<void> {
    this.isCapturingPhoto.set(true);
    try {
      const saveToFile = this.saveToFile();

      if (saveToFile) {
        const result = await this.#cameraViewService.captureSample({
          quality: this.quality(),
          saveToFile: true,
        });
        this.#modalController.dismiss({
          photo: undefined,
          webPath: result.webPath,
        });
      } else {
        const result = await this.#cameraViewService.captureSample({
          quality: this.quality(),
          saveToFile: false,
        });
        this.#modalController.dismiss({
          photo: result.photo,
          webPath: undefined,
        });
      }
    } catch (error) {
      console.error('Failed to capture sample', error);
      this.#modalController.dismiss();
    }

    this.stopCamera().catch((error) => {
      console.error('Failed to stop camera', error);
    });

    this.isCapturingPhoto.set(false);
  }

  protected async flipCamera(): Promise<void> {
    await this.#cameraViewService.flipCamera();
    await this.#initializeZoomLimits();
  }

  protected async nextFlashMode(): Promise<void> {
    const supportedModes = this.#supportedFlashModes();
    if (supportedModes.length <= 1) return;

    const currentMode = this.flashMode();
    const currentIndex = supportedModes.indexOf(currentMode);
    const nextIndex = (currentIndex + 1) % supportedModes.length;
    const nextFlashMode = supportedModes[nextIndex] as FlashMode;

    this.flashMode.set(nextFlashMode);
    await this.#cameraViewService.setFlashMode(nextFlashMode);
  }

  protected async zoomIn(): Promise<void> {
    if (this.canZoomIn()) {
      this.currentZoomFactor.update((curr) => curr + 0.5);
      await this.#cameraViewService.setZoom(this.currentZoomFactor(), true);
    }
  }

  protected async zoomOut(): Promise<void> {
    if (this.canZoomOut()) {
      this.currentZoomFactor.update((curr) => curr - 0.5);
      await this.#cameraViewService.setZoom(this.currentZoomFactor(), true);
    }
  }

  protected async readBarcode(): Promise<void> {
    await this.stopCamera();
    await this.#modalController.dismiss({ barcode: this.detectedBarcode() });
  }

  async #setZoom(zoomFactor: number): Promise<void> {
    this.currentZoomFactor.set(zoomFactor);
    await this.#cameraViewService.setZoom(zoomFactor, false);
  }

  async #initializeZoomLimits(): Promise<void> {
    try {
      const zoomRange = await this.#cameraViewService.getZoom();
      if (zoomRange) {
        this.minZoom.set(zoomRange.min);
        this.maxZoom.set(zoomRange.max);
      }
    } catch (error) {
      console.warn('Failed to get zoom range, using default values.', error);
    }
  }

  async #initializeFlashModes(): Promise<void> {
    try {
      this.#supportedFlashModes.set(
        await this.#cameraViewService.getSupportedFlashModes(),
      );
    } catch (error) {
      console.warn('Failed to get supported flash modes', error);
    }
  }

  #initializeEventListeners(): void {
    this.#elementRef.nativeElement.addEventListener(
      'touchstart',
      this.#handleTouchStart,
    );
    this.#elementRef.nativeElement.addEventListener(
      'touchmove',
      this.#handleTouchMove,
    );
  }

  #destroyEventListeners(): void {
    this.#elementRef.nativeElement.removeEventListener(
      'touchstart',
      this.#handleTouchStart,
    );
    this.#elementRef.nativeElement.removeEventListener(
      'touchmove',
      this.#handleTouchMove,
    );
  }

  #handleTouchStart(event: TouchEvent): void {
    if (event.touches.length < 2) return;

    this.#touchStartDistance = getDistance(event.touches[0], event.touches[1]);
    this.#initialZoomFactorOnPinch = this.currentZoomFactor();
  }

  #handleTouchMove(event: TouchEvent): void {
    if (event.touches.length < 2 || this.#touchStartDistance <= 0) return;

    const currentDistance = getDistance(event.touches[0], event.touches[1]);

    // Calculate new zoom factor
    const scale = currentDistance / this.#touchStartDistance;
    const newZoomFactor = Math.max(
      this.minZoom(),
      Math.min(this.maxZoom(), this.#initialZoomFactorOnPinch * scale),
    );

    this.#setZoom(newZoomFactor);
    event.preventDefault(); // Prevent scrolling
  }
}
