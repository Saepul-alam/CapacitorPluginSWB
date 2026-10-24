import { Capacitor } from '@capacitor/core';

import type { SwbPlugin as SwbPluginInterface } from './definitions';

function createPlugin(): SwbPluginInterface {
  // 1. Capacitor 3+
  if (typeof (Capacitor as any)?.registerPlugin === 'function') {
    return (Capacitor as any).registerPlugin('SwbPlugin', {
      web: () => import('./web').then(m => new m.SwbPluginWeb()),
    });
  }

  // 2. Capacitor 2
  const cap = Capacitor as any;
  if (typeof cap?.registerWebPlugin === 'function') {
    import('./web').then(m => {
      cap.registerWebPlugin(new m.SwbPluginWeb());
    });
  }

  return new Proxy({} as SwbPluginInterface, {
    get(_target, prop) {
      const plugin =
        cap?.Plugins?.SwbPlugin ||
        (typeof window !== 'undefined' &&
          (window as any)?.Capacitor?.Plugins?.SwbPlugin);

      if (plugin && typeof plugin[prop] === 'function') {
        return plugin[prop].bind(plugin);
      }

      return (...args: any[]) =>
        import('./web').then(m => {
          const webInstance = new m.SwbPluginWeb();
          if (typeof (webInstance as any)[prop] === 'function') {
            return (webInstance as any)[prop](...args);
          }
          throw new Error(
            `Method ${String(prop)} is not implemented on SwbPlugin`,
          );
        });
    },
  });
}

const SwbPlugin = createPlugin();

export * from './definitions';
export { SwbPlugin };
