import { Env } from '../types';
import { getSettings } from '../db';
import { jsonResponse } from '../utils';

export async function handleGetTos(request: Request, env: Env): Promise<Response> {
  const settings = await getSettings(env.DB, ['tos_text', 'privacy_text', 'tos_version']);

  return jsonResponse({
    ok: true,
    tos_text: settings.tos_text || '',
    privacy_text: settings.privacy_text || '',
    tos_version: parseInt(settings.tos_version || '1'),
  });
}
