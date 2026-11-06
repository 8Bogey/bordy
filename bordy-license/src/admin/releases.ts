import { Env } from '../types';
import { getSettings, setSetting, logEvent } from '../db';
import { canonicalVersion, isValidVersion } from '../version';
import { errorResponse, jsonResponse } from '../utils';
import {
  UPDATE_CHECK_DEFAULT_UNIT, UPDATE_TRIGGER_MODES, UpdateTriggerMode,
  checkIntervalSeconds, clampCheckUnit, clampCheckValue, clampTriggerId,
  clampTriggerMode, triggerMessageText, wifiOnlyEnabled,
} from '../update-config';

const KEY_PREFIX = 'releases/';
/** Workers reject bodies over 100 MB; enforce the same ceiling explicitly. */
const MAX_APK_BYTES = 100 * 1024 * 1024;
/** GitHub asset uploads are buffered in worker memory — keep a safer ceiling. */
const MAX_GITHUB_APK_BYTES = 80 * 1024 * 1024;
const DEFAULT_GITHUB_REPO = '8Bogey/bordy-releases';

interface ReleaseInfo {
  key: string;
  name: string;
  size: number;
  uploaded: string;
}

type StorageMode = 'r2' | 'github' | 'none';

const MODE_MESSAGE: Record<StorageMode, string> = {
  r2: 'التخزين: Cloudflare R2 ✓',
  github: 'التخزين: GitHub Releases ✓ (مجاني بلا بطاقة)',
  none: 'لا يوجد تخزين ملفات مهيأ بعد — أضف سر GITHUB_TOKEN من الطرفية، أو الصق رابط تنزيل يدوي في الحقل أدناه.',
};

function r2Available(env: Env): env is Env & { RELEASES: R2Bucket } {
  return typeof env.RELEASES !== 'undefined';
}

function githubRepo(env: Env): string {
  return (env.GITHUB_REPO || DEFAULT_GITHUB_REPO).trim();
}

function githubAvailable(env: Env): boolean {
  return Boolean(env.GITHUB_TOKEN);
}

function storageMode(env: Env): StorageMode {
  if (r2Available(env)) return 'r2';
  if (githubAvailable(env)) return 'github';
  return 'none';
}

function downloadUrlFor(request: Request, key: string): string {
  return `${new URL(request.url).origin}/dl/${key}`;
}

// ─── GitHub Releases backend ────────────────────────────────────────────────

function ghHeaders(env: Env, extra: Record<string, string> = {}): Record<string, string> {
  return {
    'Authorization': `Bearer ${env.GITHUB_TOKEN}`,
    'Accept': 'application/vnd.github+json',
    'X-GitHub-Api-Version': '2022-11-28',
    'User-Agent': 'bordy-license-worker',
    ...extra,
  };
}

async function ghJson(env: Env, path: string, init: RequestInit = {}): Promise<{ status: number; json: any }> {
  const res = await fetch(`https://api.github.com${path}`, {
    ...init,
    headers: ghHeaders(env, (init.headers as Record<string, string>) || {}),
  });
  let json: any = null;
  try { json = await res.json(); } catch { json = null; }
  return { status: res.status, json };
}

async function ghListReleases(env: Env): Promise<ReleaseInfo[]> {
  const { status, json } = await ghJson(env, `/repos/${githubRepo(env)}/releases?per_page=30`);
  if (status !== 200 || !Array.isArray(json)) return [];
  const out: ReleaseInfo[] = [];
  for (const rel of json) {
    for (const asset of rel.assets || []) {
      out.push({
        key: `gh:${rel.id}`,
        name: asset.name,
        size: asset.size,
        uploaded: asset.created_at || rel.published_at || '',
      });
    }
  }
  return out;
}

