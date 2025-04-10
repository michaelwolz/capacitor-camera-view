import { Component } from '@angular/core';
import { IonApp, IonRouterOutlet } from '@ionic/angular/standalone';
import { addIcons } from 'ionicons';
import {
  add,
  aperture,
  cameraOutline,
  cameraReverse,
  cameraSharp,
  close,
  closeCircle,
  flash,
  flashOff,
  imagesSharp,
  qrCodeOutline,
  remove,
  search,
  searchOutline,
  sunny,
  sunnyOutline,
} from 'ionicons/icons';

@Component({
  selector: 'app-root',
  templateUrl: 'app.component.html',
  imports: [IonApp, IonRouterOutlet],
})
export class AppComponent {
  constructor() {
    addIcons({
      add,
      aperture,
      cameraOutline,
      cameraReverse,
      cameraSharp,
      close,
      closeCircle,
      flash,
      flashOff,
      imagesSharp,
      qrCodeOutline,
      remove,
      search,
      searchOutline,
      sunny,
      sunnyOutline,
    });
  }
}
