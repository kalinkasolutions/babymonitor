import { Component, DestroyRef, inject, signal } from '@angular/core';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { Router, RouterLink } from '@angular/router';
import QRCode from 'qrcode';
import {
  PairingPayload,
  PairingVersion,
  isProvenScan,
  newPairingSecret,
  parsePairRequest,
} from '../protocol/pairing';
import { SignalMessage } from '../protocol/signal';
import { CallsService } from '../services/calls.service';
import { HouseholdService } from '../services/household.service';
import { IdentityService } from '../services/identity.service';

/**
 * The browser has no camera to scan with, so it is always the one shown: the phone scans this
 * code, then proves over the server that it did, which confirms both sides in one scan.
 */
@Component({
  selector: 'app-pair',
  imports: [RouterLink],
  styleUrl: './pair.page.css',
  template: `
    <a routerLink="/" class="back">← Back</a>
    <h1>Pair a phone</h1>

    <div class="layout">
      <div class="qr" [innerHTML]="qr()" role="img" aria-label="Pairing code"></div>
      <div>
        <ol>
          <li>Open Babymonitor on the phone.</li>
          <li>Go to <strong>Devices</strong> and tap <strong>Connect another phone</strong>.</li>
          <li>Choose <strong>Scan</strong> and point the phone at this code.</li>
        </ol>
        <p class="muted">
          The code also carries this server's address ({{ origin }}), so the phone picks it up if it
          has none.
        </p>
        <p class="muted">This page closes by itself once the phone has paired.</p>
      </div>
    </div>
  `,
})
export class PairPage {
  private readonly identity = inject(IdentityService);
  private readonly household = inject(HouseholdService);
  private readonly calls = inject(CallsService);
  private readonly router = inject(Router);
  private readonly sanitizer = inject(DomSanitizer);

  // In memory only, and new for every visit: a proof made with it shows the phone read this screen.
  private readonly secret = newPairingSecret();
  protected readonly origin = location.origin;
  protected readonly qr = signal<SafeHtml>('');

  constructor() {
    const payload: PairingPayload = {
      v: PairingVersion,
      d: this.identity.deviceId,
      k: this.identity.publicKey,
      m: this.household.name(),
      n: this.secret,
      s: this.origin,
      w: true,
    };
    // Dark on white whatever the theme, because that is what phone cameras read best.
    void QRCode.toString(JSON.stringify(payload), {
      type: 'svg',
      errorCorrectionLevel: 'M',
      margin: 2,
      color: { dark: '#000000', light: '#ffffff' },
    }).then((svg) => this.qr.set(this.sanitizer.bypassSecurityTrustHtml(svg)));

    const subscription = this.calls.pairings.subscribe((signal) => void this.onScanned(signal));
    inject(DestroyRef).onDestroy(() => subscription.unsubscribe());
  }

  private async onScanned(signal: SignalMessage): Promise<void> {
    const request = parsePairRequest(signal.body);
    if (!request) {
      return;
    }
    const name = request.name || 'The other phone';

    if (!(await isProvenScan(this.secret, signal, request))) {
      console.warn(
        `${request.deviceId} claimed to have scanned this browser but could not prove it`,
      );
      this.calls.message.set(
        `${name} could not show it had read the code off this screen. That happens when it scanned an ` +
          'older code. It is also what a stranger faking a pairing would look like. Nothing was paired: scan again.',
      );
      return;
    }

    this.household.remember({ id: request.deviceId, name, publicKey: request.publicKey });
    this.calls.message.set(`Paired with ${name}.`);
    void this.router.navigate(['/']);
  }
}
