import type { SyncEngine } from './SyncEngine.ts';

// Hand-written fake (TESTING §7): records how often the screen asked for a sync.
export class FakeSync implements SyncEngine {
  kicks = 0;

  kick(): Promise<void> {
    this.kicks += 1;
    return Promise.resolve();
  }
}
