// SHOP-10: the camera, the detector and the torch behind one small interface, so jsdom tests use a fake.

export interface ScanSession {
  /** `true` when the camera has a torch (track capabilities). */
  torch: boolean;
  setTorch(on: boolean): Promise<void>;
  stop(): void;
}

export interface ScannerDevice {
  /** `false` where the browser has no BarcodeDetector or no camera API; the scanner then offers manual entry only. */
  supported(): boolean;
  /** Starts the camera into `video` and calls `onCode` for each raw value read. Rejects with `NotAllowedError` when denied. */
  start(video: HTMLVideoElement, onCode: (raw: string) => void): Promise<ScanSession>;
}

type Detector = { detect(source: CanvasImageSource): Promise<Array<{ rawValue: string }>> };
type DetectorCtor = new (opts: { formats: string[] }) => Detector;

const FORMATS = ['ean_13', 'ean_8', 'upc_a', 'upc_e'];
const POLL_MS = 200;

const detectorCtor = (): DetectorCtor | undefined => (globalThis as { BarcodeDetector?: DetectorCtor }).BarcodeDetector;

export const browserScannerDevice: ScannerDevice = {
  supported: () => detectorCtor() !== undefined && typeof navigator.mediaDevices?.getUserMedia === 'function',
  async start(video, onCode) {
    const Ctor = detectorCtor();
    if (!Ctor) throw new DOMException('BarcodeDetector missing', 'NotSupportedError');
    const stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' } });
    const detector = new Ctor({ formats: FORMATS });
    video.srcObject = stream;
    await video.play().catch(() => undefined);
    const track = stream.getVideoTracks()[0];
    const caps = (track?.getCapabilities?.() ?? {}) as { torch?: boolean };
    let busy = false;
    const timer = setInterval(() => {
      if (busy) return;
      busy = true;
      detector
        .detect(video)
        .then((found) => {
          const first = found[0];
          if (first) onCode(first.rawValue);
        })
        .catch(() => undefined)
        .finally(() => {
          busy = false;
        });
    }, POLL_MS);
    return {
      torch: caps.torch === true,
      async setTorch(on) {
        await track?.applyConstraints({ advanced: [{ torch: on } as MediaTrackConstraintSet] });
      },
      stop() {
        clearInterval(timer);
        stream.getTracks().forEach((t) => t.stop());
        video.srcObject = null;
      },
    };
  },
};
