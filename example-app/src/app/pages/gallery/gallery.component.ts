import { Component, inject } from '@angular/core';
import {
  IonButton,
  IonButtons,
  IonCard,
  IonCol,
  IonContent,
  IonGrid,
  IonHeader,
  IonIcon,
  IonImg,
  IonRow,
  IonText,
  IonTitle,
  IonToolbar,
  ModalController,
} from '@ionic/angular/standalone';
import { PhotoViewerComponent } from '../../components/photo-viewer/photo-viewer.component';
import { GalleryItem, GalleryService } from '../../services/gallery.service';

@Component({
  selector: 'app-gallery',
  templateUrl: './gallery.component.html',
  styleUrl: './gallery.component.scss',
  imports: [
    IonButton,
    IonButtons,
    IonCard,
    IonCol,
    IonContent,
    IonGrid,
    IonHeader,
    IonIcon,
    IonImg,
    IonRow,
    IonText,
    IonTitle,
    IonToolbar,
  ],
})
export class GalleryComponent {
  protected readonly galleryService = inject(GalleryService);
  readonly #modalController = inject(ModalController);

  clearGallery() {
    this.galleryService.clearGallery();
  }

  protected async openPhoto(item: GalleryItem): Promise<void> {
    const modal = await this.#modalController.create({
      component: PhotoViewerComponent,
      componentProps: {
        startIndex: this.galleryService.photos().indexOf(item),
      },
    });

    await modal.present();
  }
}
