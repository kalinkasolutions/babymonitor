import { fromBase64, toBase64, utf8 } from '../crypto/bytes';
import { deviceIdOf } from '../crypto/keys';
import { PairRequest, SignalMessage, trustOf } from './signal';

export const PairingVersion = 2;

/** What goes in the QR code. Short keys, because a smaller code scans more easily. */
export interface PairingPayload {
  v: number;
  d: string;
  k: string;
  m: string;
  n: string;
  s: string;
  /** Only watches: the phones leave it out of the list of phones to watch. */
  w: boolean;
}

export function newPairingSecret(): string {
  return toBase64(crypto.getRandomValues(new Uint8Array(32)));
}

/** HMAC key and message built exactly as the app builds them, the secret's text as the key. */
async function proofKey(secret: string): Promise<CryptoKey> {
  return crypto.subtle.importKey('raw', utf8(secret), { name: 'HMAC', hash: 'SHA-256' }, false, [
    'sign',
    'verify',
  ]);
}

export async function signProof(
  secret: string,
  deviceId: string,
  publicKey: string,
): Promise<string> {
  const mac = await crypto.subtle.sign(
    'HMAC',
    await proofKey(secret),
    utf8(`${deviceId}\n${publicKey}`),
  );
  return toBase64(new Uint8Array(mac));
}

/** WebCrypto's HMAC verify compares in constant time, so the proof cannot be guessed a byte at a time. */
async function proofHolds(secret: string, request: PairRequest): Promise<boolean> {
  try {
    return await crypto.subtle.verify(
      'HMAC',
      await proofKey(secret),
      fromBase64(request.proof),
      utf8(`${request.deviceId}\n${request.publicKey}`),
    );
  } catch {
    return false;
  }
}

/**
 * Whether the phone that sent this really scanned our code: the proof shows it read the secret,
 * the id is its key's, and the signature with that key covers the rest of the message.
 */
export async function isProvenScan(
  secret: string,
  signal: SignalMessage,
  request: PairRequest,
): Promise<boolean> {
  if (!request.publicKey || !(await proofHolds(secret, request))) {
    return false;
  }
  if ((await deviceIdOf(request.publicKey)) !== request.deviceId) {
    return false;
  }
  return (await trustOf(signal, request.publicKey)) === 'verified';
}

export function parsePairRequest(body: string): PairRequest | null {
  try {
    const raw = JSON.parse(body) as Partial<PairRequest>;
    return {
      deviceId: raw.deviceId ?? '',
      name: raw.name ?? '',
      publicKey: raw.publicKey ?? '',
      proof: raw.proof ?? '',
      server: raw.server ?? '',
    };
  } catch {
    return null;
  }
}
