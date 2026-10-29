export type AccountStatus = 'pending' | 'active' | 'locked' | 'disabled';
export type AccountDeviceStatus =
  | 'pending'
  | 'active'
  | 'freeze_pending'
  | 'frozen'
  | 'restoring'
  | 'wipe_pending'
  | 'wiped'
  | 'revoked';

export interface AccountRow {
  id: string;
  username: string;
  username_norm: string;
  password_hash: string;
  password_salt: string;
  password_algorithm: string;
  password_iterations: number;
  password_version: number;
  status: AccountStatus;
  account_epoch: number;
  active_device_id: string | null;
  latest_change_id: number;
  data_schema_version: number;
  created_at: number;
  updated_at: number;
}

export interface AccountDeviceRow {
  id: string;
  account_id: string;
  fingerprint_hash: string;
  install_id_hash: string;
  name: string | null;
  app_version: number | null;
  status: AccountDeviceStatus;
  device_epoch: number;
  last_seen_at: number | null;
  last_sync_at: number | null;
  created_at: number;
  updated_at: number;
}

export interface RefreshSessionRow {
  id: string;
  family_id: string;
  account_id: string;
  device_id: string;
  token_hash: string;
  account_epoch: number;
  device_epoch: number;
  expires_at: number;
  used_at: number | null;
  revoked_at: number | null;
  replaced_by: string | null;
  created_at: number;
}

export interface AccessClaims {
  v: 1;
  aud: 'bordy-account';
  sid: string;
  aid: string;
  did: string;
  ae: number;
  de: number;
  iat: number;
  exp: number;
}

export interface AuthContext {
  account: AccountRow;
  device: AccountDeviceRow;
  claims: AccessClaims;
}

export type SyncEntityType =
  | 'suppliers'
  | 'components'
  | 'tags'
  | 'component_tags'
  | 'component_variants'
  | 'supplier_offers'
  | 'cart_items'
  | 'templates'
  | 'template_items'
  | 'projects'
  | 'project_items'
  | 'project_notes'
  | 'business_settings';

export interface SyncRecordRow {
  account_id: string;
  entity_type: SyncEntityType;
  entity_id: string;
  revision: number;
  schema_version: number;
  payload_json: string | null;
  deleted_at: number | null;
  updated_at: number;
  updated_by_type: 'device' | 'admin' | 'system';
  updated_by_id: string;
  operation_id: string;
}

export interface SyncMutationInput {
  entity_type: SyncEntityType;
  entity_id: string;
  action: 'upsert' | 'delete';
  base_revision: number;
  schema_version: number;
  payload?: Record<string, unknown>;
  patch?: Record<string, unknown>;
  base_values?: Record<string, unknown>;
  merge_policy?: 'safe' | 'strict';
}

export interface SyncOperationInput {
  operation_id: string;
  mutations: SyncMutationInput[];
}

export type SyncActorType = 'device' | 'admin' | 'system';

export interface SyncActor {
  type: SyncActorType;
  id: string;
}

export interface PreparedMutation {
  input: SyncMutationInput;
  payload: Record<string, unknown> | null;
  current: SyncRecordRow | null;
  expectedRevision: number;
  nextRevision: number;
}
