import { Env } from '../types';
import { errorResponse, jsonResponse, parseBoundedJson } from '../utils';
import { randomId, sha256Base64url } from './crypto';
import { requireAccountAuth, requireAccountReadAuth } from './auth';
import {
  PreparedMutation,
  SyncActor,
  SyncEntityType,
  SyncMutationInput,
  SyncOperationInput,
  SyncRecordRow,
} from './types';
import {
  canonicalJson,
  ENTITY_SCHEMAS,
  isEntityType,
  referencedRecords,
  STRICT_FIELDS,
  validateEntityId,
  validatePayload,
} from './schema';

const MAX_OPERATIONS = 50;
const MAX_MUTATIONS = 200;
const MAX_MUTATIONS_PER_OPERATION = 50;
const MAX_PULL_GROUPS = 100;
const OPERATION_TTL_MS = 30 * 24 * 60 * 60 * 1000;

interface PushInput {
  account_epoch: number;
  device_epoch: number;
  client_schema_version: number;
  operations: SyncOperationInput[];
}

interface StoredOperation {
  request_hash: string;
  response_json: string;
}

function sameJson(a: unknown, b: unknown): boolean {
  return canonicalJson(a) === canonicalJson(b);
}

function operationShapeValid(operation: SyncOperationInput): boolean {
  return /^[A-Za-z0-9_-]{16,80}$/.test(operation.operation_id || '')
    && Array.isArray(operation.mutations)
    && operation.mutations.length > 0
    && operation.mutations.length <= MAX_MUTATIONS_PER_OPERATION;
}

function mutationShapeError(mutation: SyncMutationInput): string | null {
  if (!mutation || !isEntityType(String(mutation.entity_type || ''))) return 'نوع سجل غير مسموح';
  if (!validateEntityId(String(mutation.entity_id || ''))) return 'معرّف السجل غير صالح';
  if (mutation.action !== 'upsert' && mutation.action !== 'delete') return 'نوع العملية غير صالح';
  if (!Number.isSafeInteger(mutation.base_revision) || mutation.base_revision < 0) return 'مراجعة الأساس غير صالحة';
  if (!Number.isSafeInteger(mutation.schema_version) || mutation.schema_version < 1) return 'إصدار البيانات غير صالح';
  return null;
}

async function currentRecord(
  env: Env,
  accountId: string,
  entityType: SyncEntityType,
  entityId: string,
): Promise<SyncRecordRow | null> {
  return env.DB.prepare(
    'SELECT * FROM sync_records WHERE account_id = ? AND entity_type = ? AND entity_id = ?',
  ).bind(accountId, entityType, entityId).first<SyncRecordRow>();
}

function parsePayload(row: SyncRecordRow | null): Record<string, unknown> | null {
  if (!row?.payload_json) return null;
  try {
    const parsed = JSON.parse(row.payload_json);
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : null;
  } catch {
    return null;
  }
}

function prepareMutation(input: SyncMutationInput, current: SyncRecordRow | null): PreparedMutation | string {
  if (input.action === 'delete') {
    if (!current || current.deleted_at !== null || current.revision !== input.base_revision) return 'REVISION_CONFLICT';
    return {
      input,
      payload: null,
      current,
      expectedRevision: current.revision,
      nextRevision: current.revision + 1,
    };
  }

  const currentPayload = parsePayload(current);
  let payload: Record<string, unknown> | null = null;
  let expectedRevision = input.base_revision;
  const revisionMatches = current ? current.revision === input.base_revision : input.base_revision === 0;

  if (revisionMatches) {
    if (input.payload && typeof input.payload === 'object' && !Array.isArray(input.payload)) {
      payload = input.payload;
    } else if (input.patch && typeof input.patch === 'object' && !Array.isArray(input.patch)) {
      payload = { ...(currentPayload || {}), ...input.patch };
    }
  } else if (
    input.merge_policy === 'safe'
    && current
    && current.deleted_at === null
    && currentPayload
    && input.patch
    && input.base_values
  ) {
    const strict = STRICT_FIELDS[input.entity_type] || new Set<string>();
    const touched = Object.keys(input.patch);
    const safe = touched.every(field =>
      !strict.has(field)
      && Object.prototype.hasOwnProperty.call(input.base_values, field)
      && sameJson(currentPayload[field], input.base_values?.[field]),
    );
    if (safe) {
      payload = { ...currentPayload, ...input.patch };
      expectedRevision = current.revision;
    }
  }

  if (!payload) return 'REVISION_CONFLICT';
  const validation = validatePayload(input.entity_type, payload);
  if (validation) return validation;
  return {
    input,
    payload,
    current,
    expectedRevision,
    nextRevision: expectedRevision + 1,
  };
}

