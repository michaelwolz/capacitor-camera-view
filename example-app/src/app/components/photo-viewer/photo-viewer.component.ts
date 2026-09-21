import {
  Component,
  computed,
  inject,
  input,
  linkedSignal,
} from '@angular/core';
import {
  IonButton,
  IonButtons,
  IonContent,
  IonFab,
  IonFabButton,
  IonHeader,
  IonIcon,
  IonTitle,
  IonToolbar,
  ModalController,
} from '@ionic/angular/standalone';
import { GalleryService } from '../../services/gallery.service';

@Component({
  selector: 'app-photo-viewer',
  templateUrl: './photo-viewer.component.html',
  styleUrl: './photo-viewer.component.scss',
  imports: [
    IonButton,
    IonButtons,
    IonContent,
    IonFab,
    IonFabButton,
    IonHeader,
    IonIcon,
    IonTitle,
    IonToolbar,
  ],
})
export class PhotoViewerComponent {
  readonly #galleryService = inject(GalleryService);
  readonly #modalController = inject(ModalController);

  public readonly startIndex = input<number>(0);

  protected readonly photos = this.#galleryService.photos;

  protected readonly currentIndex = linkedSignal(() => this.startIndex());

  protected readonly currentPhoto = computed(
    () => this.photos()[this.currentIndex()],
  );

  protected readonly canShowPrevious = computed(() => this.currentIndex() > 0);

  protected readonly canShowNext = computed(
    () => this.currentIndex() < this.photos().length - 1,
  );

  protected previous(): void {
    if (this.canShowPrevious()) {
      this.currentIndex.update((index) => index - 1);
    }
  }

  protected next(): void {
    if (this.canShowNext()) {
      this.currentIndex.update((index) => index + 1);
    }
  }

  protected async close(): Promise<void> {
    await this.#modalController.dismiss();
  }
}
