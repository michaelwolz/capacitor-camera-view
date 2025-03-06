import { Component, effect, inject, signal } from '@angular/core';
import {
  IonHeader,
  IonToolbar,
  IonTitle,
  IonContent,
  IonButton,
} from '@ionic/angular/standalone';
import { CameraViewService } from '../../services/camera-view.service';
import { FlashMode } from 'capacitor-camera-view';
import { Capacitor } from '@capacitor/core';

@Component({
  selector: 'app-camera-view',
  templateUrl: 'camera-view.page.html',
  imports: [IonHeader, IonToolbar, IonTitle, IonContent, IonButton],
})
export class CameraViewPage {
  readonly #cameraViewService = inject(CameraViewService);

  readonly isWeb = Capacitor.getPlatform() === 'web';

  protected readonly photos = signal<string[]>([]);

  protected cameraRunning = signal(false);

  constructor() {
    effect(() => {
      document.body.classList.toggle('camera-running', this.cameraRunning());
    });
  }

  async startCamera() {
    await this.#cameraViewService.start();
    this.cameraRunning.set(true);
  }

  async stopCamera() {
    await this.#cameraViewService.stop();
    this.cameraRunning.set(false);
  }

  async isCameraRunning() {
    return this.#cameraViewService.isRunning();
  }

  async capturePhoto() {
    const photo = await this.#cameraViewService.capture();
    this.photos.update((photos) => [...photos, photo]);
  }

  async getSupportedFlashModes() {
    return this.#cameraViewService.getSupportedFlashModes();
  }

  async setFlashMode(flashMode: FlashMode) {
    return this.#cameraViewService.setFlashMode(flashMode);
  }

  async getZoom() {
    return this.#cameraViewService.getZoom();
  }

  async setZoomLevel(zoomLevel: number) {
    return this.#cameraViewService.setZoomLevel(zoomLevel);
  }
}