async function ghUpload(env: Env, version: string, bytes: Uint8Array): Promise<{ releaseId: number; url: string } | { error: string }> {
  if (bytes.length > MAX_GITHUB_APK_BYTES) {
    return { error: `حجم الملف يتجاوز ${MAX_GITHUB_APK_BYTES / 1024 / 1024}MB (حد GitHub)` };
  }
  const repo = githubRepo(env);
  const tag = `v${version}`;
  const assetName = `bordy-${version}.apk`;

  let release = (await ghJson(env, `/repos/${repo}/releases/tags/${tag}`)).json;
  if (!release || !release.id) {
    const created = await ghJson(env, `/repos/${repo}/releases`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ tag_name: tag, name: `بوردي ${version}`, target_commitish: 'main' }),
    });
    if (created.status >= 300 || !created.json?.id) {
      return { error: `تعذر إنشاء إصدار GitHub (${created.status})` };
    }
    release = created.json;
  }

  // Re-uploading the same version replaces the old asset.
  const assets = (await ghJson(env, `/repos/${repo}/releases/${release.id}/assets`)).json;
  const existing = Array.isArray(assets) ? assets.find((a: any) => a.name === assetName) : null;
  if (existing) {
    await ghJson(env, `/repos/${repo}/releases/assets/${existing.id}`, { method: 'DELETE' });
  }

  const res = await fetch(
    `https://uploads.github.com/repos/${repo}/releases/${release.id}/assets?name=${encodeURIComponent(assetName)}`,
    {
      method: 'POST',
      headers: ghHeaders(env, { 'Content-Type': 'application/vnd.android.package-archive' }),
      body: bytes,
    },
  );
  if (!res.ok) {
    return { error: `فشل رفع الملف إلى GitHub (${res.status})` };
  }
  const asset: any = await res.json();
  return { releaseId: release.id, url: asset.browser_download_url };
}

// ─── API handlers ───────────────────────────────────────────────────────────

/** Everything the admin UI needs: update settings + available release files. */
export async function apiUpdateStatus(request: Request, env: Env): Promise<Response> {
  const settings = await getSettings(env.DB, [
    'min_app_version', 'latest_app_version', 'update_download_url',
    'update_notes', 'update_check_unit', 'update_check_value',
    'update_check_hours', 'update_wifi_only', 'update_file',
    'update_trigger_mode', 'update_trigger_id', 'update_trigger_message',
  ]);

  const checkUnit = clampCheckUnit(settings.update_check_unit);
  const checkValue = clampCheckValue(
    settings.update_check_value || (checkUnit === UPDATE_CHECK_DEFAULT_UNIT ? settings.update_check_hours : undefined),
    checkUnit,
  );

  const mode = storageMode(env);
  let releases: ReleaseInfo[] = [];
  if (mode === 'r2') {
    const listed = await env.RELEASES!.list({ prefix: KEY_PREFIX });
    releases = (listed.objects || []).map(o => ({
      key: o.key,
      name: o.key.slice(KEY_PREFIX.length),
      size: o.size,
      uploaded: o.uploaded.toISOString(),
    }));
  } else if (mode === 'github') {
    releases = await ghListReleases(env);
  }

  return jsonResponse({
    ok: true,
    storage: mode,
    storage_message: MODE_MESSAGE[mode],
    releases,
    max_apk_mb: (mode === 'github' ? MAX_GITHUB_APK_BYTES : MAX_APK_BYTES) / 1024 / 1024,
    settings: {
      min_app_version: settings.min_app_version || '1.0',
      latest_app_version: settings.latest_app_version || settings.min_app_version || '1.0',
      update_download_url: settings.update_download_url || '',
      update_notes: settings.update_notes || '',
      update_check_unit: checkUnit,
      update_check_value: String(checkValue),
      update_check_hours: String(Math.max(1, Math.min(168, Math.ceil(checkIntervalSeconds(checkUnit, checkValue) / 3600)))),
      update_wifi_only: settings.update_wifi_only ?? '1',
      update_file: settings.update_file || '',
      update_trigger_mode: clampTriggerMode(settings.update_trigger_mode),
      update_trigger_id: String(clampTriggerId(settings.update_trigger_id)),
      update_trigger_message: triggerMessageText(settings.update_trigger_message),
    },
  });
}

