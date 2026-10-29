import { Env } from '../types';
import { sendTelegram } from '../telegram';
import { checkRateLimit } from '../rate-limit';
import { timingSafeEqualString, base64urlEncode, base64urlDecode } from '../utils';

const SESSION_TTL = 12 * 3600000; // 12 hours
const MAX_ATTEMPTS = 5;

/**
 * TOTP verification (RFC 6238, SHA-1, 6 digits, 30-second step).
 */
async function verifyTOTP(secret: string, token: string): Promise<boolean> {
  const now = Math.floor(Date.now() / 1000);
  // Check current and ±1 time step for clock skew
  for (const offset of [-1, 0, 1]) {
    const timeStep = Math.floor(now / 30) + offset;
    const generated = await generateTOTP(secret, timeStep);
    if (generated === token) return true;
  }
  return false;
}

async function generateTOTP(secret: string, timeStep: number): Promise<string> {
  // Decode base32 secret
  const keyBytes = base32Decode(secret);

  // Create HMAC-SHA1 key
  const key = await crypto.subtle.importKey(
    'raw', keyBytes, { name: 'HMAC', hash: 'SHA-1' }, false, ['sign']
  );

  // Time step as 8-byte big-endian
  const msgBuf = new ArrayBuffer(8);
  const msgView = new DataView(msgBuf);
  msgView.setUint32(4, timeStep);

  const hmac = new Uint8Array(await crypto.subtle.sign('HMAC', key, msgBuf));

  // Dynamic truncation
  const offset = hmac[hmac.length - 1] & 0x0f;
  const code = (
    ((hmac[offset] & 0x7f) << 24) |
    ((hmac[offset + 1] & 0xff) << 16) |
    ((hmac[offset + 2] & 0xff) << 8) |
    (hmac[offset + 3] & 0xff)
  ) % 1000000;

  return String(code).padStart(6, '0');
}

function base32Decode(input: string): Uint8Array {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  const cleaned = input.replace(/[\s=-]/g, '').toUpperCase();
  let bits = '';
  for (const c of cleaned) {
    const val = alphabet.indexOf(c);
    if (val === -1) continue;
    bits += val.toString(2).padStart(5, '0');
  }
  const bytes = new Uint8Array(Math.floor(bits.length / 8));
  for (let i = 0; i < bytes.length; i++) {
    bytes[i] = parseInt(bits.substring(i * 8, i * 8 + 8), 2);
  }
  return bytes;
}

/**
 * Sign session data with ADMIN_SECRET via HMAC-SHA256.
 * Format: `timestamp.nonce.signature`
 */
async function signSessionToken(issuedAt: number, nonce: string, secret: string): Promise<string> {
  const enc = new TextEncoder();
  const data = enc.encode(`${issuedAt}:${nonce}`);
  const key = await crypto.subtle.importKey(
    'raw',
    enc.encode(secret),
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign']
  );
  const sigBytes = new Uint8Array(await crypto.subtle.sign('HMAC', key, data));
  return `${issuedAt}.${nonce}.${base64urlEncode(sigBytes)}`;
}

/**
 * Validate a session cookie statelessly across all Cloudflare Workers edge nodes.
 */
export async function validateSession(cookieHeader: string | null, env: Env): Promise<boolean> {
  if (!cookieHeader) return false;
  const match = cookieHeader.match(/session=([A-Za-z0-9_.-]+)/);
  if (!match) return false;

  const parts = match[1].split('.');
  if (parts.length !== 3) return false;

  const [issuedStr, nonce, sigB64] = parts;
  const issuedAt = parseInt(issuedStr, 10);
  if (isNaN(issuedAt)) return false;

  const now = Date.now();
  if (now < issuedAt || now - issuedAt > SESSION_TTL) {
    return false;
  }

  const enc = new TextEncoder();
  const data = enc.encode(`${issuedAt}:${nonce}`);
  const key = await crypto.subtle.importKey(
    'raw',
    enc.encode(env.ADMIN_SECRET),
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['verify']
  );

  try {
    const sigBytes = base64urlDecode(sigB64);
    return await crypto.subtle.verify('HMAC', key, sigBytes, data);
  } catch {
    return false;
  }
}

/**
 * Attempt to login with secret + TOTP.
 */
export async function attemptLogin(env: Env, secret: string, totp: string, ip: string): Promise<string | null> {
  const rateLimitKey = `admin_login_rate:${ip}`;
  const allowed = await checkRateLimit(env.DB, rateLimitKey, MAX_ATTEMPTS);
  if (!allowed) {
    return null;
  }

  // Verify secret in constant time
  const secretValid = await timingSafeEqualString(secret, env.ADMIN_SECRET);
  if (!secretValid) {
    await recordFailure(ip, env);
    return null;
  }

  const totpValid = await verifyTOTP(env.ADMIN_TOTP_SECRET, totp);
  if (!totpValid) {
    await recordFailure(ip, env);
    return null;
  }

  // Generate random 16-byte nonce
  const nonceArr = new Uint8Array(16);
  crypto.getRandomValues(nonceArr);
  const nonce = base64urlEncode(nonceArr);

  return signSessionToken(Date.now(), nonce, env.ADMIN_SECRET);
}

async function recordFailure(ip: string, env: Env): Promise<void> {
  const key = `admin_fail_alert:${ip}`;
  const underLimit = await checkRateLimit(env.DB, key, MAX_ATTEMPTS);
  if (!underLimit) {
    await sendTelegram(env.TG_BOT_TOKEN, env.OWNER_CHAT_ID,
      `🚨 <b>تنبيه أمني</b>\n\nمحاولات دخول فاشلة متكررة للوحة الإدارة\nIP: <code>${ip}</code>`);
  }
}

async function csrfForSession(session: string, secret: string): Promise<string> {
  const enc = new TextEncoder();
  const key = await crypto.subtle.importKey(
    'raw', enc.encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign'],
  );
  const signature = await crypto.subtle.sign('HMAC', key, enc.encode(`csrf:${session}`));
  return base64urlEncode(new Uint8Array(signature));
}

export async function getCsrfToken(cookieHeader: string | null, env: Env): Promise<string | null> {
  const session = cookieHeader?.match(/session=([A-Za-z0-9_.-]+)/)?.[1];
  if (!session || !await validateSession(cookieHeader, env)) return null;
  return csrfForSession(session, env.ADMIN_SECRET);
}

export async function validateCsrf(request: Request, env: Env): Promise<boolean> {
  const cookie = request.headers.get('Cookie');
  const expected = await getCsrfToken(cookie, env);
  const supplied = request.headers.get('X-CSRF-Token') || '';
  if (!expected || !supplied || !await timingSafeEqualString(expected, supplied)) return false;
  const origin = request.headers.get('Origin');
  if (origin && origin !== new URL(request.url).origin) return false;
  return true;
}

/**
 * Create a Set-Cookie header for a session.
 */
export function createSessionCookie(sessionId: string): string {
  return `session=${sessionId}; HttpOnly; Secure; SameSite=Strict; Path=/admin; Max-Age=${12 * 3600}`;
}

/**
 * Create a cookie that clears the session.
 */
export function clearSessionCookie(): string {
  return 'session=; HttpOnly; Secure; SameSite=Strict; Path=/admin; Max-Age=0';
}
