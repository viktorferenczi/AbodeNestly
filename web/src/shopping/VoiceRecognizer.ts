// SHOP-14: the browser's speech recognition behind one small interface, so jsdom tests use a fake.

export type VoiceError = 'not-allowed' | 'no-speech' | 'other';

export interface VoiceHandlers {
  /** The transcript so far; `final` once the recognizer has settled on it. */
  onTranscript(text: string, final: boolean): void;
  onError(error: VoiceError): void;
  /** The recognizer stopped listening, whatever the reason. */
  onEnd(): void;
}

export interface VoiceRecognizer {
  /** `false` where the browser has no SpeechRecognition (Firefox). */
  supported(): boolean;
  /** `true` when the browser can recognise speech on the device, so it also works offline (Chromium's `processLocally`). */
  canProcessLocally(): boolean;
  /** The microphone permission as far as the browser says; `prompt` when it cannot tell. */
  permission(): Promise<'granted' | 'denied' | 'prompt'>;
  /** Starts listening and returns the function that stops it. `offline` asks for on-device recognition. */
  start(handlers: VoiceHandlers, opts: { offline: boolean }): () => void;
}

type Result = { isFinal: boolean; 0: { transcript: string } };
type RecognitionEvent = { results: ArrayLike<Result> };
type Recognition = {
  lang: string;
  interimResults: boolean;
  continuous: boolean;
  maxAlternatives: number;
  processLocally?: boolean;
  onresult: ((e: RecognitionEvent) => void) | null;
  onerror: ((e: { error: string }) => void) | null;
  onend: (() => void) | null;
  start(): void;
  abort(): void;
};
type RecognitionCtor = { new (): Recognition; prototype: Recognition };

const ctor = (): RecognitionCtor | undefined => {
  const w = globalThis as { SpeechRecognition?: RecognitionCtor; webkitSpeechRecognition?: RecognitionCtor };
  return w.SpeechRecognition ?? w.webkitSpeechRecognition;
};

export const browserVoiceRecognizer: VoiceRecognizer = {
  supported: () => ctor() !== undefined,
  canProcessLocally: () => {
    const C = ctor();
    return C !== undefined && 'processLocally' in C.prototype;
  },
  async permission() {
    try {
      const status = await navigator.permissions.query({ name: 'microphone' as PermissionName });
      return status.state;
    } catch {
      return 'prompt';
    }
  },
  start(handlers, opts) {
    const C = ctor();
    if (!C) {
      handlers.onError('other');
      handlers.onEnd();
      return () => undefined;
    }
    const fail = (e: unknown) => {
      const n = e instanceof Error ? e.name : '';
      handlers.onError(n === 'NotAllowedError' || n === 'SecurityError' ? 'not-allowed' : 'other');
      handlers.onEnd();
    };
    let rec: Recognition;
    try {
      rec = new C();
      rec.lang = navigator.language;
      rec.interimResults = true;
      rec.continuous = false;
      rec.maxAlternatives = 1;
      if (opts.offline) rec.processLocally = true;
      rec.onresult = (e) => {
        const results = Array.from(e.results);
        handlers.onTranscript(results.map((r) => r[0].transcript).join(' ').trim(), results[results.length - 1]?.isFinal === true);
      };
      rec.onerror = (e) => handlers.onError(e.error === 'not-allowed' || e.error === 'service-not-allowed' ? 'not-allowed' : e.error === 'no-speech' ? 'no-speech' : 'other');
      rec.onend = () => handlers.onEnd();
      rec.start();
    } catch (e) {
      // start() throws when the recognizer is already running or the page may not use the microphone.
      fail(e);
      return () => undefined;
    }
    const started = rec;
    return () => {
      started.onresult = started.onerror = started.onend = null;
      started.abort();
    };
  },
};