/** Multipart upload: file field "apk" + "version". Stores and points the config at it. */
export async function apiUploadRelease(request: Request, env: Env): Promise<Response> {
  const mode = storageMode(env);
  if (mode === 'none') {
    return errorResponse(MODE_MESSAGE.none);
  }

  let form: FormData;
  try {
    form = await request.formData();
  } catch {
    return errorResponse('طلب الرفع غير صالح');
  }

  const versionRaw = String(form.get('version') || '').trim();
  const entry = form.get('apk');
  if (!entry || typeof entry === 'string') {
    return errorResponse('اختر ملف APK أولاً');
  }
  const file = entry as unknown as File;
  if (file.size === 0) {
    return errorResponse('اختر ملف APK أولاً');
  }
  if (file.size > MAX_APK_BYTES) {
    return errorResponse(`حجم الملف ${Math.round(file.size / 1024 / 1024)}MB — الحد الأقصى ${MAX_APK_BYTES / 1024 / 1024}MB`);
  }
  if (!isValidVersion(versionRaw)) {
    return errorResponse('رقم الإصدار غير صالح — استخدم صيغة مثل 1.0 أو 1.2');
  }
  const version = canonicalVersion(versionRaw);

  if (!file.name.toLowerCase().endsWith('.apk')) {
    return errorResponse('الملف يجب أن يكون بصيغة APK');
  }

  let key: string;
  let url: string;

  if (mode === 'r2') {
    key = `${KEY_PREFIX}bordy-${version}.apk`;
    await env.RELEASES!.put(key, file.stream(), {
      httpMetadata: { contentType: 'application/vnd.android.package-archive' },
    });
    url = downloadUrlFor(request, key);
  } else {
    const bytes = new Uint8Array(await file.arrayBuffer());
    const uploaded = await ghUpload(env, version, bytes);
    if ('error' in uploaded) {
      return errorResponse(uploaded.error);
    }
    key = `gh:${uploaded.releaseId}`;
    url = uploaded.url;
  }

  await setSetting(env.DB, 'update_file', key);
  await setSetting(env.DB, 'latest_app_version', version);
  await setSetting(env.DB, 'update_download_url', url);
  await logEvent(env.DB, null, 'RELEASE_UPLOAD', `${version} (${Math.round(file.size / 1024 / 1024)}MB, ${mode})`, 'admin');

  return jsonResponse({ ok: true, key, version, download_url: url });
}

/** Remove a release; clear the download settings when the live file is the one deleted. */
export async function apiDeleteRelease(request: Request, env: Env): Promise<Response> {
  let body: { key?: string };
  try {
    body = await request.json();
  } catch {
    return errorResponse('طلب غير صالح');
  }
  const key = String(body.key || '');

  if (key.startsWith('gh:')) {
    if (!githubAvailable(env)) return errorResponse(MODE_MESSAGE.none);
    const id = key.slice(3);
    if (!/^\d+$/.test(id)) return errorResponse('مفتاح الملف غير صالح');
    const res = await ghJson(env, `/repos/${githubRepo(env)}/releases/${id}`, { method: 'DELETE' });
    if (res.status >= 300 && res.status !== 404) {
      return errorResponse(`تعذر حذف الإصدار (${res.status})`);
    }
  } else {
    if (!r2Available(env)) return errorResponse(MODE_MESSAGE.none);
    if (!key.startsWith(KEY_PREFIX) || key.includes('..')) {
      return errorResponse('مفتاح الملف غير صالح');
    }
    await env.RELEASES.delete(key);
  }

  const settings = await getSettings(env.DB, ['update_file', 'update_download_url']);
  if (settings.update_file === key) {
    await setSetting(env.DB, 'update_file', '');
    await setSetting(env.DB, 'update_download_url', '');
  }
  await logEvent(env.DB, null, 'RELEASE_DELETE', key, 'admin');

  return jsonResponse({ ok: true });
}

