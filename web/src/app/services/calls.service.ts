import { Injectable, computed, effect, inject, signal } from '@angular/core';
import { Subject } from 'rxjs';
import {
  CallEffect,
  CallEvent,
  CallSession,
  CaptureQuality,
  IdleSession,
  onCallEvent,
  peerOf,
} from '../call/call-session';
import {
  NoiseAlarmSettings,
  DefaultAlarmSettings,
  NoiseWatch,
  newNoiseWatch,
  onNoise,
} from '../call/noise-alarm';
import { Reachability, reachabilityOf } from '../call/reachability';
import {
  Availability,
  IceCandidateDto,
  LightMode,
  PhoneStatus,
  RoomReport,
  SignalKinds,
  SignalMessage,
  SignalReplayGuard,
  StopReasons,
  parseAvailability,
  phoneStatusOf,
  trustOf,
} from '../protocol/signal';
import { AlarmService } from './alarm.service';
import { HouseholdService } from './household.service';
import { HubService, SignalResult } from './hub.service';
import { IdentityService } from './identity.service';

export type CallPath = 'unknown' | 'lan' | 'direct' | 'relayed';

export const CallPathLabels: Record<CallPath, string> = {
  unknown: 'Connecting…',
  lan: 'On the WiFi',
  direct: 'Direct over the internet',
  relayed: 'Through the relay',
};

interface IceServers {
  urls: string[];
  username: string;
  credential: string;
  expiresAt: string;
}

/** Noise arrives five times a second, so this much quiet is not a hiccup. */
const SilenceLimitMillis = 15_000;
const SilenceCheckMillis = 2_000;
/** Quick at first, because most drops are a phone changing network. */
const BackoffMillis = [2_000, 5_000, 15_000, 30_000];
/** Long enough for a phone hopping between networks, short enough to be worth waiting. */
const DropGraceMillis = 8_000;
const CandidateResends = 6;
const CandidateResendDelayMillis = 1_000;
/** Asking and negotiating both take seconds; this much means nobody is there. */
const AnswerLimitMillis = 15_000;
const PathIntervalMillis = 5_000;
const IceCheckIntervalMillis = 5 * 60_000;
/** Renewed early, so a laptop that slept a while still wakes with working credentials. */
const RenewBeforeExpiryMillis = 60 * 60_000;
const AlarmSettingsKey = 'babymonitor.alarm';

/**
 * The call, for as long as the tab is open: the browser's half of the app's CallCenter. The
 * browser only ever watches, so everything about sending, arming and the light is left out.
 */
@Injectable({ providedIn: 'root' })
export class CallsService {
  private readonly identity = inject(IdentityService);
  private readonly household = inject(HouseholdService);
  private readonly hub = inject(HubService);
  private readonly alarms = inject(AlarmService);

  private readonly replay = new SignalReplayGuard();
  private readonly audio = new Audio();

  readonly session = signal<CallSession>(IdleSession);
  readonly state = computed(() => this.session().state);
  readonly peer = computed(() => peerOf(this.state()));
  readonly online = signal<ReadonlySet<string>>(new Set());
  readonly availability = signal<Readonly<Record<string, Availability>>>({});
  readonly message = signal<string | null>(null);
  readonly muted = signal(false);
  readonly alarmSettings = signal<NoiseAlarmSettings>(readAlarmSettings());
  readonly path = signal<CallPath>('unknown');
  /** The call is being re-established after dropping out on its own. */
  readonly retrying = signal(false);
  /** The room has stopped saying anything at all. */
  readonly silent = signal(false);
  /** How far the room has got towards an alarm, 0 to 1. */
  readonly arming = signal(0);
  readonly noise = signal(0);
  readonly peerStatus = signal<PhoneStatus | null>(null);
  readonly remoteStream = signal<MediaStream | null>(null);
  readonly offline = signal(!navigator.onLine);
  /** The phone the person asked for, so the watch screen stays up while it calls back. */
  readonly wanted = signal<{ peer: string; video: boolean } | null>(null);
  /** Somebody is looking at the room. */
  readonly watching = signal(false);
  /** Somebody scanned this browser's code and said so. */
  readonly pairings = new Subject<SignalMessage>();

