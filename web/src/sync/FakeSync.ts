import type { SyncEngine, SyncPhase } from './SyncEngine.ts';

// Hand-written fake (TESTING §7): records how often the screen asked for a sync and what the sheet asked for.
export class FakeSync implements SyncEngine {
  kicks = 0;
  retried: number[] = [];
  discarded: number[] = [];
  private phase: SyncPhase = 'idle';
  private listeners = new Set<() => void>();

  kick(): Promise<void> {
    this.kicks += 1;
    return Promise.resolve();
  }

  retry(seq: number): Promise<void> {
    this.retried.push(seq);
    return Promise.resolve();
  }

  discard(seq: number): Promise<void> {
    this.discarded.push(seq);
    return Promise.resolve();
  }

  // Arrow properties: React calls these detached from the instance.
  getPhase = (): SyncPhase => this.phase;

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  setPhase(phase: SyncPhase): void {
    this.phase = phase;
    this.listeners.forEach((l) => l());
  }
}
