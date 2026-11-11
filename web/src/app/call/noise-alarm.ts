/** Ported from the app's NoiseAlarm.kt, so a browser and a phone alarm on the same room alike. */
export interface NoiseAlarmSettings {
  enabled: boolean;
  /** The quietest sound that ever counts, on the same 0–1 scale the meter draws. */
  threshold: number;
  /** How long a sound exactly at the threshold has to last. Anything louder is quicker. */
  seconds: number;
  /** Also alarm when the room stops answering, which looks just like a quiet room. */
  warnOffline: boolean;
}

export const DefaultAlarmSettings: NoiseAlarmSettings = {
  enabled: true,
  threshold: 0.4,
  seconds: 25,
  warnOffline: true,
};

/** However loud it gets, something has to last this long to be more than a door closing. */
const FloorSeconds = 2;
/** Screaming close to the phone; full scale is clipping, which a phone hardly ever reaches. */
const ShriekLevel = 0.65;
/** Quiet drains a full bucket this many times slower than it filled, because crying pauses. */
const DrainPatience = 2;
const CooldownMillis = 60_000;
const MaxStepMillis = 2_000;

/** From the full patience at the threshold down to the floor at a shriek, geometrically. */
export function secondsFor(settings: NoiseAlarmSettings, level: number): number {
  if (level < settings.threshold) {
    return Infinity;
  }
  const shriek = Math.max(ShriekLevel, settings.threshold + 0.1);
  const over = Math.min(
    1,
    Math.max(0, (level - settings.threshold) / (shriek - settings.threshold)),
  );
  return settings.seconds * Math.pow(FloorSeconds / settings.seconds, over);
}

export interface NoiseWatch {
  settings: NoiseAlarmSettings;
  /** 0 to 1: how far the room has got towards an alarm. */
  progress: number;
  lastAt: number;
  firedAt: number;
}

export function newNoiseWatch(settings: NoiseAlarmSettings): NoiseWatch {
  return { settings, progress: 0, lastAt: 0, firedAt: 0 };
}

/** The next state, and whether this reading is the moment to raise the alarm. */
export function onNoise(watch: NoiseWatch, level: number, now: number): [NoiseWatch, boolean] {
  if (!watch.settings.enabled) {
    return [{ ...watch, progress: 0, lastAt: now }, false];
  }

  // A gap means the call was interrupted, not that the room was quiet for an hour.
  const elapsed = Math.min(MaxStepMillis, Math.max(0, now - watch.lastAt)) / 1000;
  const required = secondsFor(watch.settings, level);
  const raw =
    required === Infinity
      ? watch.progress - elapsed / (watch.settings.seconds * DrainPatience)
      : watch.progress + elapsed / required;
  const next = Math.min(1, Math.max(0, raw));

  const rested = watch.firedAt === 0 || now - watch.firedAt >= CooldownMillis;
  if (next >= 1 && rested) {
    return [{ ...watch, progress: 0, lastAt: now, firedAt: now }, true];
  }
  return [{ ...watch, progress: next, lastAt: now }, false];
}
