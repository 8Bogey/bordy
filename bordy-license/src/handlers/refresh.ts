import { Env } from '../types';
import { getDevice, getSettings } from '../db';
import { getPrivateKey, signToken } from '../crypto';
import { buildTokenPayload } from './activate';
import { isValidDeviceId, jsonResponse, errorResponse, parseJsonBody } from '../utils';
import { checkRateLimit } from '../rate-limit';
import { refreshBodySchema } from '../validate';

export async function handleRefresh(request: Request, env: Env): Promise<Response> {
  const parsed = refreshBodySchema.safeParse(await parseJsonBody(request));
  if (!parsed.success) {
    return errorResponse('Missing required fields');
  }
  const body = parsed.data;

  if (!isValidDeviceId(body.did)) {
    return errorResponse('Invalid device ID');
  }

  // Rate limit by IP
  const ip = request.headers.get('CF-Connecting-IP') || 'unknown';
  const allowed = await checkRateLimit(env.DB, `refresh:${ip}`, 10);
  if (!allowed) {
    return errorResponse('Rate limited', 429);
  }

  const settings = await getSettings(env.DB, [
    'paid_token_ttl_days', 'grace_days', 'min_app_version',
    'maintenance', 'tos_text', 'privacy_text', 'tos_version'
  ]);

  if (settings.maintenance === 'on') {
    return jsonResponse({ ok: false, error: 'Service under maintenance' }, 503);
  }

  const device = await getDevice(env.DB, body.did);
  if (!device) {
    return errorResponse('Device not registered', 404);
  }

  if (device.blocked) {
    return jsonResponse({
      ok: false,
      status: 'BLOCKED',
      reason: device.block_reason || 'Account blocked',
    });
  }

  const now = Date.now();
  const paidTtlDays = parseInt(settings.paid_token_ttl_days || '7');
  const graceDays = parseInt(settings.grace_days || '2');
  const minAppVersion = parseInt(settings.min_app_version || '1');
  const tosVersion = parseInt(settings.tos_version || '1');

  const tokenPayload = buildTokenPayload(device, now, body.nonce, paidTtlDays, graceDays, minAppVersion, tosVersion);

  const privateKey = await getPrivateKey(env.LICENSE_PRIVATE_KEY);
  const token = await signToken(tokenPayload, privateKey);

  return jsonResponse({
    ok: true,
    token,
    status: tokenPayload.st,
    tos: {
      version: tosVersion,
      text: settings.tos_text || '',
      privacy: settings.privacy_text || '',
    },
  });
}
