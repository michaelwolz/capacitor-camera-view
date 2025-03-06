import { inject, Injectable } from '@angular/core';
import { CapacitorCameraViewService } from '../core/capacitor-camera-view.service';
import { FlashMode } from 'capacitor-camera-view';

@Injectable({
  providedIn: 'root',
})
export class CameraViewService {
  readonly #capacitorCameraView = inject(CapacitorCameraViewService);

  async start() {
    return this.#capacitorCameraView.start();
  }

  async stop() {
    return this.#capacitorCameraView.stop();
  }

  async isRunning() {
    return this.#capacitorCameraView.isRunning();
  }

  async capture() {
    return this.#capacitorCameraView.capture();
  }

  async getSupportedFlashModes() {
    return this.#capacitorCameraView.getSupportedFlashModes();
  }

  async setFlashMode(flashMode: FlashMode) {
    return this.#capacitorCameraView.setFlashMode(flashMode);
  }

  async getZoom() {
    return this.#capacitorCameraView.getZoom();
  }

  async setZoomLevel(zoomLevel: number) {
    return this.#capacitorCameraView.setZoom(zoomLevel);
  }
}
