import { Env } from './types';
import { getSettings } from './db';
import { canonicalVersion, compareVersions, isValidVersion } from './version';

export const UPDATE_CHECK_UNITS = ['seconds', 'minutes', 'hours'] as const;
export type UpdateCheckUnit = typeof UPDATE_CHECK_UNITS[number];

/** The owner asked for fine-grained polling, but never below five of the chosen unit. */
export const UPDATE_CHECK_MIN = 5;
export const UPDATE_CHECK_DEFAULT_UNIT: UpdateCheckUnit = 'hours';
export const UPDATE_CHECK_DEFAULT_VALUE = 12;

/** Ceiling per unit: one day for seconds/minutes, one week for hours. */
export const UPDATE_CHECK_MAX: Record<UpdateCheckUnit, number> = {
  seconds: 86400,
  minutes: 1440,
  hours: 168,
};

/**
 * Immediate update prompt the owner can fire from the panel. It never replaces
 * the periodic check — it only accelerates devices that are already talking to
 * the server (app open → /v1/sync, app launched → /v1/update).
 *  off      — no prompt
 *  optional — dismissible prompt, reappears only when a new trigger is fired
 *  forced   — non-dismissible prompt while the device is behind `latest_app_version`
 */
export const UPDATE_TRIGGER_MODES = ['off', 'optional', 'forced'] as const;
export type UpdateTriggerMode = typeof UPDATE_TRIGGER_MODES[number];

export const UPDATE_TRIGGER_DEFAULT_MODE: UpdateTriggerMode = 'off';
export const UPDATE_TRIGGER_MESSAGE_MAX = 200;

export interface UpdateTrigger {
  mode: UpdateTriggerMode;
  /** Bumped on every fire so a device that already dismissed an optional prompt shows the new one. */
  id: number;
  message: string;
  latestVersion: string;
}

export interface UpdateConfig {
  minVersion: string;
  latestVersion: string;
  downloadUrl: string;
  notes: string;
  checkUnit: UpdateCheckUnit;
  checkValue: number;
  /** Single number the app schedules by. */
  checkIntervalSeconds: number;
  /** Legacy shape kept for clients that only understand whole hours. */
  checkHours: number;
  wifiOnly: boolean;
  triggerMode: UpdateTriggerMode;
  triggerId: number;
  triggerMessage: string;
}

export function clampCheckUnit(raw: string | undefined | null): UpdateCheckUnit {
  return UPDATE_CHECK_UNITS.includes(raw as UpdateCheckUnit)
    ? raw as UpdateCheckUnit
    : UPDATE_CHECK_DEFAULT_UNIT;
}

export function clampCheckValue(raw: string | undefined | null, unit: UpdateCheckUnit): number {
  const parsed = parseInt(String(raw ?? '').trim(), 10);
  const value = Number.isFinite(parsed) ? parsed : UPDATE_CHECK_DEFAULT_VALUE;
  return Math.min(UPDATE_CHECK_MAX[unit], Math.max(UPDATE_CHECK_MIN, value));
}

export function checkIntervalSeconds(unit: UpdateCheckUnit, value: number): number {
  const multiplier = unit === 'hours' ? 3600 : unit === 'minutes' ? 60 : 1;
  return value * multiplier;
}

export function wifiOnlyEnabled(raw: string | undefined | null): boolean {
  // Absent key means "on": downloads wait for Wi-Fi unless the owner opts out.
  return raw !== '0' && raw !== 'off';
}

export function clampTriggerMode(raw: string | undefined | null): UpdateTriggerMode {
  return UPDATE_TRIGGER_MODES.includes(raw as UpdateTriggerMode)
    ? raw as UpdateTriggerMode
    : UPDATE_TRIGGER_DEFAULT_MODE;
}

export function clampTriggerId(raw: string | number | undefined | null): number {
  const parsed = typeof raw === 'number' ? raw : parseInt(String(raw ?? '').trim(), 10);
  return Number.isFinite(parsed) && parsed > 0 ? Math.floor(parsed) : 0;
}

