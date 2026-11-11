import {
  Component,
  DestroyRef,
  ElementRef,
  computed,
  effect,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { CaptureQualities, CaptureQuality, GlanceSeconds } from '../call/call-session';
import { NoiseAlarmSettings } from '../call/noise-alarm';
import { LightMode } from '../protocol/signal';
import { AlarmService } from '../services/alarm.service';
import { CallsService } from '../services/calls.service';
import { HouseholdService } from '../services/household.service';
import { NoiseMeter } from '../ui/noise-meter';
import { StatusOverlay } from '../ui/status-overlay';

/** Fast enough to feel like a dimmer, slow enough not to flood the other phone. */
const BrightnessIntervalMillis = 80;
const KeepScreenOnKey = 'babymonitor.keepScreenOn';

/** The room, while you watch it. Everything here is about the other phone. */
@Component({
  selector: 'app-watch',
  imports: [RouterLink, NoiseMeter, StatusOverlay],
  styleUrl: './watch.page.css',
  template: `
    @if (!active()) {
      <section class="card empty">
        <h2>Not watching anything</h2>
        <p class="muted">Pick a phone in the list to listen to or watch.</p>
        <a routerLink="/"><button class="primary">Back to the list</button></a>
      </section>
    } @else {
      <div class="layout">
        <div class="room" #room [style.--ratio]="ratio()">
          @if (stream() && session().video) {
            <video
              autoplay
              playsinline
              muted
              [srcObject]="stream()"
              (playing)="pictureShown.set(true)"
              (resize)="measure($event)"
              (loadedmetadata)="measure($event)"
            ></video>
          }
          @if (!session().video || !pictureShown()) {
            <p class="placeholder">
              {{ session().video ? 'Waiting for the picture…' : 'Listening. Camera is off.' }}
            </p>
          }

          <div class="title">
            <strong>{{ peerName() }}</strong>
            @if (problem() || !live()) {
              <span [class.problem]="problem()">{{ line() }}</span>
            }
          </div>
          <app-noise-meter
            class="meter"
            [level]="calls.noise()"
            [threshold]="alarm().threshold"
            [arming]="calls.arming()"
          />
          @if (calls.peerStatus() || calls.path() !== 'unknown') {
            <app-status-overlay
              class="status"
              [status]="calls.peerStatus()"
              [path]="calls.path()"
            />
          }

          <button class="pill stop" (click)="stop()">
            <svg viewBox="0 0 24 24" width="14" height="14">
              <rect x="5" y="5" width="14" height="14" rx="2" fill="currentColor" />
            </svg>
            {{ live() ? 'Stop watching' : 'Cancel' }}
          </button>

          <div class="controls">
            <button
              class="round"
              (click)="calls.showVideo(!session().video)"
              [attr.aria-label]="session().video ? 'Turn the camera off' : 'Turn the camera on'"
              [title]="session().video ? 'Camera off' : 'Camera on'"
            >
              <svg viewBox="0 0 24 24" width="22" height="22">
                <path
                  d="M3 7h12v10H3zM15 10l6-3v10l-6-3z"
                  fill="none"
                  stroke="#fff"
                  stroke-width="1.8"
                  stroke-linejoin="round"
                />
                @if (!session().video) {
                  <path d="M3 4l18 16" stroke="#fff" stroke-width="1.8" />
                }
              </svg>
            </button>
            <button
              class="round"
              (click)="calls.toggleMute()"
              [attr.aria-label]="calls.muted() ? 'Sound on' : 'Sound off'"
              [title]="calls.muted() ? 'Sound on' : 'Sound off'"
            >
              <svg viewBox="0 0 24 24" width="22" height="22">
                <path d="M4 9h4l5-4v14l-5-4H4z" fill="#fff" />
                @if (calls.muted()) {
                  <path d="M16 9l5 6M21 9l-5 6" stroke="#fff" stroke-width="1.8" />
                } @else {
                  <path
                    d="M16 8.5a5 5 0 0 1 0 7M18.5 6a8.5 8.5 0 0 1 0 12"
                    fill="none"
                    stroke="#fff"
                    stroke-width="1.8"
                  />
                }
              </svg>
            </button>
            <select
              class="pill quality"
              (change)="chooseQuality($event)"
              aria-label="Picture quality"
              [title]="qualityDetail()"
            >
              @for (option of qualities; track option.value) {
                <option [value]="option.value" [selected]="option.value === session().quality">
                  {{ option.label }}
                </option>
              }
            </select>
            <button
              class="round"
              (click)="toggleFullscreen()"
              aria-label="Full screen"
              title="Full screen"
            >
              <svg viewBox="0 0 24 24" width="20" height="20">
                <path
                  d="M4 9V4h5M15 4h5v5M20 15v5h-5M9 20H4v-5"
                  fill="none"
                  stroke="#fff"
                  stroke-width="2"
                />
              </svg>
            </button>
          </div>
        </div>

        <aside class="panel">
          <section>
            <h2>Light</h2>
            <div class="chips">
              @for (mode of lightModes; track mode) {
                <button
                  [class.selected]="session().light.mode === mode"
                  (click)="setLight(mode)"
                  [disabled]="!live()"
                >
                  {{ mode }}
                </button>
              }
              <button
                class="text"
                (click)="glance()"
                [disabled]="!live()"
                title="White, full brightness, for {{ glanceSeconds }} seconds"
              >
                Glance
              </button>
            </div>
            <input
              type="range"
              min="0"
              max="100"
              [value]="brightness() * 100"
              (input)="dragBrightness($event)"
              (change)="releaseBrightness()"
              [disabled]="!live()"
              aria-label="Light brightness"
            />
            <p class="muted small">
              {{ round(brightness() * 100) }}%. Lights the room with the phone's own screen.
            </p>
          </section>

          <label class="switch">
            <input type="checkbox" [checked]="keepScreenOn()" (change)="setKeepScreenOn($event)" />
            Keep this screen on
          </label>

          <section>
            <h2>Alarm</h2>
            <p class="small" [class.muted]="alarmHeldBack()">{{ alarmLine() }}</p>
            <label class="switch">
              <input
                type="checkbox"
                [checked]="alarm().enabled"
                (change)="updateAlarm({ enabled: checked($event) })"
              />
              When the room is loud
            </label>
            <label>
              Starts at {{ round(alarm().threshold * 100) }}% on the meter (the white line)
              <input
                type="range"
                min="10"
                max="90"
                [value]="alarm().threshold * 100"
                (change)="updateAlarm({ threshold: number($event) / 100 })"
              />
            </label>
            <label>
              At that level, after {{ alarm().seconds }} seconds. Louder is quicker.
              <input
                type="range"
                min="5"
                max="60"
                [value]="alarm().seconds"
                (change)="updateAlarm({ seconds: number($event) })"
              />
            </label>
            <label class="switch">
              <input
                type="checkbox"
                [checked]="alarm().warnOffline"
                (change)="updateAlarm({ warnOffline: checked($event) })"
              />
              When the room stops answering
            </label>
            <button (click)="alarms.test()">Test the alarm</button>
            <p class="muted small">
              It rings only while this tab is open, and not while you watch with the sound on: then
              you hear the room yourself.
            </p>
          </section>
        </aside>
      </div>
    }
  `,
})
export class WatchPage {
  protected readonly calls = inject(CallsService);
  protected readonly alarms = inject(AlarmService);
  private readonly household = inject(HouseholdService);
  private readonly router = inject(Router);
  private readonly room = viewChild<ElementRef<HTMLElement>>('room');

  protected readonly qualities = CaptureQualities;
  protected readonly lightModes: LightMode[] = ['Off', 'White', 'Red'];
  protected readonly glanceSeconds = GlanceSeconds;
  protected readonly round = Math.round;

  protected readonly session = this.calls.session;
  protected readonly alarm = this.calls.alarmSettings;
  protected readonly stream = this.calls.remoteStream;
  protected readonly pictureShown = signal(false);
  /** The picture's own shape, so a phone filming upright is not boxed into a wide frame. */
  protected readonly ratio = signal(4 / 3);
  protected readonly qualityDetail = computed(
    () => CaptureQualities.find((option) => option.value === this.session().quality)?.detail ?? '',
  );
  protected readonly alarmHeldBack = computed(
    () => this.alarm().enabled && this.calls.watching() && !this.calls.muted(),
  );
  protected readonly alarmLine = computed(() => {
    if (!this.alarm().enabled) {
      return 'Off for noise.';
    }
    if (this.alarmHeldBack()) {
      return 'Held back: you are watching with the sound on. It rings when the sound is off or this tab is hidden.';
    }
    return `Ready. The room is ${Math.round(this.calls.arming() * 100)}% of the way to an alarm.`;
  });
  protected readonly keepScreenOn = signal(localStorage.getItem(KeepScreenOnKey) === 'true');
  protected readonly brightness = signal(this.session().light.brightness);

  private readonly peerId = computed(() => this.calls.peer() ?? this.calls.wanted()?.peer ?? null);
  protected readonly active = computed(
    () => this.peerId() !== null || this.calls.state().kind === 'failed',
  );
  protected readonly live = computed(() => this.calls.state().kind === 'live');
  protected readonly peerName = computed(() => {
    const peer = this.peerId();
    return peer ? this.household.nameOf(peer) : 'The room';
  });
  protected readonly problem = computed(
    () =>
      this.calls.offline() ||
      this.calls.retrying() ||
      this.calls.silent() ||
      this.calls.state().kind === 'failed',
  );
  protected readonly line = computed(() => {
    const name = this.peerName();
    const state = this.calls.state();
    // This computer's own network first: otherwise the room gets the blame for it.
    if (this.calls.offline()) {
      return 'This computer is offline. Check its network.';
    }
    if (this.calls.retrying()) {
      return 'Lost the room. Trying again.';
    }
    if (this.calls.silent()) {
      return `Nothing heard from ${name} for a while.`;
    }
    switch (state.kind) {
      case 'asking':
        return `Asking ${name} to send…`;
      case 'negotiating':
        return `Connecting to ${name}…`;
      case 'live':
        return `Listening to ${name}.`;
      case 'failed':
        return state.reason;
      case 'idle':
        return 'Nothing running.';
    }
  });

  private wakeLock: WakeLockSentinel | null = null;
  private lastBrightnessSent = 0;
  private pendingBrightness: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    const onVisibility = () => {
      this.calls.setWatching(document.visibilityState === 'visible');
      void this.holdScreen();
    };
    document.addEventListener('visibilitychange', onVisibility);
    onVisibility();
    inject(DestroyRef).onDestroy(() => {
      document.removeEventListener('visibilitychange', onVisibility);
      this.calls.setWatching(false);
      void this.wakeLock?.release();
    });

    effect(() => {
      if (!this.stream() || !this.session().video) {
        this.pictureShown.set(false);
      }
    });
  }

  protected measure(event: Event): void {
    const video = event.target as HTMLVideoElement;
    if (video.videoWidth && video.videoHeight) {
      this.ratio.set(video.videoWidth / video.videoHeight);
    }
  }

  protected stop(): void {
    this.calls.hangUp();
    void this.router.navigate(['/']);
  }

  protected chooseQuality(event: Event): void {
    this.calls.chooseQuality((event.target as HTMLSelectElement).value as CaptureQuality);
  }

  protected setLight(mode: LightMode): void {
    this.calls.askForLight(mode, mode === 'Off' ? 0 : this.brightness());
  }

  /** The same light with an end to it, so a look at the room cannot be left on. */
  protected glance(): void {
    this.calls.askForLight('White', 1, GlanceSeconds);
  }

  /** Sent while dragging, so the room brightens under the pointer, but sampled to avoid a flood. */
  protected dragBrightness(event: Event): void {
    this.brightness.set(this.number(event) / 100);
    const wait = BrightnessIntervalMillis - (Date.now() - this.lastBrightnessSent);
    if (this.pendingBrightness) {
      return;
    }
    this.pendingBrightness = setTimeout(
      () => {
        this.pendingBrightness = null;
        this.sendBrightness();
      },
      Math.max(0, wait),
    );
  }

  /** The last value always goes, in case the drag ended inside a sampling interval. */
  protected releaseBrightness(): void {
    if (this.pendingBrightness) {
      clearTimeout(this.pendingBrightness);
      this.pendingBrightness = null;
    }
    this.sendBrightness();
  }

  private sendBrightness(): void {
    this.lastBrightnessSent = Date.now();
    const mode = this.session().light.mode;
    this.calls.askForLight(mode === 'Off' ? 'White' : mode, this.brightness());
  }

  protected toggleFullscreen(): void {
    if (document.fullscreenElement) {
      void document.exitFullscreen();
    } else {
      void this.room()?.nativeElement.requestFullscreen();
    }
  }

  protected setKeepScreenOn(event: Event): void {
    this.keepScreenOn.set(this.checked(event));
    localStorage.setItem(KeepScreenOnKey, String(this.keepScreenOn()));
    void this.holdScreen();
  }

  /** A wake lock is dropped whenever the tab is hidden, so it is taken again on coming back. */
  private async holdScreen(): Promise<void> {
    if (!('wakeLock' in navigator)) {
      return;
    }
    if (!this.keepScreenOn()) {
      await this.wakeLock?.release();
      this.wakeLock = null;
      return;
    }
    if (document.visibilityState === 'visible' && (!this.wakeLock || this.wakeLock.released)) {
      this.wakeLock = await navigator.wakeLock.request('screen').catch(() => null);
    }
  }

  protected updateAlarm(change: Partial<NoiseAlarmSettings>): void {
    this.calls.setAlarm({ ...this.alarm(), ...change });
  }

  protected checked(event: Event): boolean {
    return (event.target as HTMLInputElement).checked;
  }

  protected number(event: Event): number {
    return Number((event.target as HTMLInputElement).value);
  }
}
