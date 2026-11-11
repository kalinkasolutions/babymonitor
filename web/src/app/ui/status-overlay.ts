import { Component, computed, input } from '@angular/core';
import { PhoneStatus } from '../protocol/signal';
import { CallPath, CallPathLabels } from '../services/calls.service';

/** The route the call took, and the battery and network of the phone in the room. */
@Component({
  selector: 'app-status-overlay',
  styles: `
    :host {
      display: inline-flex;
      align-items: center;
      gap: 8px;
      padding: 4px 8px;
      border-radius: 8px;
      background: var(--scrim);
      color: #fff;
      font-size: 0.8rem;
    }
    .relayed {
      color: #e3c86b;
    }
    svg {
      display: block;
    }
  `,
  template: `
    @if (path() !== 'unknown') {
      <span [class.relayed]="path() === 'relayed'" [title]="pathLabel()">{{ shortPath() }}</span>
    }
    @if (status(); as status) {
      @if (status.network === 'Wifi') {
        <svg width="14" height="12" viewBox="0 0 14 12" role="img" aria-label="WiFi">
          @for (arc of [0, 1, 2]; track arc) {
            <path
              [attr.d]="wifiArc(arc)"
              fill="none"
              stroke-width="1.6"
              stroke-linecap="round"
              [attr.stroke]="lit(status.bars, arc + 2) ? '#fff' : 'rgb(255 255 255 / 33%)'"
            />
          }
          <circle cx="7" cy="10.5" r="1.4" fill="#fff" />
        </svg>
      } @else if (status.network === 'Mobile') {
        <svg width="14" height="12" viewBox="0 0 14 12" role="img" aria-label="Mobile data">
          @for (bar of [0, 1, 2, 3]; track bar) {
            <rect
              [attr.x]="bar * 3.6"
              [attr.y]="12 - (bar + 1) * 3"
              width="2.4"
              [attr.height]="(bar + 1) * 3"
              [attr.fill]="lit(status.bars, bar + 1) ? '#fff' : 'rgb(255 255 255 / 33%)'"
            />
          }
        </svg>
      }
      @if (status.batteryPercent !== null) {
        <span>{{ status.batteryPercent }}%</span>
        <svg
          width="22"
          height="12"
          viewBox="0 0 22 12"
          role="img"
          [attr.aria-label]="status.charging ? 'Charging' : 'Battery'"
        >
          <rect
            x="0.75"
            y="0.75"
            width="18"
            height="10.5"
            rx="2.5"
            fill="none"
            stroke="#fff"
            stroke-width="1.5"
          />
          <rect x="19.5" y="3.5" width="2" height="5" rx="1" fill="#fff" />
          <rect
            x="2.5"
            y="2.5"
            [attr.width]="(14.5 * status.batteryPercent) / 100"
            height="7"
            rx="1"
            [attr.fill]="status.batteryPercent <= 15 && !status.charging ? '#df6b60' : '#fff'"
          />
          @if (status.charging) {
            <path
              d="M11 1.2 7 6.6h3l-1.4 4.2 4.6-5.6h-3z"
              fill="#7bdf8a"
              stroke="#000"
              stroke-width="0.5"
            />
          }
        </svg>
      }
    }
  `,
})
export class StatusOverlay {
  readonly status = input<PhoneStatus | null>(null);
  readonly path = input<CallPath>('unknown');

  protected readonly pathLabel = computed(() => CallPathLabels[this.path()]);
  protected readonly shortPath = computed(
    () => ({ unknown: '', lan: 'WiFi', direct: 'Direct', relayed: 'Relay' })[this.path()],
  );

  /** Unknown strength shows everything lit, as the app does. */
  protected lit(bars: number | null, needed: number): boolean {
    return bars === null || bars >= needed;
  }

  protected wifiArc(index: number): string {
    const radius = 3.5 + index * 3.2;
    const angle = (20 * Math.PI) / 180;
    const startX = 7 - radius * Math.cos(angle);
    const endX = 7 + radius * Math.cos(angle);
    const y = 11 - radius * Math.sin(angle) - radius * 0.35;
    return `M${startX.toFixed(2)} ${y.toFixed(2)} Q7 ${(11 - radius * 1.35).toFixed(2)} ${endX.toFixed(2)} ${y.toFixed(2)}`;
  }
}
