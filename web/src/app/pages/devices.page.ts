import { Component, computed, effect, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { reachabilityLine } from '../call/reachability';
import { fingerprintOf } from '../crypto/keys';
import { CallsService } from '../services/calls.service';
import { HouseholdService, Phone } from '../services/household.service';
import { HubService } from '../services/hub.service';

/** This browser, the phones paired with it, and the way to watch one. */
@Component({
  selector: 'app-devices',
  imports: [RouterLink],
  styleUrl: './devices.page.css',
  template: `
    <section class="card this">
      @if (editing() === 'self') {
        <form class="rename" (submit)="saveOwnName($event, ownName.value)">
          <input
            #ownName
            type="text"
            [value]="household.name()"
            aria-label="Name of this browser"
          />
          <button class="primary" type="submit">Save</button>
          <button class="text" type="button" (click)="editing.set(null)">Cancel</button>
        </form>
        <p class="muted small">Phones paired from now on see this name.</p>
      } @else {
        <div class="row">
          <div class="grow">
            <h2>{{ household.name() }} <span class="muted">(this browser)</span></h2>
            <p class="muted">
              <span class="dot" [class.on]="hub.connected()"></span>
              {{
                hub.connected()
                  ? 'Connected to the server. Watches only.'
                  : 'Connecting to the server…'
              }}
            </p>
          </div>
          <button class="text" (click)="editing.set('self')">Rename</button>
        </div>
      }
    </section>

    <div class="heading">
      <h1>Watch a phone</h1>
      <a routerLink="/pair"><button class="primary">Pair a phone</button></a>
    </div>

    @if (household.phones().length === 0) {
      <section class="card empty">
        <h2>No phone yet</h2>
        <p class="muted">
          Pair the phone that stays in the room with the baby. It scans a code shown here.
        </p>
      </section>
    }

    @for (phone of household.phones(); track phone.id) {
      <section class="card">
        @if (editing() === phone.id) {
          <form class="rename" (submit)="saveName($event, phone, phoneName.value)">
            <input #phoneName type="text" [value]="phone.name" aria-label="Name of the phone" />
            <button class="primary" type="submit">Save</button>
            <button class="text" type="button" (click)="editing.set(null)">Cancel</button>
          </form>
        } @else if (removing() === phone.id) {
          <p>
            <strong>Remove {{ phone.name }}?</strong>
            This browser stops trusting it, so it can no longer watch it. To use it again, pair it
            again.
          </p>
          <div class="actions">
            <button class="danger" (click)="remove(phone)">Remove</button>
            <button class="text" (click)="removing.set(null)">Cancel</button>
          </div>
        } @else {
          <div class="row">
            <div class="grow">
              <h2>{{ phone.name }}</h2>
              <p class="muted">
                <span class="dot" [class.on]="calls.reachability(phone.id) === 'ready'"></span>
                {{ line(phone) }}
              </p>
              <p class="muted mono">key {{ fingerprints()[phone.id] }}</p>
            </div>
            <div class="actions">
              <button class="text" [disabled]="!canWatch(phone)" (click)="watch(phone, true)">
                Watch
              </button>
              <button class="primary" [disabled]="!canWatch(phone)" (click)="watch(phone, false)">
                Listen
              </button>
            </div>
          </div>
          <div class="manage">
            <button class="text" (click)="editing.set(phone.id)">Rename</button>
            <button class="danger" (click)="removing.set(phone.id)">Remove</button>
          </div>
        }
      </section>
    }
  `,
})
export class DevicesPage {
  protected readonly household = inject(HouseholdService);
  protected readonly calls = inject(CallsService);
  protected readonly hub = inject(HubService);
  private readonly router = inject(Router);

  protected readonly editing = signal<string | null>(null);
  protected readonly removing = signal<string | null>(null);
  protected readonly fingerprints = signal<Record<string, string>>({});
  private readonly busy = computed(
    () => this.calls.peer() !== null || this.calls.wanted() !== null,
  );

  constructor() {
    effect(() => {
      const phones = this.household.phones();
      void Promise.all(
        phones.map(async (phone) => [phone.id, await fingerprintOf(phone.publicKey)] as const),
      ).then((entries) => this.fingerprints.set(Object.fromEntries(entries)));
    });
  }

  protected line(phone: Phone): string {
    return reachabilityLine(this.calls.reachability(phone.id), phone.name);
  }

  protected canWatch(phone: Phone): boolean {
    return this.calls.reachability(phone.id) === 'ready' && !this.busy();
  }

  protected watch(phone: Phone, video: boolean): void {
    this.calls.listenTo(phone.id, video);
    void this.router.navigate(['/watch']);
  }

  protected saveName(event: Event, phone: Phone, name: string): void {
    event.preventDefault();
    if (name.trim()) {
      this.household.rename(phone.id, name.trim());
    }
    this.editing.set(null);
  }

  protected saveOwnName(event: Event, name: string): void {
    event.preventDefault();
    if (name.trim()) {
      this.household.renameThisBrowser(name.trim());
    }
    this.editing.set(null);
  }

  protected remove(phone: Phone): void {
    this.household.forget(phone.id);
    this.removing.set(null);
  }
}
