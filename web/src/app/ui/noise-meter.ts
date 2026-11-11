import { Component, computed, input } from '@angular/core';

/**
 * How loud the room is, filling from the bottom, beside how close that has brought it to an
 * alarm. With the sound off this is the only thing saying whether anything is happening.
 */
@Component({
  selector: 'app-noise-meter',
  styles: `
    :host {
      display: block;
      padding: 4px;
      border-radius: 6px;
      background: var(--scrim);
    }
    svg {
      display: block;
    }
    rect {
      transition:
        y 0.2s ease-out,
        height 0.2s ease-out;
    }
  `,
  template: `
    <svg width="16" height="96" viewBox="0 0 16 96" role="img" [attr.aria-label]="label()">
      <rect x="0" y="0" width="9" height="96" rx="4.5" fill="rgb(255 255 255 / 20%)" />
      <rect
        x="0"
        [attr.y]="96 - levelHeight()"
        width="9"
        [attr.height]="levelHeight()"
        rx="4.5"
        [attr.fill]="colour()"
      />
      @if (threshold() > 0.01 && threshold() < 0.99) {
        <rect
          x="0"
          [attr.y]="96 - threshold() * 96 - 1"
          width="9"
          height="2"
          fill="rgb(255 255 255 / 80%)"
        />
      }
      <rect x="12" y="0" width="4" height="96" rx="2" fill="rgb(255 255 255 / 20%)" />
      <rect
        x="12"
        [attr.y]="96 - armingHeight()"
        width="4"
        [attr.height]="armingHeight()"
        rx="2"
        fill="#d2a8e4"
      />
    </svg>
  `,
})
export class NoiseMeter {
  readonly level = input(0);
  readonly threshold = input(0.4);
  readonly arming = input(0);

  protected readonly levelHeight = computed(() => clamp(this.level()) * 96);
  protected readonly armingHeight = computed(() => clamp(this.arming()) * 96);
  protected readonly colour = computed(() =>
    this.level() > 0.75 ? '#df6b60' : this.level() > 0.45 ? '#e3c86b' : '#7bdf8a',
  );
  protected readonly label = computed(() => `Noise ${Math.round(clamp(this.level()) * 100)}%`);
}

function clamp(value: number): number {
  return Math.min(1, Math.max(0, value));
}
