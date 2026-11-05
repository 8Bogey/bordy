import { Env } from '../types';
import { getActivePlans, getAutoApplyCode, getSettings } from '../db';
import { parsePaymentMethods, PaymentMethodInfo } from '../utils';

export const SYNC_INTERVAL_DEFAULT = 60;
export const SYNC_INTERVAL_MIN = 15;
export const SYNC_INTERVAL_MAX = 3600;

/** While the app is open it polls this often, so panel changes show up in seconds. */
export const FOREGROUND_SYNC_DEFAULT = 10;
export const FOREGROUND_SYNC_MIN = 5;
export const FOREGROUND_SYNC_MAX = 120;

export const TRIAL_MIN_SECONDS = 30;
export const TRIAL_MAX_SECONDS = 3650 * 86400;
export const TRIAL_DEFAULT_SECONDS = 7 * 86400;

/** How early the app reminds the user, in the owner's chosen unit. */
export const EXPIRY_NOTICE_UNITS = ['days', 'hours', 'minutes'] as const;
export type ExpiryNoticeUnit = typeof EXPIRY_NOTICE_UNITS[number];
export const EXPIRY_NOTICE_DEFAULT_UNIT: ExpiryNoticeUnit = 'days';
export const EXPIRY_NOTICE_DEFAULT_VALUE = 3;

export function clampExpiryNoticeUnit(raw: string | undefined | null): ExpiryNoticeUnit {
  return EXPIRY_NOTICE_UNITS.includes(raw as ExpiryNoticeUnit)
    ? raw as ExpiryNoticeUnit
    : EXPIRY_NOTICE_DEFAULT_UNIT;
}

/** 365 days / 8760 hours / 525600 minutes — the same maximum expressed in each unit. */
export function clampExpiryNoticeValue(raw: string | undefined | null, unit: ExpiryNoticeUnit): number {
  const parsed = parseInt(raw || '', 10);
  const value = Number.isFinite(parsed) && parsed > 0 ? parsed : EXPIRY_NOTICE_DEFAULT_VALUE;
  const max = unit === 'days' ? 365 : unit === 'hours' ? 8760 : 525600;
  return Math.min(max, Math.max(1, value));
}

export function expiryNoticeEnabled(raw: string | undefined | null): boolean {
  // Absent key means "on": the reminder exists unless the owner turns it off.
  return raw !== '0' && raw !== 'off';
}

/** Clamp whatever is stored in settings to a sane polling period. */
export function clampSyncInterval(raw: string | undefined | null): number {
  const value = parseInt(raw || '', 10);
  if (!Number.isFinite(value)) return SYNC_INTERVAL_DEFAULT;
  return Math.min(SYNC_INTERVAL_MAX, Math.max(SYNC_INTERVAL_MIN, value));
}

export function clampForegroundSync(raw: string | undefined | null): number {
  const value = parseInt(raw || '', 10);
  if (!Number.isFinite(value)) return FOREGROUND_SYNC_DEFAULT;
  return Math.min(FOREGROUND_SYNC_MAX, Math.max(FOREGROUND_SYNC_MIN, value));
}

/**
 * Trial length is stored in seconds; `trial_days` is the legacy fallback.
 * An explicit "0" means the owner disabled the free trial, so new devices start locked —
 * it is not treated as "missing", otherwise 0 could never be saved.
 */
export function clampTrialSeconds(raw: string | undefined | null, legacyDays?: string | null): number {
  const trimmed = String(raw ?? '').trim();
  if (/^\d+$/.test(trimmed)) {
    const seconds = parseInt(trimmed, 10);
    if (seconds === 0) return 0;
    return Math.min(TRIAL_MAX_SECONDS, Math.max(TRIAL_MIN_SECONDS, seconds));
  }
  const days = parseInt(legacyDays || '', 10);
  if (Number.isFinite(days) && days > 0) {
    return Math.min(TRIAL_MAX_SECONDS, Math.max(TRIAL_MIN_SECONDS, days * 86400));
  }
  return TRIAL_DEFAULT_SECONDS;
}

/** FNV-1a — a cheap fingerprint so the app only re-applies config that really changed. */
function fnv1a(input: string): number {
  let hash = 0x811c9dc5;
  for (let i = 0; i < input.length; i++) {
    hash ^= input.charCodeAt(i);
    hash = Math.imul(hash, 0x01000193) >>> 0;
  }
  return hash >>> 0;
}

export interface RemoteConfig {
  plans: { id: string; title: string; days: number; price: number }[];
  autoDiscount: { code: string; kind: string; value: number; plans: string; ends_at: number } | null;
  paymentMethods: PaymentMethodInfo[];
  support: { telegram: string; whatsapp: string };
  syncIntervalSeconds: number;
  foregroundSyncSeconds: number;
  expiryNotice: { enabled: boolean; unit: ExpiryNoticeUnit; value: number };
  version: number;
}

/**
 * Everything the app shows from the server-owned settings: plans, the automatic
 * campaign, wallet numbers, support handles, the sync cadence and the expiry reminder.
 */
export async function loadRemoteConfig(env: Env, now: number): Promise<RemoteConfig> {
  const [plans, autoCode, settings] = await Promise.all([
    getActivePlans(env.DB),
    getAutoApplyCode(env.DB, now),
    getSettings(env.DB, [
      'payment_methods', 'support_telegram', 'support_whatsapp', 'sync_interval_seconds',
      'foreground_sync_seconds', 'expiry_notice_enabled', 'expiry_notice_unit', 'expiry_notice_value',
    ]),
  ]);

  const syncIntervalSeconds = clampSyncInterval(settings.sync_interval_seconds);
  const foregroundSyncSeconds = clampForegroundSync(settings.foreground_sync_seconds);
  const noticeUnit = clampExpiryNoticeUnit(settings.expiry_notice_unit);
  const noticeValue = clampExpiryNoticeValue(settings.expiry_notice_value, noticeUnit);
  const payload = {
    plans: plans.map(p => ({ id: p.id, title: p.title, days: p.days, price: p.price })),
    auto_discount: autoCode ? {
      code: autoCode.code,
      kind: autoCode.kind,
      value: autoCode.value,
      plans: autoCode.plans,
      ends_at: autoCode.ends_at,
    } : null,
    payment_methods: parsePaymentMethods(settings.payment_methods),
    support: {
      telegram: settings.support_telegram || '',
      whatsapp: settings.support_whatsapp || '',
    },
    sync_interval_seconds: syncIntervalSeconds,
    foreground_sync_seconds: foregroundSyncSeconds,
    expiry_notice: {
      enabled: expiryNoticeEnabled(settings.expiry_notice_enabled),
      unit: noticeUnit,
      value: noticeValue,
    },
  };

  return {
    plans: payload.plans,
    autoDiscount: payload.auto_discount,
    paymentMethods: payload.payment_methods,
    support: payload.support,
    syncIntervalSeconds,
    foregroundSyncSeconds,
    expiryNotice: payload.expiry_notice,
    version: fnv1a(JSON.stringify(payload)),
  };
}
