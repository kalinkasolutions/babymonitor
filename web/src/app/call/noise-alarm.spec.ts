import { describe, expect, it } from 'vitest';
import { DefaultAlarmSettings, newNoiseWatch, onNoise, secondsFor } from './noise-alarm';

function feed(level: number, seconds: number, start = 1_000_000) {
  let watch = newNoiseWatch(DefaultAlarmSettings);
  watch = { ...watch, lastAt: start };
  for (let at = start + 200; at <= start + seconds * 1000; at += 200) {
    const [next, fired] = onNoise(watch, level, at);
    watch = next;
    if (fired) {
      return { fired: true, after: (at - start) / 1000 };
    }
  }
  return { fired: false, after: seconds };
}

describe('noise alarm', () => {
  it('needs the full patience right at the threshold', () => {
    expect(secondsFor(DefaultAlarmSettings, 0.4)).toBeCloseTo(25);
  });

  it('needs only the floor at a shriek', () => {
    expect(secondsFor(DefaultAlarmSettings, 0.65)).toBeCloseTo(2);
  });

  it('never fires below the threshold', () => {
    expect(feed(0.39, 120).fired).toBe(false);
  });

  it('fires on a shriek within a few seconds', () => {
    const result = feed(0.9, 10);
    expect(result.fired).toBe(true);
    expect(result.after).toBeLessThanOrEqual(2.2);
  });

  it('does not fire when switched off', () => {
    let watch = newNoiseWatch({ ...DefaultAlarmSettings, enabled: false });
    for (let at = 200; at < 20_000; at += 200) {
      const [next, fired] = onNoise(watch, 1, at);
      expect(fired).toBe(false);
      watch = next;
    }
  });
});
