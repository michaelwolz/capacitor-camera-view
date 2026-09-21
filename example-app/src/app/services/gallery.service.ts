import { computed, Injectable, signal } from '@angular/core';

export interface GalleryItem {
  type: 'photo' | 'video';
  data: string;
}

@Injectable({
  providedIn: 'root',
})
export class GalleryService {
  readonly #items = signal<GalleryItem[]>([]);
  public items = this.#items.asReadonly();
  public photos = computed(() =>
    this.#items().filter((item) => item.type === 'photo'),
  );

  public addPhoto(photo: string) {
    this.#items.update((curr) => [
      ...curr,
      { type: 'photo', data: `data:image/jpeg;base64,${photo}` },
    ]);
  }

  public addPhotoFromFile(filePath: string) {
    this.#items.update((curr) => [...curr, { type: 'photo', data: filePath }]);
  }

  public addVideoFromFile(filePath: string) {
    this.#items.update((curr) => [...curr, { type: 'video', data: filePath }]);
  }

  public clearGallery() {
    this.#items.set([]);
  }
}