async function referencesAreValid(
  env: Env,
  accountId: string,
  prepared: PreparedMutation[],
): Promise<string | null> {
  const upserts = new Set(prepared.filter(item => item.input.action === 'upsert')
    .map(item => `${item.input.entity_type}:${item.input.entity_id}`));
  const deletes = new Set(prepared.filter(item => item.input.action === 'delete')
    .map(item => `${item.input.entity_type}:${item.input.entity_id}`));

  for (const mutation of prepared) {
    if (!mutation.payload) continue;
    for (const reference of referencedRecords(mutation.input.entity_type, mutation.payload)) {
      const key = `${reference.entityType}:${reference.entityId}`;
      if (upserts.has(key) && !deletes.has(key)) continue;
      const row = await currentRecord(env, accountId, reference.entityType, reference.entityId);
      if (!row || row.deleted_at !== null || deletes.has(key)) {
        return `مرجع غير موجود: ${reference.entityType}/${reference.entityId}`;
      }
    }
  }

  for (const mutation of prepared.filter(item => item.input.action === 'delete')) {
    for (const [childType, schema] of Object.entries(ENTITY_SCHEMAS) as Array<[SyncEntityType, typeof ENTITY_SCHEMAS[SyncEntityType]]>) {
      for (const [field, rule] of Object.entries(schema)) {
        if (rule.reference !== mutation.input.entity_type) continue;
        const candidates = await env.DB.prepare(
          `SELECT entity_id FROM sync_records
           WHERE account_id = ? AND entity_type = ? AND deleted_at IS NULL
           AND json_extract(payload_json, ?) = ? LIMIT 25`,
        ).bind(accountId, childType, `$.${field}`, mutation.input.entity_id).all<{ entity_id: string }>();
        const surviving = candidates.results.find(row => !deletes.has(`${childType}:${row.entity_id}`));
        if (surviving) return `لا يمكن حذف السجل قبل حذف توابعه من ${childType}`;
      }
    }
  }
  return null;
}