export function triggerMessageText(raw: unknown): string {
  return String(raw ?? '').trim().slice(0, UPDATE_TRIGGER_MESSAGE_MAX);
}

/**
 * Pure so `/v1/update` and `/v1/sync` can share one settings row without a
 * second query. `latestVersion` is already canonical/sane by the caller.
 */
export function readTrigger(
  s: Record<string, string>,
  latestVersion: string,
): UpdateTrigger {
  return {
    mode: clampTriggerMode(s.update_trigger_mode),
    id: clampTriggerId(s.update_trigger_id),
    message: triggerMessageText(s.update_trigger_message),
    latestVersion,
  };
}

const TRIGGER_KEYS = ['update_trigger_mode', 'update_trigger_id', 'update_trigger_message'];

/**
 * Wire shape of the trigger inside `/v1/sync`. Keys are snake_case on purpose:
 * the Android client reads `latest_version`, and a camelCase name would reach it
 * as an empty version — the device then assumes it is already up to date and
 * shows nothing at all.
 */
export function triggerPayload(t: UpdateTrigger | null): Record<string, string | number> {
  if (!t) return { mode: 'off' };
  return { mode: t.mode, id: t.id, message: t.message, latest_version: t.latestVersion };
}

/** The pair of versions every client must obey: sane minimum, "latest" never older than it. */
export function saneVersions(minRaw: string | undefined, latestRaw: string | undefined):
  { minVersion: string; latestVersion: string } {
  const storedMin = minRaw ? canonicalVersion(minRaw) : '';
  const storedLatest = latestRaw ? canonicalVersion(latestRaw) : '';
  const minVersion = isValidVersion(storedMin) ? storedMin : '1.0';
  const latestVersion = isValidVersion(storedLatest)
    ? (compareVersions(storedLatest, minVersion) < 0 ? minVersion : storedLatest)
    : minVersion;
  return { minVersion, latestVersion };
}

/**
 * The trigger block for `/v1/sync`: null when idle, so the polling payload
 * stays tiny for the overwhelming majority of devices.
 */
export async function loadUpdateTrigger(env: Env): Promise<UpdateTrigger | null> {
  const s = await getSettings(env.DB, [...TRIGGER_KEYS, 'latest_app_version', 'min_app_version']);
  if (clampTriggerMode(s.update_trigger_mode) === 'off') return null;
  return readTrigger(s, saneVersions(s.min_app_version, s.latest_app_version).latestVersion);
}

/**
 * Read + sanitize the update settings. A malformed stored value falls back to
 * something safe instead of breaking every client check.
 */
export async function loadUpdateConfig(env: Env): Promise<UpdateConfig> {
  const s = await getSettings(env.DB, [
    'min_app_version', 'latest_app_version', 'update_download_url',
    'update_notes', 'update_check_unit', 'update_check_value',
    'update_check_hours', 'update_wifi_only', ...TRIGGER_KEYS,
  ]);

  const { minVersion, latestVersion } = saneVersions(s.min_app_version, s.latest_app_version);

  const unit = clampCheckUnit(s.update_check_unit);
  // Older rows only have update_check_hours; honour it while the unit is still hours.
  const rawValue = s.update_check_value || (unit === 'hours' ? s.update_check_hours : undefined);
  const value = clampCheckValue(rawValue, unit);
  const intervalSeconds = checkIntervalSeconds(unit, value);
  const trigger = readTrigger(s, latestVersion);

  return {
    minVersion,
    latestVersion,
    downloadUrl: (s.update_download_url || '').trim(),
    notes: (s.update_notes || '').trim(),
    checkUnit: unit,
    checkValue: value,
    checkIntervalSeconds: intervalSeconds,
    checkHours: Math.max(1, Math.min(168, Math.ceil(intervalSeconds / 3600))),
    wifiOnly: wifiOnlyEnabled(s.update_wifi_only),
    triggerMode: trigger.mode,
    triggerId: trigger.id,
    triggerMessage: trigger.message,
  };
}
