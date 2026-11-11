import { Injectable, signal } from '@angular/core';

/** A phone this browser paired with. Its key was read off this screen by that phone's camera. */
export interface Phone {
  id: string;
  name: string;
  publicKey: string;
}

const PhonesKey = 'babymonitor.phones';
const NameKey = 'babymonitor.name';

/**
 * The paired phones and this browser's own name, kept in this browser only: the list decides
 * whose signals are believed, so nothing outside gets to edit it.
 */
@Injectable({ providedIn: 'root' })
export class HouseholdService {
  readonly phones = signal<Phone[]>(readPhones());
  readonly name = signal<string>(localStorage.getItem(NameKey) || defaultName());

  keyOf(deviceId: string): string | undefined {
    return this.phones().find((phone) => phone.id === deviceId)?.publicKey;
  }

  nameOf(deviceId: string): string {
    return this.phones().find((phone) => phone.id === deviceId)?.name ?? 'That phone';
  }

  remember(phone: Phone): void {
    this.save([...this.phones().filter((kept) => kept.id !== phone.id), phone]);
  }

  forget(deviceId: string): void {
    this.save(this.phones().filter((phone) => phone.id !== deviceId));
  }

  rename(deviceId: string, name: string): void {
    this.save(this.phones().map((phone) => (phone.id === deviceId ? { ...phone, name } : phone)));
  }

  /** Reaches the phones only through the next pairing code; the ones already paired keep the old name. */
  renameThisBrowser(name: string): void {
    localStorage.setItem(NameKey, name);
    this.name.set(name);
  }

  private save(phones: Phone[]): void {
    localStorage.setItem(PhonesKey, JSON.stringify(phones));
    this.phones.set(phones);
  }
}

function readPhones(): Phone[] {
  try {
    return JSON.parse(localStorage.getItem(PhonesKey) ?? '[]') as Phone[];
  } catch {
    return [];
  }
}

/** "Firefox on Linux", the way the app names a phone after its maker and model. */
export function defaultName(userAgent = navigator.userAgent): string {
  const browser =
    [
      ['Edg/', 'Edge'],
      ['OPR/', 'Opera'],
      ['Firefox/', 'Firefox'],
      ['Chrome/', 'Chrome'],
      ['Safari/', 'Safari'],
    ].find(([marker]) => userAgent.includes(marker))?.[1] ?? 'Browser';
  const system = [
    ['Windows', 'Windows'],
    ['Mac OS X', 'macOS'],
    ['Android', 'Android'],
    ['CrOS', 'ChromeOS'],
    ['Linux', 'Linux'],
  ].find(([marker]) => userAgent.includes(marker))?.[1];
  return system ? `${browser} on ${system}` : browser;
}
