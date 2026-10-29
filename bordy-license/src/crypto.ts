import { TokenPayload } from './types';
import { base64urlEncode } from './utils';

let cachedKey: CryptoKey | null = null;

/**
 * Import the ECDSA P-256 private key from PKCS8 base64.
 */
export async function getPrivateKey(base64Pkcs8: string): Promise<CryptoKey> {
  if (cachedKey) return cachedKey;

  const binaryStr = atob(base64Pkcs8);
  const bytes = new Uint8Array(binaryStr.length);
  for (let i = 0; i < binaryStr.length; i++) {
    bytes[i] = binaryStr.charCodeAt(i);
  }

  cachedKey = await crypto.subtle.importKey(
    'pkcs8',
    bytes.buffer,
    { name: 'ECDSA', namedCurve: 'P-256' },
    false,
    ['sign']
  );
  return cachedKey;
}

/**
 * Sign a token payload and return "base64url(json).<base64url(signature)>".
 * The signature is raw r||s (64 bytes) — NOT DER.
 */
export async function signToken(payload: TokenPayload, privateKey: CryptoKey): Promise<string> {
  const payloadJson = JSON.stringify(payload);
  const payloadBytes = new TextEncoder().encode(payloadJson);
  const payloadB64 = base64urlEncode(payloadBytes);

  const signature = await crypto.subtle.sign(
    { name: 'ECDSA', hash: 'SHA-256' },
    privateKey,
    payloadBytes
  );

  const sigB64 = base64urlEncode(new Uint8Array(signature));
  return `${payloadB64}.${sigB64}`;
}
