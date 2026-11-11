import { Component, inject, signal } from '@angular/core';
import { RouterLink, RouterOutlet } from '@angular/router';
import { AlarmService } from './services/alarm.service';
import { CallsService } from './services/calls.service';

const TabLock = 'babymonitor-connection';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink],
  template: `
    <header class="bar">
      <a routerLink="/" class="brand">
        <img src="favicon.svg" alt="" width="24" height="24" />
        Babymonitor
      </a>
    </header>

    @if (alarms.ringing(); as alarm) {
      <section class="alarm" role="alert">
        <div>
          <strong>{{ alarm.title }}</strong>
          <span>{{ alarm.text }}</span>
        </div>
        <button class="primary" (click)="alarms.silence()">I'm awake</button>
      </section>
    }

    @if (calls.message(); as message) {
      <section class="message" role="status">
        <span>{{ message }}</span>
        <button class="text" (click)="calls.dismissMessage()">OK</button>
      </section>
    }

    <main>
      @if (otherTab()) {
        <section class="card empty">
          <h2>Babymonitor is open in another tab</h2>
          <p class="muted">
            Only one tab can be connected at a time. Close the other one, and this one takes over.
          </p>
        </section>
      } @else {
        <router-outlet />
      }
    </main>
  `,
})
export class App {
  protected readonly calls = inject(CallsService);
  protected readonly alarms = inject(AlarmService);
  protected readonly otherTab = signal(false);

  constructor() {
    void this.takeTheConnection();
  }

  /**
   * Two tabs would sign in as the same device and push each other off the server, so one tab
   * holds a lock for as long as it is open and any other waits for it.
   */
  private async takeTheConnection(): Promise<void> {
    if (!navigator.locks) {
      await this.calls.start();
      return;
    }
    const taken = await navigator.locks.request(
      TabLock,
      { ifAvailable: true },
      (lock) => lock && this.holdForever(),
    );
    if (taken === null) {
      this.otherTab.set(true);
      void navigator.locks.request(TabLock, () => {
        this.otherTab.set(false);
        return this.holdForever();
      });
    }
  }

  private holdForever(): Promise<never> {
    void this.calls.start();
    return new Promise<never>(() => undefined);
  }
}
