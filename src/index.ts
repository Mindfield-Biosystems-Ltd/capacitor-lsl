import { registerPlugin } from '@capacitor/core';

import type { LSLPlugin } from './definitions';

const LSL = registerPlugin<LSLPlugin>('LSL', {
  web: () => import('./web').then((m) => new m.LSLWeb()),
});

export * from './definitions';
export { LSL };
