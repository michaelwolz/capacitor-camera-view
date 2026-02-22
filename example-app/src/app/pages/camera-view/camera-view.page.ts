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
  BarcodeType,
  CameraDevice,
  FlashMode,
  VideoRecordingQuality,
} from 'capacitor-camera-view';
import { CameraModalComponent } from '../../components/camera-modal/camera-modal.component';
import { CapacitorCameraViewService } from '../../core/capacitor-camera-view.service';
import { GalleryService } from '../../services/gallery.service';

/**
 * Labels for different barcode types.
 */
const barcodeTypeLabels = {
  aztec: 'Aztec',
  code128: 'Code 128',
  code39: 'Code 39',
  code39Mod43: 'Code 39 Mod 43',
  code93: 'Code 93',
  dataMatrix: 'Data Matrix',
  ean13: 'EAN-13',
  ean8: 'EAN-8',
  interleaved2of5: 'Interleaved 2 of 5',
  itf14: 'ITF-14',
  pdf417: 'PDF417',
  qr: 'QR Code',
  upce: 'UPC-E',
} satisfies Record<BarcodeType, string>;

/**
 * Labels for different video recording qualities.
 */
const videoRecordingQualityLabels = {
  lowest: 'Lowest',
  sd: 'SD',
  hd: 'HD',
  fhd: 'Full HD',
  uhd: 'UHD',
  highest: 'Highest',
} satisfies Record<VideoRecordingQuality, string>;

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
  protected barcodeTypes = model<BarcodeType[]>(['qr']);
  protected position = model<string>('back');
  protected quality = model<number>(85);
  protected useTripleCameraIfAvailable = model<boolean>(false);
  protected initialZoomFactor = model<number>(1.0);
  protected saveToFile = model<boolean>(false);
  protected enableAudio = model<boolean>(true);
  protected videoRecordingQuality = model<VideoRecordingQuality>('hd');

  protected barcodeValue = signal<string | undefined>(undefined);

  protected readonly isIos = Capacitor.getPlatform() === 'ios';

  protected readonly barcodeTypeOptions: {
    label: string;
    value: BarcodeType;
  }[] = (Object.entries(barcodeTypeLabels) as [BarcodeType, string][]).map(
    ([value, label]) => ({ label, value }),
  );

  protected readonly videoRecordingQualityOptions: {
    label: string;
    value: VideoRecordingQuality;
  }[] = (
    Object.entries(videoRecordingQualityLabels) as [
      VideoRecordingQuality,
      string,
    ][]
  ).map(([value, label]) => ({ label, value }));

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
        barcodeTypes: this.barcodeTypes(),
        position: this.position(),
        quality: this.quality(),
        useTripleCameraIfAvailable: this.useTripleCameraIfAvailable(),
        initialZoomFactor: this.initialZoomFactor(),
        saveToFile: this.saveToFile(),
        enableAudio: this.enableAudio(),
        videoRecordingQuality: this.videoRecordingQuality(),
      },
    });

    await cameraModal.present();

    const { data } = await cameraModal.onDidDismiss<{
      photo?: string;
      webPath?: string;
      videoWebPath?: string;
      barcode?: BarcodeDetectionData;
    }>();

    if (data?.photo) {
      this.#galleryService.addPhoto(data.photo);
    } else if (data?.webPath) {
      this.#galleryService.addPhotoFromFile(data.webPath);
    } else if (data?.videoWebPath) {
      this.#galleryService.addVideoFromFile(data.videoWebPath);
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