  private pc: RTCPeerConnection | null = null;
  private channel: RTCDataChannel | null = null;
  private ice: IceServers | null = null;
  private noiseWatch: NoiseWatch = newNoiseWatch(this.alarmSettings());
  private attempts = 0;
  private heardAt = 0;
  private effectQueue: Promise<void> = Promise.resolve();
  private signalQueue: Promise<void> = Promise.resolve();
  private watchdog: ReturnType<typeof setInterval> | null = null;
  private pathWatch: ReturnType<typeof setInterval> | null = null;
  private redial: ReturnType<typeof setTimeout> | null = null;
  private dropped: ReturnType<typeof setTimeout> | null = null;
  private answering: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    this.audio.autoplay = true;
    effect(() => this.hub.watch(this.household.phones().map((phone) => phone.id)));
  }

  reachability(deviceId: string): Reachability {
    return reachabilityOf(this.online().has(deviceId), this.availability()[deviceId]);
  }

  async start(): Promise<void> {
    this.hub.signals.subscribe((signal) => {
      // One at a time: checking a signature is asynchronous and would otherwise reorder them.
      this.signalQueue = this.signalQueue.then(() => this.onSignalFromServer(signal));
    });
    this.hub.presence.subscribe(({ deviceId, online }) => {
      const next = new Set(this.online());
      if (online) {
        next.add(deviceId);
      } else {
        next.delete(deviceId);
      }
      this.online.set(next);

      // The phone we are waiting for is back: no reason to sit out the backoff.
      if (online && deviceId === this.wanted()?.peer && this.retrying()) {
        this.attempts = 0;
        this.scheduleRedial(true);
      }
    });
    window.addEventListener('online', () => this.offline.set(false));
    window.addEventListener('offline', () => this.offline.set(true));

    await this.hub.connect();
    void this.renewIce();
    setInterval(() => void this.renewIce(), IceCheckIntervalMillis);
  }

  listenTo(deviceId: string, video: boolean): void {
    this.alarms.prepare();
    this.message.set(null);
    this.silent.set(false);
    this.alarms.silence();

    if (!this.household.keyOf(deviceId)) {
      this.explainUnconfirmed(this.household.nameOf(deviceId));
      return;
    }

    this.wanted.set({ peer: deviceId, video });
    this.attempts = 0;
    this.stopRedialling();
    this.dispatch({ kind: 'listen', peer: deviceId, video });
  }

  /** Stopping on purpose is the one ending that is not news, and the one that stops calling back. */
  hangUp(): void {
    this.wanted.set(null);
    this.stopRedialling();
    this.waitForAnswer(null);
    this.silent.set(false);
    this.alarms.silence();
    this.dispatch({ kind: 'hang-up' });
  }

  /** Silences the sound here only: the room keeps sending, so the meter and alarm keep working. */
  toggleMute(): void {
    this.muted.set(!this.muted());
    this.audio.muted = this.muted();
  }

  askForLight(mode: LightMode, brightness: number, seconds = 0): void {
    this.dispatch({ kind: 'ask-for-light', mode, brightness, seconds });
  }

  showVideo(on: boolean): void {
    this.dispatch({ kind: 'ask-for-video', on });
  }

  chooseQuality(quality: CaptureQuality): void {
    this.dispatch({ kind: 'choose-quality', quality });
  }

  dismissMessage(): void {
    this.message.set(null);
  }

  /** Whether somebody is looking at the room. Looking is as good as pressing "I'm awake". */
  setWatching(watching: boolean): void {
    this.watching.set(watching);
    if (watching) {
      this.alarms.silence();
    }
  }

  setAlarm(settings: NoiseAlarmSettings): void {
    localStorage.setItem(AlarmSettingsKey, JSON.stringify(settings));
    this.alarmSettings.set(settings);
    this.noiseWatch = { ...this.noiseWatch, settings };
    this.sendAlarmThreshold();
  }

  private dispatch(event: CallEvent): void {
    const was = this.session().state;
    const [next, effects] = onCallEvent(this.session(), event);
    this.session.set(next);

    if (next.state.kind === 'live') {
      this.message.set(null);
    }
    for (const callEffect of effects) {
      // One queue for every effect, so a connection is always open before anything is added to it.
      this.effectQueue = this.effectQueue
        .then(() => this.apply(callEffect))
        .catch((error) => console.error(error));
    }

    // A call that was live and failed is the case worth waking for: a flat battery, a killed app.
    if (next.state.kind === 'failed' && was.kind === 'live') {
      this.reportRoomLost(was.peer);
    }

    switch (next.state.kind) {
      case 'idle':
      case 'failed':
        this.waitForAnswer(null);
        if (this.wanted() && was.kind !== 'idle') {
          this.scheduleRedial();
        }
        break;
      case 'asking':
      case 'negotiating':
        this.waitForAnswer('No answer.');
        break;
      case 'live':
        this.waitForAnswer(null);
        this.attempts = 0;
        this.retrying.set(false);
        this.watchThePath();
        break;
    }
  }

  private async apply(callEffect: CallEffect): Promise<void> {
    switch (callEffect.kind) {
      case 'open':
        this.open();
        break;
      case 'accept-offer':
        await this.acceptOffer(callEffect.sdp);
        break;
      case 'add-candidate':
        await this.pc
          ?.addIceCandidate(callEffect.candidate)
          .catch((error) => console.warn('Bad candidate', error));
        break;
      case 'send':
        this.report(callEffect, await this.send(callEffect.to, callEffect.signal, callEffect.body));
        break;
      case 'close':
        this.close();
        break;
    }
  }

  private open(): void {
    const pc = new RTCPeerConnection({
      iceServers: toIceServers(this.ice),
      bundlePolicy: 'max-bundle',
    });
    const stream = new MediaStream();
    this.pc = pc;

    pc.ontrack = (event) => {
      stream.addTrack(event.track);
      this.remoteStream.set(new MediaStream(stream.getTracks()));
      // Once per call: assigning it again for the second track would abort the first play().
      if (this.audio.srcObject !== stream) {
        this.audio.srcObject = stream;
        this.audio.muted = this.muted();
        void this.audio.play().catch((error) => console.warn('Could not play the room', error));
      }
    };
    pc.onicecandidate = (event) => {
      if (this.pc === pc && event.candidate?.candidate) {
        this.dispatch({
          kind: 'local-candidate',
          candidate: {
            sdpMid: event.candidate.sdpMid ?? '',
            sdpMLineIndex: event.candidate.sdpMLineIndex ?? 0,
            candidate: event.candidate.candidate,
          },
        });
      }
    };
    pc.onconnectionstatechange = () => {
      if (this.pc !== pc) {
        return;
      }
      switch (pc.connectionState) {
        case 'connected':
          this.clearDropped();
          this.dispatch({ kind: 'connected' });
          break;
        // Given a while to come back on its own: a phone changing network recovers in seconds.
        case 'disconnected':
          this.dropped ??= setTimeout(() => {
            this.dropped = null;
            this.dispatch({ kind: 'failure', reason: 'The connection dropped.' });
          }, DropGraceMillis);
          break;
        case 'failed':
          this.dispatch({ kind: 'failure', reason: 'The two phones could not reach each other.' });
          break;
      }
    };
    // The phone that films opens the channel; this end is handed it.
    pc.ondatachannel = (event) => {
      const channel = event.channel;
      this.channel = channel;
      channel.onmessage = (message) => this.onRoomMessage(String(message.data));
      channel.onopen = () => this.sendAlarmThreshold();
    };

    this.heardAt = Date.now();
    this.watchdog = setInterval(() => this.checkStillThere(), SilenceCheckMillis);
  }

  private async acceptOffer(sdp: string): Promise<void> {
    const pc = this.pc;
    if (!pc) {
      return;
    }
    try {
      await pc.setRemoteDescription({ type: 'offer', sdp });
      const answer = await pc.createAnswer();
      await pc.setLocalDescription(answer);
      if (this.pc === pc) {
        this.dispatch({ kind: 'local-answer', sdp: answer.sdp ?? '' });
      }
    } catch (error) {
      console.error('Could not answer the offer', error);
      this.dispatch({ kind: 'failure', reason: 'The two phones could not agree on a connection.' });
    }
  }

  private close(): void {
    if (this.pathWatch) {
      clearInterval(this.pathWatch);
      this.pathWatch = null;
    }
    if (this.watchdog) {
      clearInterval(this.watchdog);
      this.watchdog = null;
    }
    this.clearDropped();
    this.path.set('unknown');
    this.muted.set(false);
    this.audio.muted = false;
    this.audio.srcObject = null;
    this.channel = null;
    this.pc?.close();
    this.pc = null;
    this.remoteStream.set(null);
    this.peerStatus.set(null);
    this.noise.set(0);
    this.arming.set(0);
    this.noiseWatch = newNoiseWatch(this.alarmSettings());
  }

  private async send(to: string, kind: string, body: string): Promise<SignalResult> {
    return this.hub.send(await this.identity.sign(to, kind, body));
  }

  /** Only the asking is worth reporting when nobody is there; one lost candidate is not news. */
  private report(callEffect: Extract<CallEffect, { kind: 'send' }>, result: SignalResult): void {
    switch (result.kind) {
      case 'delivered':
        return;
      case 'not-listening':
        if (callEffect.signal === SignalKinds.Start) {
          this.message.set(
            `${this.household.nameOf(callEffect.to)} is not connected. Open the app on it and leave it on.`,
          );
          this.dispatch({ kind: 'stopped', peer: callEffect.to });
        }
        return;
      case 'offline':
        if (callEffect.signal === SignalKinds.Candidate) {
          void this.resendCandidate(callEffect);
          return;
        }
        this.message.set('This computer cannot reach the server.');
        this.dispatch({ kind: 'failure', reason: 'This computer cannot reach the server.' });
        return;
      case 'refused':
        this.message.set(result.reason);
        this.dispatch({ kind: 'failure', reason: result.reason });
        return;
    }
  }

  private async resendCandidate(callEffect: Extract<CallEffect, { kind: 'send' }>): Promise<void> {
    for (let attempt = 0; attempt < CandidateResends; attempt++) {
      await new Promise((resolve) => setTimeout(resolve, CandidateResendDelayMillis));
      if ((await this.send(callEffect.to, callEffect.signal, callEffect.body)).kind !== 'offline') {
        return;
      }
    }
  }

  /** The server says who sent a signal; only the signature proves it. Anything else is dropped. */
  private async onSignalFromServer(signal: SignalMessage): Promise<void> {
    const name = this.household.nameOf(signal.fromDeviceId);
    switch (await trustOf(signal, this.household.keyOf(signal.fromDeviceId))) {
      case 'verified':
        this.onSignal(signal);
        return;
      case 'unconfirmed':
        if (signal.kind === SignalKinds.Pair) {
          this.pairings.next(signal);
        } else {
          console.warn(
            `Dropped a ${signal.kind} from ${signal.fromDeviceId}: its key was never confirmed`,
          );
        }
        return;
      case 'stale':
        this.message.set(
          `${name} and this computer disagree about the time. Check the clock on both.`,
        );
        return;
      case 'wrong-key':
        console.error(
          `Dropped a ${signal.kind} from ${signal.fromDeviceId}: not signed by the confirmed key`,
        );
        this.message.set(`${name} could not prove it is itself. Pair it again before trusting it.`);
        return;
    }
  }

  private onSignal(signal: SignalMessage): void {
    if (!this.replay.accept(signal)) {
      console.warn(`Dropped a ${signal.kind} from ${signal.fromDeviceId}: already acted on`);
      return;
    }

    const from = signal.fromDeviceId;
    switch (signal.kind) {
      // A browser has no foreground service and no lock-screen light, so it is never the monitor.
      case SignalKinds.Start:
        this.message.set(
          `${this.household.nameOf(from)} tried to watch this browser. A browser can only watch.`,
        );
        void this.send(from, SignalKinds.Stop, StopReasons.NotArmed);
        void this.send(
          from,
          SignalKinds.Availability,
          JSON.stringify({ armed: false, busy: this.peer() !== null }),
        );
        return;
      case SignalKinds.Offer:
        this.dispatch({ kind: 'offer-received', peer: from, sdp: signal.body });
        return;
      case SignalKinds.Candidate:
        this.dispatch({
          kind: 'candidate-received',
          peer: from,
          candidate: JSON.parse(signal.body) as IceCandidateDto,
        });
        return;
      case SignalKinds.Light: {
        const reason = (JSON.parse(signal.body) as { reason?: string }).reason;
        if (reason) {
          this.message.set(reason);
        }
        return;
      }
      case SignalKinds.Stop:
        this.onStop(from, signal.body);
        return;
      case SignalKinds.Pair:
        this.pairings.next(signal);
        return;
      case SignalKinds.Availability:
        this.availability.set({ ...this.availability(), [from]: parseAvailability(signal.body) });
        return;
    }
  }

  /**
   * No reason is somebody at that phone pressing Stop, and "not armed" will be said again.
   * Calling back would undo the one and annoy with the other, so this stops wanting the call.
   */
  private onStop(from: string, body: string): void {
    const name = this.household.nameOf(from);
    const reason = body || null;
    if (reason) {
      this.message.set(reason);
    }

    const notArmed = reason === StopReasons.NotArmed;
    if ((reason === null || notArmed) && this.wanted()?.peer === from) {
      this.wanted.set(null);
      this.stopRedialling();
      this.message.set(
        notArmed
          ? `${name} is not set up as a monitor. Turn on “Use this phone as the monitor” on it.`
          : `${name} stopped the call.`,
      );
    }
    if (notArmed) {
      this.availability.set({ ...this.availability(), [from]: { armed: false, busy: false } });
    }

    this.dispatch({ kind: 'stopped', peer: from });
  }

  private onRoomMessage(text: string): void {
    // Anything at all is a sign of life: noise comes five times a second, so silence is real.
    this.heardAt = Date.now();
    if (this.silent()) {
      this.silent.set(false);
      this.alarms.silence();
    }

    let report: RoomReport;
    try {
      report = JSON.parse(text) as RoomReport;
    } catch {
      return;
    }
    if (report.status) {
      this.peerStatus.set(phoneStatusOf(report.status));
    }
    if (report.noise !== undefined) {
      this.noise.set(report.noise);
      this.considerAlarm(report.noise);
    }
  }

  private considerAlarm(level: number): void {
    const [next, fire] = onNoise(this.noiseWatch, level, Date.now());
    this.noiseWatch = next;
    this.arming.set(next.progress);
    if (!fire) {
      return;
    }
    // Already looking at the room with the sound on: the noise is its own notification.
    if (this.watching() && !this.muted()) {
      return;
    }
    const peer = this.peer();
    this.alarms.noise(peer ? this.household.nameOf(peer) : 'The room');
  }

  private sendAlarmThreshold(): void {
    if (this.channel?.readyState === 'open') {
      this.channel.send(JSON.stringify({ alarmThreshold: this.alarmSettings().threshold }));
    }
  }

  /** A phone that is off or out of signal looks exactly like a quiet room, so silence is a failure. */
  private checkStillThere(): void {
    if (!this.peer() || this.silent() || Date.now() - this.heardAt < SilenceLimitMillis) {
      return;
    }
    this.dispatch({ kind: 'failure', reason: 'The room stopped answering.' });
  }

  /** Raised once per disappearance, however many redials fail on the way back. */
  private reportRoomLost(peer: string): void {
    if (this.silent()) {
      return;
    }
    this.silent.set(true);
    if (this.alarmSettings().warnOffline) {
      this.alarms.lost(this.household.nameOf(peer), this.offline());
    }
  }

  /** Asks again, further apart each time: every way a call dies looks the same from here. */
  private scheduleRedial(now = false): void {
    const wanted = this.wanted();
    if (!wanted) {
      return;
    }
    if (this.redial) {
      clearTimeout(this.redial);
    }
    this.retrying.set(true);
    const wait = now ? 0 : BackoffMillis[Math.min(this.attempts, BackoffMillis.length - 1)];
    this.attempts++;
    this.redial = setTimeout(
      () => this.dispatch({ kind: 'listen', peer: wanted.peer, video: wanted.video }),
      wait,
    );
  }

  private stopRedialling(): void {
    if (this.redial) {
      clearTimeout(this.redial);
      this.redial = null;
    }
    this.retrying.set(false);
  }

  private waitForAnswer(reason: string | null): void {
    if (this.answering) {
      clearTimeout(this.answering);
      this.answering = null;
    }
    if (reason) {
      this.answering = setTimeout(
        () => this.dispatch({ kind: 'failure', reason }),
        AnswerLimitMillis,
      );
    }
  }

  private clearDropped(): void {
    if (this.dropped) {
      clearTimeout(this.dropped);
      this.dropped = null;
    }
  }

  /** Asked again now and then: ICE can move a live call to a better pair. */
  private watchThePath(): void {
    if (this.pathWatch) {
      clearInterval(this.pathWatch);
    }
    void this.probePath();
    this.pathWatch = setInterval(() => void this.probePath(), PathIntervalMillis);
  }

  private async probePath(): Promise<void> {
    const pc = this.pc;
    if (!pc) {
      return;
    }
    const stats = await pc.getStats();
    const path = callPathOf(stats);
    if (path && this.pc === pc) {
      this.path.set(path);
    }
  }

  private explainUnconfirmed(name: string): void {
    this.message.set(`${name} has not been paired with this browser. Pair it to listen to it.`);
  }

  private async renewIce(): Promise<void> {
    if (this.ice && !needsIceRenewal(this.ice, Date.now())) {
      return;
    }
    try {
      const response = await fetch('/api/turn/credentials', {
        headers: { Authorization: `Bearer ${await this.identity.authToken()}` },
      });
      if (response.ok) {
        this.ice = (await response.json()) as IceServers;
      }
    } catch (error) {
      // Not fatal: on one WiFi the two ends reach each other without it.
      console.warn('Could not fetch the relay settings', error);
    }
  }
}

