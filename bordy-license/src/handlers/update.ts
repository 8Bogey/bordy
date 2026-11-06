import { Env } from '../types';
import { loadUpdateConfig } from '../update-config';
import { jsonResponse } from '../utils';

/**
 * Public update check for the app. Tiny JSON — the device decides what to do:
 * versions below `min_version` are blocked, `latest_version` (which may be
 * *older* than the device's, for rollbacks) is what gets installed.
 * `trigger_*` is the owner's immediate prompt; it never replaces `check_*`.
 */
export async function handleGetUpdate(_request: Request, env: Env): Promise<Response> {
  const config = await loadUpdateConfig(env);
  return jsonResponse({
    ok: true,
    min_version: config.minVersion,
    latest_version: config.latestVersion,
    download_url: config.downloadUrl,
    notes: config.notes,
    check_unit: config.checkUnit,
    check_value: config.checkValue,
    check_interval_seconds: config.checkIntervalSeconds,
    check_hours: config.checkHours,
    wifi_only: config.wifiOnly,
    trigger_mode: config.triggerMode,
    trigger_id: config.triggerId,
    trigger_message: config.triggerMessage,
  });
}
