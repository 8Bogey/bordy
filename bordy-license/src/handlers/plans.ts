import { Env } from '../types';
import { jsonResponse } from '../utils';
import { loadRemoteConfig } from './config';

export async function handleGetPlans(request: Request, env: Env): Promise<Response> {
  const config = await loadRemoteConfig(env, Date.now());

  return jsonResponse({
    ok: true,
    plans: config.plans,
    auto_discount: config.autoDiscount,
    payment_methods: config.paymentMethods,
    support: config.support,
    config_version: config.version,
    sync_interval_seconds: config.syncIntervalSeconds,
    foreground_sync_seconds: config.foregroundSyncSeconds,
  });
}
