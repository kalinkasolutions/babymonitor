import { createHash, createSign, createVerify, generateKeyPairSync } from 'node:crypto';
import { describe, expect, it } from 'vitest';
import { fromBase64, toBase64, utf8 } from './bytes';
import {
  deviceIdOf,
  exportPublicKey,
  fingerprintOf,
  generateIdentityKey,
  signWith,
  verifySignature,
} from './keys';

describe('identity keys', () => {
  it('signs in the DER form Java and .NET verify', async () => {
    const key = await generateIdentityKey();
    const publicKey = await exportPublicKey(key.publicKey);
    const data = utf8('babymonitor-hub\n1700000000000');

    const signature = await signWith(key.privateKey, data);

    const verifier = createVerify('SHA256');
    verifier.update(data);
    const pem = { key: Buffer.from(fromBase64(publicKey)), format: 'der', type: 'spki' } as const;
    expect(verifier.verify(pem, Buffer.from(fromBase64(signature)))).toBe(true);
  });

  it('verifies a DER signature made the way the phones make it', async () => {
    const { publicKey, privateKey } = generateKeyPairSync('ec', { namedCurve: 'P-256' });
    const data = utf8('1700000000000\nabc\nstart\n{}');
    // Many signatures, so short r and s values, which DER trims, are among them.
    for (let attempt = 0; attempt < 50; attempt++) {
      const signer = createSign('SHA256');
      signer.update(data);
      const signature = toBase64(signer.sign(privateKey));
      const spki = toBase64(publicKey.export({ format: 'der', type: 'spki' }));

      expect(await verifySignature(spki, data, signature)).toBe(true);
    }
  });

  it('refuses a signature over different bytes', async () => {
    const key = await generateIdentityKey();
    const publicKey = await exportPublicKey(key.publicKey);
    const signature = await signWith(key.privateKey, utf8('one'));

    expect(await verifySignature(publicKey, utf8('two'), signature)).toBe(false);
  });

  it('derives the id and fingerprint from the same digests the app uses', async () => {
    const { publicKey } = generateKeyPairSync('ec', { namedCurve: 'P-256' });
    const der = publicKey.export({ format: 'der', type: 'spki' });
    const spki = toBase64(der);
    const fingerprintHex = createHash('sha256').update(spki).digest('hex').slice(0, 24);

    expect(await deviceIdOf(spki)).toBe(
      createHash('sha256').update(der).digest('hex').slice(0, 32),
    );
    expect(await fingerprintOf(spki)).toBe(fingerprintHex.match(/.{4}/g)!.join(' '));
  });
});