/** Two host candidates mean one network; a relay on either side means the server carries it. */
export function callPathOf(stats: RTCStatsReport): CallPath | null {
  const reports = [...stats.values()] as Record<string, unknown>[];
  const selectedId = reports.find((report) => report['type'] === 'transport')?.[
    'selectedCandidatePairId'
  ];
  const pair =
    reports.find((report) => report['id'] === selectedId) ??
    reports.find((report) => report['type'] === 'candidate-pair' && report['selected'] === true) ??
    reports.find(
      (report) =>
        report['type'] === 'candidate-pair' &&
        report['state'] === 'succeeded' &&
        report['nominated'],
    );
  if (!pair) {
    return null;
  }

  const types = [pair['localCandidateId'], pair['remoteCandidateId']].map(
    (id) => reports.find((report) => report['id'] === id)?.['candidateType'],
  );
  if (types.some((type) => type === undefined)) {
    return null;
  }
  if (types.includes('relay')) {
    return 'relayed';
  }
  return types.every((type) => type === 'host') ? 'lan' : 'direct';
}

/** STUN takes no credential; only the relay does, because only it carries traffic. */
function toIceServers(ice: IceServers | null): RTCIceServer[] {
  return (ice?.urls ?? []).map((url) =>
    url.toLowerCase().startsWith('stun:')
      ? { urls: url }
      : { urls: url, username: ice!.username, credential: ice!.credential },
  );
}

function needsIceRenewal(ice: IceServers, now: number): boolean {
  if (!ice.username) {
    return false;
  }
  const expiresAt = Date.parse(ice.expiresAt);
  return Number.isNaN(expiresAt) || expiresAt - now < RenewBeforeExpiryMillis;
}

function readAlarmSettings(): NoiseAlarmSettings {
  try {
    return {
      ...DefaultAlarmSettings,
      ...(JSON.parse(localStorage.getItem(AlarmSettingsKey) ?? '{}') as object),
    };
  } catch {
    return DefaultAlarmSettings;
  }
}
