import 'fake-indexeddb/auto';

// jsdom has no PointerEvent, so fireEvent.pointerX would drop clientX/clientY.
if (typeof window !== 'undefined' && !('PointerEvent' in window)) {
  Object.defineProperty(window, 'PointerEvent', { value: class PointerEvent extends MouseEvent {}, configurable: true });
}
