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

/** Trial length is stored in seconds; `trial_days` is the legacy fallback. */
export function clampTrialSeconds(raw: string | undefined | null, legacyDays?: string | null): number {
  const seconds = parseInt(raw || '', 10);
  if (Number.isFinite(seconds) && seconds > 0) {
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
  version: number;
}

/**
 * Everything the app shows from the server-owned settings: plans, the automatic
 * campaign, wallet numbers, support handles and the sync cadence.
 */
export async function loadRemoteConfig(env: Env, now: number): Promise<RemoteConfig> {
  const [plans, autoCode, settings] = await Promise.all([
    getActivePlans(env.DB),
    getAutoApplyCode(env.DB, now),
    getSettings(env.DB, ['payment_methods', 'support_telegram', 'support_whatsapp', 'sync_interval_seconds', 'foreground_sync_seconds']),
  ]);

  const syncIntervalSeconds = clampSyncInterval(settings.sync_interval_seconds);
  const foregroundSyncSeconds = clampForegroundSync(settings.foreground_sync_seconds);
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
  };

  return {
    plans: payload.plans,
    autoDiscount: payload.auto_discount,
    paymentMethods: payload.payment_methods,
    support: payload.support,
    syncIntervalSeconds,
    foregroundSyncSeconds,
    version: fnv1a(JSON.stringify(payload)),
  };
}
