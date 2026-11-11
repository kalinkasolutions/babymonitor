import { Injectable } from '@angular/core';
import { utf8 } from '../crypto/bytes';
import { deviceIdOf, exportPublicKey, generateIdentityKey, signWith } from '../crypto/keys';
import { SignalMessage, signedBytes } from '../protocol/signal';

const DatabaseName = 'babymonitor';
const StoreName = 'identity';
const KeyName = 'key';

/**
 * This browser's identity key. Kept in IndexedDB as a CryptoKey, which stores the private half
 * without ever exposing its bytes. Clearing the site's data loses it, and the phones then see a
 * stranger: the browser has to be paired again.
 */
@Injectable({ providedIn: 'root' })
export class IdentityService {
  private keys!: CryptoKeyPair;
  deviceId = '';
  publicKey = '';

  async load(): Promise<void> {
    const database = await openDatabase();
    this.keys =
      (await read<CryptoKeyPair>(database, KeyName)) ?? (await this.createAndStore(database));
    this.publicKey = await exportPublicKey(this.keys.publicKey);
    this.deviceId = await deviceIdOf(this.publicKey);
  }

  /** Time, signature and key in one token, because a browser cannot put headers on a WebSocket. */
  async authToken(): Promise<string> {
    const time = String(Date.now());
    const signature = await signWith(this.keys.privateKey, utf8(`babymonitor-hub\n${time}`));
    return `${time}.${signature}.${this.publicKey}`;
  }

  async sign(toDeviceId: string, kind: string, body: string): Promise<SignalMessage> {
    const message: SignalMessage = {
      toDeviceId,
      fromDeviceId: this.deviceId,
      kind,
      body,
      sentAt: Date.now(),
      signature: '',
    };
    return { ...message, signature: await signWith(this.keys.privateKey, signedBytes(message)) };
  }

  private async createAndStore(database: IDBDatabase): Promise<CryptoKeyPair> {
    const keys = await generateIdentityKey();
    await write(database, KeyName, keys);
    return keys;
  }
}

function openDatabase(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DatabaseName, 1);
    request.onupgradeneeded = () => request.result.createObjectStore(StoreName);
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

function read<T>(database: IDBDatabase, key: string): Promise<T | undefined> {
  return new Promise((resolve, reject) => {
    const request = database.transaction(StoreName).objectStore(StoreName).get(key);
    request.onsuccess = () => resolve(request.result as T | undefined);
    request.onerror = () => reject(request.error);
  });
}

function write(database: IDBDatabase, key: string, value: unknown): Promise<void> {
  return new Promise((resolve, reject) => {
    const transaction = database.transaction(StoreName, 'readwrite');
    transaction.objectStore(StoreName).put(value, key);
    transaction.oncomplete = () => resolve();
    transaction.onerror = () => reject(transaction.error);
  });
}