async function saveConflict(
  env: Env,
  accountId: string,
  actor: SyncActor,
  operation: SyncOperationInput,
  mutation: SyncMutationInput,
  current: SyncRecordRow | null,
  reason: string,
): Promise<Record<string, unknown>> {
  const now = Date.now();
  const conflictId = randomId();
  const result = {
    operation_id: operation.operation_id,
    status: 'conflict',
    code: 'REVISION_CONFLICT',
    reason,
    conflict_id: conflictId,
    entity_type: mutation.entity_type,
    entity_id: mutation.entity_id,
    current_revision: current?.revision || 0,
    current_json: current?.payload_json ? parsePayload(current) : null,
  };
  await env.DB.batch([
    env.DB.prepare(
      `INSERT INTO sync_conflicts
       (id, account_id, operation_id, entity_type, entity_id, base_revision, current_revision,
        incoming_json, current_json, actor_type, actor_id, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    ).bind(
      conflictId, accountId, operation.operation_id, mutation.entity_type, mutation.entity_id,
      mutation.base_revision, current?.revision || null, canonicalJson(mutation), current?.payload_json || null,
      actor.type, actor.id, now,
    ),
    env.DB.prepare(
      `INSERT OR REPLACE INTO sync_operations
       (account_id, operation_id, device_id, request_hash, status, response_json, created_at, expires_at)
       VALUES (?, ?, ?, ?, 'conflict', ?, ?, ?)`,
    ).bind(
      accountId, operation.operation_id, actor.type === 'device' ? actor.id : null,
      await sha256Base64url(canonicalJson(operation)), JSON.stringify(result), now, now + OPERATION_TTL_MS,
    ),
  ]);
  return result;
}

export async function applySyncOperation(
  env: Env,
  accountId: string,
  actor: SyncActor,
  operation: SyncOperationInput,
): Promise<Record<string, unknown>> {
  const requestHash = await sha256Base64url(canonicalJson(operation));
  const prior = await env.DB.prepare(
    'SELECT request_hash, response_json FROM sync_operations WHERE account_id = ? AND operation_id = ?',
  ).bind(accountId, operation.operation_id).first<StoredOperation>();
  if (prior) {
    if (prior.request_hash !== requestHash) {
      return { operation_id: operation.operation_id, status: 'rejected', code: 'OPERATION_ID_REUSED' };
    }
    return JSON.parse(prior.response_json) as Record<string, unknown>;
  }

  const seen = new Set<string>();
  const prepared: PreparedMutation[] = [];
  for (const mutation of operation.mutations) {
    const shapeError = mutationShapeError(mutation);
    if (shapeError) return { operation_id: operation.operation_id, status: 'rejected', code: 'VALIDATION_FAILED', error: shapeError };
    const key = `${mutation.entity_type}:${mutation.entity_id}`;
    if (seen.has(key)) return { operation_id: operation.operation_id, status: 'rejected', code: 'VALIDATION_FAILED', error: 'السجل مكرر داخل العملية' };
    seen.add(key);
    const current = await currentRecord(env, accountId, mutation.entity_type, mutation.entity_id);
    const result = prepareMutation(mutation, current);
    if (typeof result === 'string') {
      if (result === 'REVISION_CONFLICT') return saveConflict(env, accountId, actor, operation, mutation, current, result);
      return { operation_id: operation.operation_id, status: 'rejected', code: 'VALIDATION_FAILED', error: result };
    }
    prepared.push(result);
  }

  const referenceError = await referencesAreValid(env, accountId, prepared);
  if (referenceError) {
    return { operation_id: operation.operation_id, status: 'rejected', code: 'REFERENCE_CONFLICT', error: referenceError };
  }

  const now = Date.now();
  const result = {
    operation_id: operation.operation_id,
    status: 'accepted',
    records: prepared.map(item => ({
      entity_type: item.input.entity_type,
      entity_id: item.input.entity_id,
      revision: item.nextRevision,
    })),
  };
  const statements: D1PreparedStatement[] = [];

  prepared.forEach((item, index) => {
    const assertionId = `${accountId}:${operation.operation_id}:${index}`;
    if (item.expectedRevision === 0) {
      statements.push(env.DB.prepare(
        `INSERT INTO sync_assertions(id, ok)
         SELECT ?, CASE WHEN NOT EXISTS(
           SELECT 1 FROM sync_records WHERE account_id = ? AND entity_type = ? AND entity_id = ?
         ) THEN 1 ELSE 0 END`,
      ).bind(assertionId, accountId, item.input.entity_type, item.input.entity_id));
    } else {
      statements.push(env.DB.prepare(
        `INSERT INTO sync_assertions(id, ok)
         SELECT ?, CASE WHEN EXISTS(
           SELECT 1 FROM sync_records WHERE account_id = ? AND entity_type = ? AND entity_id = ? AND revision = ?
         ) THEN 1 ELSE 0 END`,
      ).bind(assertionId, accountId, item.input.entity_type, item.input.entity_id, item.expectedRevision));
    }

    const payloadJson = item.payload ? canonicalJson(item.payload) : null;
    const deletedAt = item.input.action === 'delete' ? now : null;
    statements.push(env.DB.prepare(
      `INSERT INTO sync_records
       (account_id, entity_type, entity_id, revision, schema_version, payload_json, deleted_at,
        updated_at, updated_by_type, updated_by_id, operation_id)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
       ON CONFLICT(account_id, entity_type, entity_id) DO UPDATE SET
         revision = excluded.revision,
         schema_version = excluded.schema_version,
         payload_json = excluded.payload_json,
         deleted_at = excluded.deleted_at,
         updated_at = excluded.updated_at,
         updated_by_type = excluded.updated_by_type,
         updated_by_id = excluded.updated_by_id,
         operation_id = excluded.operation_id`,
    ).bind(
      accountId, item.input.entity_type, item.input.entity_id, item.nextRevision,
      item.input.schema_version, payloadJson, deletedAt, now, actor.type, actor.id, operation.operation_id,
    ));
    statements.push(env.DB.prepare(
      `INSERT INTO sync_changes
       (account_id, operation_id, group_index, group_size, entity_type, entity_id, revision,
        schema_version, action, payload_json, actor_type, actor_id, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    ).bind(
      accountId, operation.operation_id, index, prepared.length, item.input.entity_type,
      item.input.entity_id, item.nextRevision, item.input.schema_version, item.input.action,
      payloadJson, actor.type, actor.id, now,
    ));
  });

  statements.push(env.DB.prepare(
    `INSERT INTO sync_operations
     (account_id, operation_id, device_id, request_hash, status, response_json, created_at, expires_at)
     VALUES (?, ?, ?, ?, 'accepted', ?, ?, ?)`,
  ).bind(
    accountId, operation.operation_id, actor.type === 'device' ? actor.id : null,
    requestHash, JSON.stringify(result), now, now + OPERATION_TTL_MS,
  ));
  statements.push(env.DB.prepare(
    `UPDATE accounts SET latest_change_id = COALESCE(
       (SELECT MAX(change_id) FROM sync_changes WHERE account_id = ?), latest_change_id
     ), updated_at = ? WHERE id = ?`,
  ).bind(accountId, now, accountId));
  if (actor.type === 'device') {
    statements.push(env.DB.prepare(
      'UPDATE account_devices SET last_sync_at = ?, last_seen_at = ?, updated_at = ? WHERE id = ?',
    ).bind(now, now, now, actor.id));
  }
  for (let i = 0; i < prepared.length; i++) {
    statements.push(env.DB.prepare('DELETE FROM sync_assertions WHERE id = ?')
      .bind(`${accountId}:${operation.operation_id}:${i}`));
  }

  try {
    await env.DB.batch(statements);
    return result;
  } catch {
    const mutation = operation.mutations[0];
    const current = await currentRecord(env, accountId, mutation.entity_type, mutation.entity_id);
    return saveConflict(env, accountId, actor, operation, mutation, current, 'CONCURRENT_WRITE');
  }
}

