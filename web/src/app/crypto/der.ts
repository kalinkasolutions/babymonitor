// WebCrypto signs ECDSA as raw r||s; the phones and the server use DER, as Java does.

const P256IntegerLength = 32;

export function rawToDer(raw: Uint8Array): Uint8Array<ArrayBuffer> {
  const half = raw.length / 2;
  const r = derInteger(raw.slice(0, half));
  const s = derInteger(raw.slice(half));
  return new Uint8Array([0x30, r.length + s.length, ...r, ...s]);
}

export function derToRaw(der: Uint8Array): Uint8Array<ArrayBuffer> {
  let offset = 0;
  const expect = (tag: number) => {
    if (der[offset++] !== tag) {
      throw new Error('Not a DER ECDSA signature');
    }
  };
  const readLength = () => {
    const first = der[offset++];
    if (first < 0x80) {
      return first;
    }
    let length = 0;
    for (let count = first & 0x7f; count > 0; count--) {
      length = (length << 8) | der[offset++];
    }
    return length;
  };
  const readInteger = () => {
    expect(0x02);
    const length = readLength();
    const value = der.slice(offset, offset + length);
    offset += length;
    return fixedWidth(value);
  };

  expect(0x30);
  readLength();
  const r = readInteger();
  const s = readInteger();
  return new Uint8Array([...r, ...s]);
}

function derInteger(unsigned: Uint8Array): number[] {
  let start = 0;
  while (start < unsigned.length - 1 && unsigned[start] === 0) {
    start++;
  }
  const value = Array.from(unsigned.slice(start));
  // A set top bit would read as negative, so DER puts a zero in front.
  if (value[0] & 0x80) {
    value.unshift(0);
  }
  return [0x02, value.length, ...value];
}

function fixedWidth(value: Uint8Array): Uint8Array {
  const trimmed = value.slice(Math.max(0, value.length - P256IntegerLength));
  const padded = new Uint8Array(P256IntegerLength);
  padded.set(trimmed, P256IntegerLength - trimmed.length);
  return padded;
}
