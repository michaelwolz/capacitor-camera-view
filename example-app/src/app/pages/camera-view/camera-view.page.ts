import { Component, inject, model, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Capacitor } from '@capacitor/core';
import {
  IonButton,
  IonCheckbox,
  IonContent,
  IonHeader,
  IonIcon,
  IonItem,
  IonList,
  IonRange,
  IonSelect,
  IonSelectOption,
  IonTextarea,
  IonTitle,
  IonToolbar,
  ModalController,
} from '@ionic/angular/standalone';
import {
  BarcodeDetectionData,
  CameraDevice,
  FlashMode,
} from 'capacitor-camera-view';
import { CameraModalComponent } from '../../components/camera-modal/camera-modal.component';
import { CapacitorCameraViewService } from '../../core/capacitor-camera-view.service';
import { GalleryService } from '../../services/gallery.service';

@Component({
  selector: 'app-camera-view',
  templateUrl: 'camera-view.page.html',
  imports: [
    FormsModule,
    IonButton,
    IonCheckbox,
    IonContent,
    IonHeader,
    IonIcon,
    IonItem,
    IonList,
    IonRange,
    IonSelect,
    IonSelectOption,
    IonTitle,
    IonToolbar,
    IonTextarea,
  ],
})
export class CameraSettingsPage implements OnInit {
  readonly #cameraViewService = inject(CapacitorCameraViewService);
  readonly #galleryService = inject(GalleryService);
  readonly #modalController = inject(ModalController);

  protected readonly cameraDevices = signal<CameraDevice[]>([]);

  protected deviceId = model<string | null>(null);
  protected enableBarcodeDetection = model<boolean>(false);
  protected position = model<string>('back');
  protected quality = model<number>(85);
  protected useTripleCameraIfAvailable = model<boolean>(false);
  protected initialZoomFactor = model<number>(1.0);
  protected saveToFile = model<boolean>(false);

  protected barcodeValue = signal<string | undefined>(undefined);

  protected readonly isIos = Capacitor.getPlatform() === 'ios';

  ngOnInit() {
    setTimeout(() => {
      this.#cameraViewService.getAvailableDevices().then((devices) => {
        this.cameraDevices.set(devices);
      });
    }, 100);
  }

  protected async startCamera(): Promise<void> {
    const cameraModal = await this.#modalController.create({
      component: CameraModalComponent,
      animated: false,
      componentProps: {
        deviceId: this.deviceId(),
        enableBarcodeDetection: this.enableBarcodeDetection(),
        position: this.position(),
        quality: this.quality(),
        useTripleCameraIfAvailable: this.useTripleCameraIfAvailable(),
        initialZoomFactor: this.initialZoomFactor(),
        saveToFile: this.saveToFile(),
      },
    });

    await cameraModal.present();

    const { data } = await cameraModal.onDidDismiss<{
      photo?: string;
      webPath?: string;
      barcode?: BarcodeDetectionData;
    }>();

    if (data?.photo) {
      this.#galleryService.addPhoto(data.photo);
    } else if (data?.webPath) {
      this.#galleryService.addPhotoFromFile(data.webPath);
    }

    if (data?.barcode) {
      this.barcodeValue.set(data.barcode.value);
    } else {
      this.barcodeValue.set(undefined);
    }
  }

  async isCameraRunning() {
    return this.#cameraViewService.isRunning();
  }

  async getSupportedFlashModes() {
    console.log(await this.#cameraViewService.getSupportedFlashModes());
  }

  async setFlashMode(flashMode: FlashMode) {
    return this.#cameraViewService.setFlashMode(flashMode);
  }

  async getZoom() {
    console.log(await this.#cameraViewService.getZoom());
  }

  async setZoomFactor(zoomFactor: number) {
    return this.#cameraViewService.setZoom(zoomFactor);
  }
}