export async function handleSyncHead(request: Request, env: Env): Promise<Response> {
  const auth = await requireAccountReadAuth(request, env);
  if (auth instanceof Response) return auth;
  const cursor = auth.account.latest_change_id;
  const etag = `"${cursor}:${auth.account.account_epoch}:${auth.device.device_epoch}"`;
  if (request.headers.get('If-None-Match') === etag) {
    return new Response(null, { status: 304, headers: { ETag: etag, 'Cache-Control': 'no-store' } });
  }
  const response = jsonResponse({
    ok: true,
    cursor,
    account_epoch: auth.account.account_epoch,
    device_epoch: auth.device.device_epoch,
    data_schema_version: auth.account.data_schema_version,
  });
  response.headers.set('ETag', etag);
  return response;
}

export async function handleSyncPull(request: Request, env: Env): Promise<Response> {
  const auth = await requireAccountReadAuth(request, env);
  if (auth instanceof Response) return auth;
  const url = new URL(request.url);
  const cursor = Number(url.searchParams.get('cursor') || '0');
  const limit = Math.min(MAX_PULL_GROUPS, Math.max(1, Number(url.searchParams.get('limit') || '50')));
  if (!Number.isSafeInteger(cursor) || cursor < 0) return errorResponse('مؤشر غير صالح', 400, 'VALIDATION_FAILED');

  const operationRows = await env.DB.prepare(
    `SELECT operation_id, MIN(change_id) AS first_id
     FROM sync_changes WHERE account_id = ? AND change_id > ?
     GROUP BY operation_id ORDER BY first_id ASC LIMIT ?`,
  ).bind(auth.account.id, cursor, limit + 1).all<{ operation_id: string; first_id: number }>();
  const selected = operationRows.results.slice(0, limit);
  if (!selected.length) return jsonResponse({ ok: true, from_cursor: cursor, next_cursor: cursor, has_more: false, groups: [] });

  const placeholders = selected.map(() => '?').join(',');
  const changes = await env.DB.prepare(
    `SELECT change_id, operation_id, group_index, group_size, entity_type, entity_id, revision,
            schema_version, action, payload_json, actor_type, actor_id, created_at
     FROM sync_changes WHERE account_id = ? AND operation_id IN (${placeholders})
     ORDER BY change_id ASC`,
  ).bind(auth.account.id, ...selected.map(row => row.operation_id)).all<{
    change_id: number; operation_id: string; group_index: number; group_size: number;
    entity_type: string; entity_id: string; revision: number; schema_version: number;
    action: string; payload_json: string | null; actor_type: string; actor_id: string; created_at: number;
  }>();

  const grouped = new Map<string, Array<Record<string, unknown>>>();
  let nextCursor = cursor;
  for (const row of changes.results) {
    nextCursor = Math.max(nextCursor, row.change_id);
    const list = grouped.get(row.operation_id) || [];
    list.push({
      change_id: row.change_id,
      entity_type: row.entity_type,
      entity_id: row.entity_id,
      revision: row.revision,
      schema_version: row.schema_version,
      action: row.action,
      payload: row.payload_json ? JSON.parse(row.payload_json) : null,
      actor_type: row.actor_type,
      created_at: row.created_at,
    });
    grouped.set(row.operation_id, list);
  }

  return jsonResponse({
    ok: true,
    from_cursor: cursor,
    next_cursor: nextCursor,
    has_more: operationRows.results.length > limit,
    groups: selected.map(row => ({ operation_id: row.operation_id, changes: grouped.get(row.operation_id) || [] })),
  });
}

