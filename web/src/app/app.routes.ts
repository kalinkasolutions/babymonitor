import { Routes } from '@angular/router';
import { DevicesPage } from './pages/devices.page';
import { PairPage } from './pages/pair.page';
import { WatchPage } from './pages/watch.page';

export const routes: Routes = [
  { path: '', component: DevicesPage, title: 'Babymonitor' },
  { path: 'pair', component: PairPage, title: 'Pair a phone · Babymonitor' },
  { path: 'watch', component: WatchPage, title: 'Watching · Babymonitor' },
  { path: '**', redirectTo: '' },
];
