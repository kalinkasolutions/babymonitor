import { Injectable, signal } from '@angular/core';

export interface Alarm {
  title: string;
  text: string;
}

/** An alarm in an empty house should not ring until somebody comes home. */
const RingLimitMillis = 10 * 60_000;
const BeepEveryMillis = 1_200;

/**
 * Raises an alarm while this tab is open: a system notification, and a tone played here and looped
 * until somebody acknowledges it, because a notification alone plays once and is easy to sleep through.
 */
@Injectable({ providedIn: 'root' })
export class AlarmService {
  readonly ringing = signal<Alarm | null>(null);
  readonly permission = signal<NotificationPermission | 'unsupported'>(
    'Notification' in window ? Notification.permission : 'unsupported',
  );

  private audio: AudioContext | null = null;
  private beeper: ReturnType<typeof setInterval> | null = null;
  private limit: ReturnType<typeof setTimeout> | null = null;
  private notification: Notification | null = null;

  /** Browsers only let a page make sound after a click, so the first click prepares it. */
  prepare(): void {
    this.audio ??= new AudioContext();
    void this.audio.resume();
    if (this.permission() === 'default') {
      void Notification.requestPermission().then((result) => this.permission.set(result));
    }
  }

  noise(room: string): void {
    this.raise({ title: 'Noise in the room', text: `${room} has been loud for a while.` });
  }

  lost(room: string, thisBrowserOffline: boolean): void {
    this.raise(
      thisBrowserOffline
        ? { title: 'This computer is offline', text: `It cannot hear ${room}. Check its network.` }
        : {
            title: 'Lost the room',
            text: `${room} stopped answering. It may be off, asleep or out of signal.`,
          },
    );
  }

  /** Rings exactly like a real alarm, so sound and notification can be checked before the night. */
  test(): void {
    this.prepare();
    this.raise({
      title: 'Test alarm',
      text: 'This is how the alarm sounds. Press "I\'m awake" to stop it.',
    });
  }

  silence(): void {
    this.ringing.set(null);
    this.notification?.close();
    this.notification = null;
    if (this.beeper) {
      clearInterval(this.beeper);
      this.beeper = null;
    }
    if (this.limit) {
      clearTimeout(this.limit);
      this.limit = null;
    }
  }

  private raise(alarm: Alarm): void {
    console.warn(`Raising an alarm: ${alarm.title} — ${alarm.text}`);
    this.silence();
    this.ringing.set(alarm);

    if (this.permission() === 'granted') {
      this.notification = new Notification(alarm.title, {
        body: alarm.text,
        requireInteraction: true,
        tag: 'babymonitor-alarm',
      });
      this.notification.onclick = () => {
        window.focus();
        this.silence();
      };
    }

    this.beep();
    this.beeper = setInterval(() => this.beep(), BeepEveryMillis);
    this.limit = setTimeout(() => this.silence(), RingLimitMillis);
  }

  /** Two short tones, generated rather than loaded, so nothing has to be fetched while it rings. */
  private beep(): void {
    const audio = this.audio;
    if (!audio) {
      return;
    }
    for (const [offset, frequency] of [
      [0, 880],
      [0.25, 660],
    ]) {
      const oscillator = audio.createOscillator();
      const gain = audio.createGain();
      const start = audio.currentTime + offset;
      oscillator.frequency.value = frequency;
      gain.gain.setValueAtTime(0.0001, start);
      gain.gain.exponentialRampToValueAtTime(0.4, start + 0.02);
      gain.gain.exponentialRampToValueAtTime(0.0001, start + 0.2);
      oscillator.connect(gain).connect(audio.destination);
      oscillator.start(start);
      oscillator.stop(start + 0.22);
    }
  }
}
