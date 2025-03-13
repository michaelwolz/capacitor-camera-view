import { Component } from '@angular/core';
import { IonApp, IonRouterOutlet } from '@ionic/angular/standalone';
import { addIcons } from 'ionicons';
import {
  cameraOutline,
  cameraReverse,
  cameraSharp,
  close,
  flash,
  flashOff,
  imagesSharp,
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
      cameraSharp,
      cameraOutline,
      imagesSharp,
      sunnyOutline,
      sunny,
      searchOutline,
      search,
      close,
      flash,
      flashOff,
      cameraReverse
    });
  }
}
