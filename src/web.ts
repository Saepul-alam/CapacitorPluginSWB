import { WebPlugin } from '@capacitor/core';

import type { SwbPlugin } from './definitions';

export class SwbWeb extends WebPlugin implements SwbPlugin {
  async echo(options: { value: string }): Promise<{ value: string }> {
    console.log('ECHO', options);
    return options;
  }
}
