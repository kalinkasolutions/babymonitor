import { fromBase64, sha256, toBase64, toHex, utf8 } from './bytes';
import { derToRaw, rawToDer } from './der';

const Ecdsa = { name: 'ECDSA', namedCurve: 'P-256' } as const;
const EcdsaSha256 = { name: 'ECDSA', hash: 'SHA-256' } as const;

/** The private half is made non-extractable, so not even this page can read it back out. */
export function generateIdentityKey(): Promise<CryptoKeyPair> {
  return crypto.subtle.generateKey(Ecdsa, false, ['sign', 'verify']);
}

/** Base64 X.509 SubjectPublicKeyInfo, the form the phones exchange. */
export async function exportPublicKey(key: CryptoKey): Promise<string> {
  return toBase64(new Uint8Array(await crypto.subtle.exportKey('spki', key)));
}

/** Base64 DER, the signature format the phones and the server check. */
export async function signWith(
  privateKey: CryptoKey,
  data: Uint8Array<ArrayBuffer>,
): Promise<string> {
  const raw = new Uint8Array(await crypto.subtle.sign(EcdsaSha256, privateKey, data));
  return toBase64(rawToDer(raw));
}

export async function verifySignature(
  publicKeyBase64: string,
  data: Uint8Array<ArrayBuffer>,
  signatureBase64: string,
): Promise<boolean> {
  try {
    const key = await crypto.subtle.importKey('spki', fromBase64(publicKeyBase64), Ecdsa, false, [
      'verify',
    ]);
    return await crypto.subtle.verify(
      EcdsaSha256,
      key,
      derToRaw(fromBase64(signatureBase64)),
      data,
    );
  } catch {
    return false;
  }
}

/** First 16 bytes of SHA-256 over the DER key, as the phones and the server derive it. */
export async function deviceIdOf(publicKeyBase64: string): Promise<string> {
  return toHex((await sha256(fromBase64(publicKeyBase64))).slice(0, 16));
}

/** 96 bits of the key in groups of four, the same digest the phones show. */
export async function fingerprintOf(publicKeyBase64: string): Promise<string> {
  const hex = toHex((await sha256(utf8(publicKeyBase64))).slice(0, 12));
  return hex.match(/.{4}/g)!.join(' ');
}
