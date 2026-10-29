import { Env } from '../types';
import { getSetting } from '../db';
import { jsonResponse, errorResponse } from '../utils';

export async function handleGetUpdate(request: Request, env: Env): Promise<Response> {
  const minVer = parseInt(await getSetting(env.DB, 'min_app_version') || '1');

  // Return latest version info and download link
  return jsonResponse({
    ok: true,
    min_version: minVer,
    download_url: 'https://t.me/XXXXXXX',
  });
}