export async function handleSyncPush(request: Request, env: Env): Promise<Response> {
  const auth = await requireAccountAuth(request, env);
  if (auth instanceof Response) return auth;
  const body = await parseBoundedJson<PushInput>(request);
  if (!body || !Array.isArray(body.operations)) return errorResponse('طلب مزامنة غير صالح', 400, 'VALIDATION_FAILED');
  if (body.account_epoch !== auth.account.account_epoch) return errorResponse('تغيّر جيل الحساب', 409, 'ACCOUNT_EPOCH_CHANGED');
  if (body.device_epoch !== auth.device.device_epoch) return errorResponse('تغيّر جيل الجهاز', 409, 'DEVICE_EPOCH_CHANGED');
  // A missing field compares as undefined < n === false, which would skip the upgrade gate.
  const clientSchema = Number(body.client_schema_version);
  if (!Number.isFinite(clientSchema)) return errorResponse('طلب مزامنة غير صالح', 400, 'VALIDATION_FAILED');
  if (clientSchema < auth.account.data_schema_version) {
    return errorResponse('يجب تحديث التطبيق قبل المزامنة', 426, 'SCHEMA_UPGRADE_REQUIRED');
  }
  if (body.operations.length > MAX_OPERATIONS) return errorResponse('عدد العمليات أكبر من المسموح', 413, 'VALIDATION_FAILED');
  const total = body.operations.reduce((sum, operation) => sum + (operation.mutations?.length || 0), 0);
  if (total > MAX_MUTATIONS) return errorResponse('عدد التغييرات أكبر من المسموح', 413, 'VALIDATION_FAILED');
  const bad = body.operations.find(operation => !operationShapeValid(operation));
  if (bad) return errorResponse('بنية العملية غير صالحة', 400, 'VALIDATION_FAILED');

  const results: Array<Record<string, unknown>> = [];
  for (const operation of body.operations) {
    results.push(await applySyncOperation(
      env,
      auth.account.id,
      { type: 'device', id: auth.device.id },
      operation,
    ));
  }
  const head = await env.DB.prepare('SELECT latest_change_id FROM accounts WHERE id = ?')
    .bind(auth.account.id).first<{ latest_change_id: number }>();
  return jsonResponse({ ok: true, cursor: head?.latest_change_id || 0, results });
}

export async function handleSyncSnapshot(request: Request, env: Env): Promise<Response> {
  const auth = await requireAccountReadAuth(request, env);
  if (auth instanceof Response) return auth;
  const url = new URL(request.url);
  const afterType = url.searchParams.get('after_type') || '';
  const afterId = url.searchParams.get('after_id') || '';
  const limit = Math.min(250, Math.max(1, Number(url.searchParams.get('limit') || '100')));
  const rows = await env.DB.prepare(
    `SELECT entity_type, entity_id, revision, schema_version, payload_json, updated_at
     FROM sync_records
     WHERE account_id = ? AND deleted_at IS NULL
       AND (entity_type > ? OR (entity_type = ? AND entity_id > ?))
     ORDER BY entity_type ASC, entity_id ASC LIMIT ?`,
  ).bind(auth.account.id, afterType, afterType, afterId, limit + 1).all<{
    entity_type: string; entity_id: string; revision: number; schema_version: number;
    payload_json: string; updated_at: number;
  }>();
  const selected = rows.results.slice(0, limit);
  const last = selected[selected.length - 1];
  return jsonResponse({
    ok: true,
    cursor: auth.account.latest_change_id,
    has_more: rows.results.length > limit,
    next_after_type: last?.entity_type || afterType,
    next_after_id: last?.entity_id || afterId,
    records: selected.map(row => ({
      entity_type: row.entity_type,
      entity_id: row.entity_id,
      revision: row.revision,
      schema_version: row.schema_version,
      payload: JSON.parse(row.payload_json),
      updated_at: row.updated_at,
    })),
  });
}
