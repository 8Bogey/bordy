import { AccessClaims } from './types';
import { base64urlDecode, base64urlEncode } from '../utils';

export const PASSWORD_ITERATIONS = 100_000;
export const ACCESS_TOKEN_TTL_MS = 10 * 60 * 1000;
export const REFRESH_TOKEN_TTL_MS = 30 * 24 * 60 * 60 * 1000;

const encoder = new TextEncoder();

export function randomId(): string {
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  return base64urlEncode(bytes);
}

export function randomSecret(size = 32): string {
  const bytes = new Uint8Array(size);
  crypto.getRandomValues(bytes);
  return base64urlEncode(bytes);
}

export function normalizeUsername(value: string): string {
  return value.trim().toLocaleLowerCase('en-US');
}

export function validateCredentials(username: string, password: string): string | null {
  if (!/^[a-zA-Z0-9._-]{3,40}$/.test(username.trim())) {
    return 'اسم المستخدم يجب أن يكون 3–40 حرفًا إنجليزيًا أو رقمًا، ويسمح بالنقطة والشرطة فقط';
  }
  if (password.length < 10 || password.length > 128) {
    return 'كلمة المرور يجب أن تكون بين 10 و128 حرفًا';
  }
  return null;
}

async function importHmac(secret: string, usage: Array<'sign' | 'verify'>): Promise<CryptoKey> {
  return crypto.subtle.importKey(
    'raw',
    encoder.encode(secret),
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    usage,
  );
}

export async function sha256Base64url(value: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', encoder.encode(value));
  return base64urlEncode(new Uint8Array(digest));
}

export async function hashPrivateIdentifier(value: string, pepper: string): Promise<string> {
  return sha256Base64url(`${pepper}\u0000${value}`);
}

export async function derivePasswordHash(
  password: string,
  pepper: string,
  salt?: Uint8Array,
  iterations = PASSWORD_ITERATIONS,
): Promise<{ hash: string; salt: string; iterations: number }> {
  const actualSalt = salt || (() => {
    const generated = new Uint8Array(16);
    crypto.getRandomValues(generated);
    return generated;
  })();
  const material = await crypto.subtle.importKey(
    'raw',
    encoder.encode(`${password}\u0000${pepper}`),
    'PBKDF2',
    false,
    ['deriveBits'],
  );
  const bits = await crypto.subtle.deriveBits(
    { name: 'PBKDF2', hash: 'SHA-256', salt: actualSalt, iterations },
    material,
    256,
  );
  return {
    hash: base64urlEncode(new Uint8Array(bits)),
    salt: base64urlEncode(actualSalt),
    iterations,
  };
}

export async function verifyPassword(
  password: string,
  pepper: string,
  saltEncoded: string,
  expectedHash: string,
  iterations: number,
): Promise<boolean> {
  try {
    const actual = await derivePasswordHash(password, pepper, base64urlDecode(saltEncoded), iterations);
    const key = await importHmac(pepper, ['sign']);
    const [left, right] = await Promise.all([
      crypto.subtle.sign('HMAC', key, encoder.encode(actual.hash)),
      crypto.subtle.sign('HMAC', key, encoder.encode(expectedHash)),
    ]);
    const a = new Uint8Array(left);
    const b = new Uint8Array(right);
    let diff = a.length ^ b.length;
    for (let i = 0; i < Math.min(a.length, b.length); i++) diff |= a[i] ^ b[i];
    return diff === 0;
  } catch {
    return false;
  }
}

export async function signAccessToken(claims: AccessClaims, secret: string): Promise<string> {
  const payload = base64urlEncode(encoder.encode(JSON.stringify(claims)));
  const key = await importHmac(secret, ['sign']);
  const signature = await crypto.subtle.sign('HMAC', key, encoder.encode(payload));
  return `${payload}.${base64urlEncode(new Uint8Array(signature))}`;
}

export async function verifyAccessToken(token: string, secret: string): Promise<AccessClaims | null> {
  const parts = token.split('.');
  if (parts.length !== 2) return null;
  try {
    const key = await importHmac(secret, ['verify']);
    const valid = await crypto.subtle.verify(
      'HMAC',
      key,
      base64urlDecode(parts[1]),
      encoder.encode(parts[0]),
    );
    if (!valid) return null;
    const claims = JSON.parse(new TextDecoder().decode(base64urlDecode(parts[0]))) as AccessClaims;
    const now = Date.now();
    if (claims.v !== 1 || claims.aud !== 'bordy-account' || !claims.aid || !claims.did || !claims.sid) return null;
    if (!Number.isFinite(claims.iat) || !Number.isFinite(claims.exp) || claims.iat > now + 60_000 || claims.exp <= now) return null;
    return claims;
  } catch {
    return null;
  }
}
