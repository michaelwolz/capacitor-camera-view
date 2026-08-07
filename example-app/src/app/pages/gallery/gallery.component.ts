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
} from '@ionic/angular/standalone';
import { GalleryService } from '../../services/gallery.service';

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

  clearGallery() {
    this.galleryService.clearGallery();
  }
}
