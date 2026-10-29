/**
 * Normalize a discount/promo code:
 * - Convert Arabic-Indic and Extended Arabic-Indic digits to Latin
 * - Remove spaces, dashes, underscores
 * - Uppercase
 */
export function normalizeCode(input: string): string {
  return input
    .replace(/[٠-٩]/g, c => String.fromCharCode(c.charCodeAt(0) - 0x0660 + 0x0030))
    .replace(/[۰-۹]/g, c => String.fromCharCode(c.charCodeAt(0) - 0x06F0 + 0x0030))
    .replace(/[\s\-_]/g, '')
    .toUpperCase();
}

/**
 * Round a price to the nearest 250 IQD.
 */
export function roundPrice(price: number): number {
  return Math.round(price / 250) * 250;
}

/**
 * Generate a short order ID like MB-X3KRW (no ambiguous chars O/0/1/I).
 */
export function generateOrderId(): string {
  const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  let result = 'MB-';
  for (let i = 0; i < 5; i++) {
    result += chars[Math.floor(Math.random() * chars.length)];
  }
  return result;
}

/**
 * Validate device ID is 32 hex chars.
 */
export function isValidDeviceId(did: string): boolean {
  return /^[0-9a-fA-F]{32}$/.test(did);
}

/**
 * Create a JSON response with security headers and server_time.
 */
export function jsonResponse(body: Record<string, unknown>, status = 200): Response {
  return new Response(JSON.stringify({ ...body, server_time: Date.now() }), {
    status,
    headers: {
      'Content-Type': 'application/json',
      'Cache-Control': 'no-store',
    },
  });
}

/**
 * Create an error JSON response.
 * `code` is a stable machine-readable identifier the app can map to localized copy.
 */
export function errorResponse(error: string, status = 400, code?: string): Response {
  return jsonResponse(code ? { ok: false, error, code } : { ok: false, error }, status);
}

/**
 * Payment methods configured in settings, defensively parsed.
 */
export interface PaymentMethodInfo {
  name: string;
  account: string;
  note: string;
}

export function parsePaymentMethods(raw: string | null | undefined): PaymentMethodInfo[] {
  try {
    const parsed = JSON.parse(raw || '[]');
    if (!Array.isArray(parsed)) return [];
    return parsed
      .filter((m: any) => m && typeof m.name === 'string' && m.name.trim() && typeof m.account === 'string' && m.account.trim())
      .map((m: any) => ({
        name: String(m.name).trim(),
        account: String(m.account).trim(),
        note: typeof m.note === 'string' ? m.note : '',
      }));
  } catch {
    return [];
  }
}

/**
 * base64url encode a Uint8Array.
 */
export function base64urlEncode(data: Uint8Array): string {
  let binary = '';
  for (let i = 0; i < data.length; i++) {
    binary += String.fromCharCode(data[i]);
  }
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/**
 * base64url decode to Uint8Array.
 */
export function base64urlDecode(str: string): Uint8Array {
  str = str.replace(/-/g, '+').replace(/_/g, '/');
  while (str.length % 4) str += '=';
  const binary = atob(str);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes;
}

/**
 * Format epoch ms to Baghdad time string (UTC+3).
 */
export function formatBaghdadTime(epochMs: number): string {
  const d = new Date(epochMs + 3 * 60 * 60 * 1000);
  const yyyy = d.getUTCFullYear();
  const mm = String(d.getUTCMonth() + 1).padStart(2, '0');
  const dd = String(d.getUTCDate()).padStart(2, '0');
  const hh = String(d.getUTCHours()).padStart(2, '0');
  const mi = String(d.getUTCMinutes()).padStart(2, '0');
  return `${yyyy}-${mm}-${dd} ${hh}:${mi}`;
}

/**
 * Parse a request body as JSON, returning null on failure.
 */
export async function parseJsonBody<T = Record<string, unknown>>(request: Request): Promise<T | null> {
  try {
    return await request.json() as T;
  } catch {
    return null;
  }
}

export async function parseBoundedJson<T = Record<string, unknown>>(
  request: Request,
  maxBytes = 512 * 1024,
): Promise<T | null> {
  const length = Number(request.headers.get('Content-Length') || '0');
  if (Number.isFinite(length) && length > maxBytes) return null;
  try {
    const text = await request.text();
    if (new TextEncoder().encode(text).byteLength > maxBytes) return null;
    const parsed = JSON.parse(text);
    return parsed && typeof parsed === 'object' ? parsed as T : null;
  } catch {
    return null;
  }
}

/**
 * Constant-time string comparison using SHA-256 digests to prevent timing attacks.
 */
export async function timingSafeEqualString(a: string, b: string): Promise<boolean> {
  if (!a || !b) return false;
  const enc = new TextEncoder();
  const aDigest = new Uint8Array(await crypto.subtle.digest('SHA-256', enc.encode(a)));
  const bDigest = new Uint8Array(await crypto.subtle.digest('SHA-256', enc.encode(b)));
  let diff = 0;
  for (let i = 0; i < aDigest.length; i++) {
    diff |= aDigest[i] ^ bDigest[i];
  }
  return diff === 0 && a.length === b.length;
}

