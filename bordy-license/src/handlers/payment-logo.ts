import { Env } from '../types';
import { getSetting, setSetting } from '../db';
import { errorResponse, parseBoundedJson } from '../utils';
import { sniffImageMime } from './orders';

const MAX_LOGO_BYTES = 300 * 1024;
const ALLOWED_LOGO_MIMES = new Set(['image/jpeg', 'image/png', 'image/webp']);

export function isValidPaymentMethodId(id: string): boolean {
  return /^[a-z0-9_-]{1,40}$/.test(id);
}

function paymentLogoKey(id: string): string {
  return `payment_logo:${id}`;
}

function decodeDataUrl(dataUrl: string | null | undefined): { mime: string; bytes: Uint8Array } | null {
  if (!dataUrl || !dataUrl.startsWith('data:')) return null;
  const commaIndex = dataUrl.indexOf(',');
  if (commaIndex < 0) return null;
  const header = dataUrl.substring(5, commaIndex);
  const mime = (header.split(';')[0] || '').toLowerCase();
  if (!ALLOWED_LOGO_MIMES.has(mime)) return null;
  let bytes: Uint8Array;
  try {
    const binary = atob(dataUrl.substring(commaIndex + 1));
    bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) {
      bytes[i] = binary.charCodeAt(i);
    }
  } catch {
    return null;
  }
  const sniffed = sniffImageMime(bytes);
  if (!sniffed || !ALLOWED_LOGO_MIMES.has(sniffed)) return null;
  return { mime: sniffed, bytes };
}

/**
 * Public logo endpoint: GET /v1/payment-logo?id=<method id>
 */
export async function handleGetPaymentLogo(request: Request, env: Env): Promise<Response> {
  const url = new URL(request.url);
  const id = (url.searchParams.get('id') || '').trim().toLowerCase();
  if (!isValidPaymentMethodId(id)) return new Response(null, { status: 404 });
  const dataUrl = await getSetting(env.DB, paymentLogoKey(id));
  const decoded = decodeDataUrl(dataUrl);
  if (!decoded) return new Response(null, { status: 404 });
  return new Response(decoded.bytes, {
    status: 200,
    headers: {
      'Content-Type': decoded.mime,
      'Cache-Control': 'public, max-age=3600',
    },
  });
}

/**
 * Admin save/delete: POST /admin/api/payment-logo { id, logo_data }
 * An empty logo_data removes the stored logo.
 */
export async function apiSavePaymentLogo(request: Request, env: Env): Promise<Response> {
  const body = await parseBoundedJson<{ id?: unknown; logo_data?: unknown }>(request, 700 * 1024);
  if (!body) return errorResponse('صيغة الطلب غير صالحة', 400);
  const id = typeof body.id === 'string' ? body.id.trim().toLowerCase() : '';
  if (!isValidPaymentMethodId(id)) return errorResponse('معرف طريقة الدفع غير صالح', 400);

  const logoData = typeof body.logo_data === 'string' ? body.logo_data.trim() : '';
  if (!logoData) {
    await setSetting(env.DB, paymentLogoKey(id), '');
    return Response.json({ ok: true, has_logo: false });
  }

  const decoded = decodeDataUrl(logoData);
  if (!decoded) return errorResponse('صورة غير مدعومة، استخدم JPG أو PNG', 400, 'INVALID_IMAGE');
  if (decoded.bytes.length > MAX_LOGO_BYTES) {
    return errorResponse('حجم الصورة كبير جداً', 400, 'LOGO_TOO_LARGE');
  }

  const base64Part = logoData.substring(logoData.indexOf(',') + 1);
  await setSetting(env.DB, paymentLogoKey(id), `data:${decoded.mime};base64,${base64Part}`);
  return Response.json({ ok: true, has_logo: true });
}
