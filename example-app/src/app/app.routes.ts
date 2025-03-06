import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    path: '',
    loadComponent: () => import('./pages/camera-view/camera-view.page').then((m) => m.CameraViewPage),
  },
];
