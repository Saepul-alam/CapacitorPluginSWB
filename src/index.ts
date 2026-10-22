import { registerPlugin } from '@capacitor/core';

import type { SwbPlugin } from './definitions';

const Swb = registerPlugin<SwbPlugin>('Swb', {
  web: () => import('./web').then(m => new m.SwbWeb()),
});

export * from './definitions';
export { Swb };
