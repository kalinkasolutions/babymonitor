import { describe, expect, it } from 'vitest';
import { exportPublicKey, generateIdentityKey, signWith } from '../crypto/keys';
import { isProvenScan, newPairingSecret, signProof } from './pairing';
import { SignalMessage, SignalReplayGuard, signedBytes, trustOf } from './signal';
import { deviceIdOf } from '../crypto/keys';

async function signedMessage(
  key: CryptoKeyPair,
  fields: Partial<SignalMessage> = {},
): Promise<SignalMessage> {
  const message: SignalMessage = {
    toDeviceId: 'a'.repeat(32),
    fromDeviceId: 'b'.repeat(32),
    kind: 'start',
    body: '{}',
    sentAt: Date.now(),
    signature: '',
    ...fields,
  };
  return { ...message, signature: await signWith(key.privateKey, signedBytes(message)) };
}

describe('signal trust', () => {
  it('believes a fresh message signed by the confirmed key', async () => {
    const key = await generateIdentityKey();
    const message = await signedMessage(key);

    expect(await trustOf(message, await exportPublicKey(key.publicKey))).toBe('verified');
  });

  it('does not believe a message from a phone nobody confirmed', async () => {
    const key = await generateIdentityKey();

    expect(await trustOf(await signedMessage(key), undefined)).toBe('unconfirmed');
  });

  it('refuses a message signed by another key', async () => {
    const key = await generateIdentityKey();
    const other = await generateIdentityKey();

    expect(await trustOf(await signedMessage(key), await exportPublicKey(other.publicKey))).toBe(
      'wrong-key',
    );
  });

  it('refuses a message whose body was changed after signing', async () => {
    const key = await generateIdentityKey();
    const message = { ...(await signedMessage(key)), body: '{"video":true}' };

    expect(await trustOf(message, await exportPublicKey(key.publicKey))).toBe('wrong-key');
  });

  it('refuses a message signed more than a minute away', async () => {
    const key = await generateIdentityKey();
    const message = await signedMessage(key, { sentAt: Date.now() - 61_000 });

    expect(await trustOf(message, await exportPublicKey(key.publicKey))).toBe('stale');
  });

  it('lets the same message through once', async () => {
    const guard = new SignalReplayGuard();
    const message = await signedMessage(await generateIdentityKey());

    expect(guard.accept(message)).toBe(true);
    expect(guard.accept(message)).toBe(false);
  });
});

describe('pairing proof', () => {
  async function scan(secretUsed: string) {
    const scanner = await generateIdentityKey();
    const publicKey = await exportPublicKey(scanner.publicKey);
    const deviceId = await deviceIdOf(publicKey);
    const request = {
      deviceId,
      name: 'Pixel',
      publicKey,
      proof: await signProof(secretUsed, deviceId, publicKey),
      server: '',
    };
    const signal = await signedMessage(scanner, {
      kind: 'pair',
      body: JSON.stringify(request),
      fromDeviceId: deviceId,
    });
    return { request, signal };
  }

  it('accepts a scanner that read this code', async () => {
    const secret = newPairingSecret();
    const { request, signal } = await scan(secret);

    expect(await isProvenScan(secret, signal, request)).toBe(true);
  });

  it('refuses a scanner that read another code', async () => {
    const { request, signal } = await scan(newPairingSecret());

    expect(await isProvenScan(newPairingSecret(), signal, request)).toBe(false);
  });

  it('refuses a scanner claiming an id that is not its key', async () => {
    const secret = newPairingSecret();
    const { request, signal } = await scan(secret);
    const claimed = { ...request, deviceId: 'c'.repeat(32) };
    claimed.proof = await signProof(secret, claimed.deviceId, claimed.publicKey);

    expect(await isProvenScan(secret, signal, claimed)).toBe(false);
  });
});
