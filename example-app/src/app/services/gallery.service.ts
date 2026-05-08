import { Injectable, signal } from '@angular/core';

@Injectable({
  providedIn: 'root',
})
export class GalleryService {
  readonly #items = signal<Array<{ type: 'photo' | 'video'; data: string }>>(
    [],
  );
  public items = this.#items.asReadonly();

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
