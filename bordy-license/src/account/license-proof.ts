import { TokenPayload } from '../types';
import { base64urlDecode } from '../utils';

let cachedPublicKey: CryptoKey | null = null;

async function derivePublicKey(privateKeyBase64: string): Promise<CryptoKey> {
  if (cachedPublicKey) return cachedPublicKey;
  const binary = atob(privateKeyBase64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  const privateKey = await crypto.subtle.importKey(
    'pkcs8',
    bytes,
    { name: 'ECDSA', namedCurve: 'P-256' },
    true,
    ['sign'],
  );
  const jwk = await crypto.subtle.exportKey('jwk', privateKey) as JsonWebKey;
  const publicJwk: JsonWebKey = {
    kty: jwk.kty,
    crv: jwk.crv,
    x: jwk.x,
    y: jwk.y,
    ext: true,
    key_ops: ['verify'],
  };
  cachedPublicKey = await crypto.subtle.importKey(
    'jwk',
    publicJwk,
    { name: 'ECDSA', namedCurve: 'P-256' },
    false,
    ['verify'],
  );
  return cachedPublicKey;
}

export async function verifyLegacyLicenseToken(
  token: string,
  expectedDeviceId: string,
  privateKeyBase64: string,
): Promise<TokenPayload | null> {
  const parts = token.split('.');
  if (parts.length !== 2) return null;
  try {
    const payloadBytes = base64urlDecode(parts[0]);
    const signature = base64urlDecode(parts[1]);
    const publicKey = await derivePublicKey(privateKeyBase64);
    const valid = await crypto.subtle.verify(
      { name: 'ECDSA', hash: 'SHA-256' },
      publicKey,
      signature,
      payloadBytes,
    );
    if (!valid) return null;
    const payload = JSON.parse(new TextDecoder().decode(payloadBytes)) as TokenPayload;
    if (payload.v !== 1 || payload.did !== expectedDeviceId || !payload.iat || !payload.exp) return null;
    return payload;
  } catch {
    return null;
  }
}