/** Owner edits the update settings without uploading a file. */
export async function apiUpdateReleaseSettings(request: Request, env: Env): Promise<Response> {
  let body: Record<string, unknown>;
  try {
    body = await request.json();
  } catch {
    return errorResponse('طلب غير صالح');
  }

  if (body.min_app_version !== undefined) {
    const value = canonicalVersion(String(body.min_app_version));
    if (!isValidVersion(value)) {
      return errorResponse('أقل إصدار غير صالح — استخدم صيغة مثل 1.0 أو 1.2');
    }
    await setSetting(env.DB, 'min_app_version', value);
  }
  if (body.latest_app_version !== undefined) {
    const value = canonicalVersion(String(body.latest_app_version));
    if (!isValidVersion(value)) {
      return errorResponse('أحدث إصدار غير صالح — استخدم صيغة مثل 1.0 أو 1.2');
    }
    await setSetting(env.DB, 'latest_app_version', value);
  }
  if (body.update_check_unit !== undefined || body.update_check_value !== undefined || body.update_check_hours !== undefined) {
    const current = await getSettings(env.DB, ['update_check_unit', 'update_check_value', 'update_check_hours']);
    let unit = body.update_check_unit !== undefined
      ? clampCheckUnit(String(body.update_check_unit))
      : clampCheckUnit(current.update_check_unit);
    let rawValue: unknown = body.update_check_value;
    if (rawValue === undefined && body.update_check_hours !== undefined) {
      // Legacy callers only send whole hours.
      unit = 'hours';
      rawValue = body.update_check_hours;
    }
    if (rawValue === undefined) {
      rawValue = current.update_check_value || (unit === 'hours' ? current.update_check_hours : undefined);
    }
    const value = clampCheckValue(String(rawValue ?? ''), unit);
    await setSetting(env.DB, 'update_check_unit', unit);
    await setSetting(env.DB, 'update_check_value', String(value));
    // Keep the legacy key in sync so an older app/admin build still reads a sane value.
    await setSetting(env.DB, 'update_check_hours',
      String(Math.max(1, Math.min(168, Math.ceil(checkIntervalSeconds(unit, value) / 3600)))));
  }
  if (body.update_wifi_only !== undefined) {
    await setSetting(env.DB, 'update_wifi_only', wifiOnlyEnabled(String(body.update_wifi_only)) ? '1' : '0');
  }
  if (body.update_notes !== undefined) {
    await setSetting(env.DB, 'update_notes', String(body.update_notes).slice(0, 1000));
  }
  if (body.update_download_url !== undefined) {
    const url = String(body.update_download_url).trim();
    if (url && !/^https:\/\//.test(url)) {
      return errorResponse('رابط التنزيل يجب أن يبدأ بـ https://');
    }
    await setSetting(env.DB, 'update_download_url', url);
  }
  if (body.trigger !== undefined && body.trigger !== null) {
    const t = body.trigger as Record<string, unknown>;
    const mode = clampTriggerMode(String(t.mode ?? 'off')) as UpdateTriggerMode;
    if (mode === 'off') {
      await setSetting(env.DB, 'update_trigger_mode', 'off');
      await logEvent(env.DB, null, 'UPDATE_TRIGGER', 'off', 'admin');
    } else {
      // A fresh stamp makes devices that already dismissed an optional prompt show it again.
      const current = await getSettings(env.DB, ['update_trigger_id']);
      const firedAt = Math.max(Date.now(), clampTriggerId(current.update_trigger_id) + 1);
      await setSetting(env.DB, 'update_trigger_mode', mode);
      await setSetting(env.DB, 'update_trigger_id', String(firedAt));
      await setSetting(env.DB, 'update_trigger_message', triggerMessageText(t.message));
      await logEvent(env.DB, null, 'UPDATE_TRIGGER', `${mode} #${firedAt}`, 'admin');
    }
  }

  await logEvent(env.DB, null, 'RELEASE_SETTINGS', 'Update settings changed', 'admin');
  return jsonResponse({ ok: true });
}

/** Public streaming download of an R2 release object (free egress). */
export async function handleDownloadRelease(key: string, env: Env): Promise<Response> {
  if (!r2Available(env) || !key.startsWith(KEY_PREFIX) || key.includes('..')) {
    return errorResponse('Not found', 404);
  }
  const object = await env.RELEASES.get(key);
  if (!object) {
    return errorResponse('Not found', 404);
  }
  const headers = new Headers();
  object.writeHttpMetadata(headers);
  headers.set('Content-Type', 'application/vnd.android.package-archive');
  headers.set('Content-Disposition', `attachment; filename="${key.slice(KEY_PREFIX.length)}"`);
  headers.set('Cache-Control', 'public, max-age=3600');
  return new Response(object.body, { headers });
}
