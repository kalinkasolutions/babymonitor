import { utf8 } from '../crypto/bytes';
import { verifySignature } from '../crypto/keys';

/** One signalling message, as the phones and the hub carry it. */
export interface SignalMessage {
  toDeviceId: string;
  fromDeviceId: string;
  kind: string;
  body: string;
  sentAt: number;
  signature: string;
}

/** The same closed set the hub accepts. */
export const SignalKinds = {
  Start: 'start',
  Offer: 'offer',
  Answer: 'answer',
  Candidate: 'candidate',
  Stop: 'stop',
  Light: 'light',
  Quality: 'quality',
  Video: 'video',
  Pair: 'pair',
  Availability: 'availability',
} as const;

/** The reasons a phone puts in a stop, word for word, because the other end compares them. */
export const StopReasons = {
  Busy: 'That phone is already in a call.',
  Refused: "That phone has not confirmed this one's key.",
  NotArmed: 'That phone is not set up as a monitor.',
} as const;

/** fromDeviceId is left out on purpose: the hub overwrites it with the sender it authenticated. */
export function signedBytes(message: SignalMessage): Uint8Array<ArrayBuffer> {
  return utf8([String(message.sentAt), message.toDeviceId, message.kind, message.body].join('\n'));
}

/** Fills in what the sender left out: the phones do not write fields that hold their default. */
export function parseSignal(json: string): SignalMessage {
  const raw = JSON.parse(json) as Partial<SignalMessage>;
  return {
    toDeviceId: raw.toDeviceId ?? '',
    fromDeviceId: raw.fromDeviceId ?? '',
    kind: raw.kind ?? '',
    body: raw.body ?? '',
    sentAt: raw.sentAt ?? 0,
    signature: raw.signature ?? '',
  };
}

/** How far apart two clocks may be, the same window the phones allow. */
export const SignalFreshnessWindowMillis = 60_000;

export type SignalTrust = 'verified' | 'unconfirmed' | 'wrong-key' | 'stale';

export async function trustOf(
  message: SignalMessage,
  confirmedKey: string | undefined,
  now = Date.now(),
): Promise<SignalTrust> {
  if (!confirmedKey) {
    return 'unconfirmed';
  }
  if (!message.signature) {
    return 'wrong-key';
  }
  if (Math.abs(now - message.sentAt) > SignalFreshnessWindowMillis) {
    return 'stale';
  }
  return (await verifySignature(confirmedKey, signedBytes(message), message.signature))
    ? 'verified'
    : 'wrong-key';
}

/** Lets each signed signal through once, so one captured in flight cannot be acted on twice. */
export class SignalReplayGuard {
  private readonly seen = new Map<string, number>();

  constructor(private readonly windowMillis = SignalFreshnessWindowMillis) {}

  accept(signal: SignalMessage, now = Date.now()): boolean {
    for (const [key, sentAt] of this.seen) {
      if (now - sentAt > this.windowMillis) {
        this.seen.delete(key);
      }
    }

    const key = `${signal.fromDeviceId}\n${signal.signature}`;
    if (this.seen.has(key)) {
      return false;
    }
    this.seen.set(key, signal.sentAt);
    return true;
  }
}

export type LightMode = 'Off' | 'White' | 'Red';

export interface LightRequest {
  mode: LightMode;
  brightness: number;
  seconds: number;
  reason: string;
}

export interface Availability {
  armed: boolean;
  busy: boolean;
}

/** A phone too old to say anything is taken as available, as the app does. */
export function parseAvailability(body: string): Availability {
  const raw = JSON.parse(body) as Partial<Availability>;
  return { armed: raw.armed ?? true, busy: raw.busy ?? false };
}

export interface PairRequest {
  deviceId: string;
  name: string;
  publicKey: string;
  proof: string;
  server: string;
}

export interface IceCandidateDto {
  sdpMid: string;
  sdpMLineIndex: number;
  candidate: string;
}

export type Network = 'None' | 'Wifi' | 'Mobile';

export interface PhoneStatus {
  batteryPercent: number | null;
  charging: boolean;
  network: Network;
  bars: number | null;
}

/** What the phone in the room sends on the data channel; either half may be missing. */
export interface RoomReport {
  status?: Partial<PhoneStatus>;
  noise?: number;
  alarmThreshold?: number;
}

export function phoneStatusOf(raw: Partial<PhoneStatus>): PhoneStatus {
  return {
    batteryPercent: raw.batteryPercent ?? null,
    charging: raw.charging ?? false,
    network: raw.network ?? 'None',
    bars: raw.bars ?? null,
  };
}
