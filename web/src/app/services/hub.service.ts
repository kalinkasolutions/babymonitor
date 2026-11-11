import { Injectable, inject, signal } from '@angular/core';
import {
  HubConnection,
  HubConnectionBuilder,
  HubConnectionState,
  LogLevel,
} from '@microsoft/signalr';
import { Subject } from 'rxjs';
import { SignalMessage, parseSignal } from '../protocol/signal';
import { IdentityService } from './identity.service';

export type SignalResult =
  | { kind: 'delivered' }
  /** The hub took it and nobody was there: the app on that phone is closed or asleep. */
  | { kind: 'not-listening' }
  /** This browser has no connection to the server, so nothing was sent. */
  | { kind: 'offline' }
  | { kind: 'refused'; reason: string };

export interface Presence {
  deviceId: string;
  online: boolean;
}

const FirstRetryMillis = 1_000;
const LongestRetryMillis = 30_000;
const HubExceptionMarker = 'HubException:';

/** The connection to the server's hub. Signals and presence arrive here as they happen. */
@Injectable({ providedIn: 'root' })
export class HubService {
  private readonly identity = inject(IdentityService);
  private connection: HubConnection | null = null;
  private watched: string[] = [];
  private starting = false;

  readonly connected = signal(false);
  readonly signals = new Subject<SignalMessage>();
  readonly presence = new Subject<Presence>();

  async connect(): Promise<void> {
    const connection = new HubConnectionBuilder()
      .withUrl('/hubs/devices', { accessTokenFactory: () => this.identity.authToken() })
      // Never gives up: a monitor left open overnight loses this socket often.
      .withAutomaticReconnect({
        nextRetryDelayInMilliseconds: (context) =>
          Math.min(FirstRetryMillis * 2 ** context.previousRetryCount, LongestRetryMillis),
      })
      .configureLogging(LogLevel.Warning)
      .build();

    connection.on('signal', (payload: string) => this.signals.next(parseSignal(payload)));
    connection.on('devicePresence', (payload: string) => {
      const presence = JSON.parse(payload) as { deviceId: string; isOnline: boolean };
      this.presence.next({ deviceId: presence.deviceId, online: presence.isOnline });
    });
    connection.onreconnecting(() => this.connected.set(false));
    connection.onreconnected(() => {
      this.connected.set(true);
      void this.sendWatch();
    });
    // Automatic reconnect stops after the first failed start and when it runs out, so start over.
    connection.onclose(() => {
      this.connected.set(false);
      void this.startWithRetry(connection);
    });

    this.connection = connection;
    await this.startWithRetry(connection);
  }

  /** The phones whose presence to hear about. Sent again on every reconnect. */
  watch(deviceIds: string[]): void {
    this.watched = deviceIds;
    void this.sendWatch();
  }

  async send(message: SignalMessage): Promise<SignalResult> {
    const connection = this.connection;
    if (connection?.state !== HubConnectionState.Connected) {
      return { kind: 'offline' };
    }
    try {
      const delivered = await connection.invoke<boolean>('Signal', JSON.stringify(message));
      return delivered ? { kind: 'delivered' } : { kind: 'not-listening' };
    } catch (error) {
      const text = error instanceof Error ? error.message : String(error);
      if (text.includes(HubExceptionMarker)) {
        return {
          kind: 'refused',
          reason: text
            .substring(text.indexOf(HubExceptionMarker) + HubExceptionMarker.length)
            .trim(),
        };
      }
      if (text.toLowerCase().includes('does not exist')) {
        return {
          kind: 'refused',
          reason: 'This server cannot connect calls yet. It is older than this app.',
        };
      }
      return { kind: 'offline' };
    }
  }

  private async startWithRetry(connection: HubConnection): Promise<void> {
    if (this.starting) {
      return;
    }
    this.starting = true;
    let wait = FirstRetryMillis;
    try {
      while (connection.state === HubConnectionState.Disconnected) {
        try {
          await connection.start();
          this.connected.set(true);
          await this.sendWatch();
          return;
        } catch {
          await new Promise((resolve) => setTimeout(resolve, wait));
          wait = Math.min(wait * 2, LongestRetryMillis);
        }
      }
    } finally {
      this.starting = false;
    }
  }

  private async sendWatch(): Promise<void> {
    const connection = this.connection;
    if (connection?.state !== HubConnectionState.Connected) {
      return;
    }
    const ids = this.watched;
    try {
      const online = new Set(await connection.invoke<string[]>('Watch', ids));
      for (const deviceId of ids) {
        this.presence.next({ deviceId, online: online.has(deviceId) });
      }
    } catch (error) {
      console.warn('Could not ask the server who is online', error);
    }
  }
}
