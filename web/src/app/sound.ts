/** 轻量按键音：无外部音频资源，用 WebAudio 合成极短提示音。 */
let context: AudioContext | null = null;

function ensureContext(): AudioContext | null {
  if (typeof window === 'undefined') return null;
  const Ctor = window.AudioContext ?? (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
  if (!Ctor) return null;
  if (!context) {
    try {
      context = new Ctor();
    } catch {
      return null;
    }
  }
  if (context.state === 'suspended') void context.resume().catch(() => undefined);
  return context;
}

function blip(frequency: number, duration: number, volume: number): void {
  const audio = ensureContext();
  if (!audio) return;
  const oscillator = audio.createOscillator();
  const gain = audio.createGain();
  const now = audio.currentTime;
  oscillator.type = 'triangle';
  oscillator.frequency.setValueAtTime(frequency, now);
  gain.gain.setValueAtTime(0.0001, now);
  gain.gain.exponentialRampToValueAtTime(volume, now + 0.008);
  gain.gain.exponentialRampToValueAtTime(0.0001, now + duration);
  oscillator.connect(gain).connect(audio.destination);
  oscillator.start(now);
  oscillator.stop(now + duration + 0.02);
}

export function playTap(enabled: boolean): void {
  if (!enabled) return;
  blip(660, 0.05, 0.05);
}

export function playSignal(enabled: boolean): void {
  if (!enabled) return;
  blip(880, 0.09, 0.06);
}

export function playAlert(enabled: boolean): void {
  if (!enabled) return;
  blip(420, 0.14, 0.06);
}
