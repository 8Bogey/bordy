export interface Env {
  DB: D1Database;
  LICENSE_PRIVATE_KEY: string;  // PKCS8 base64
  TG_BOT_TOKEN: string;
  TG_WEBHOOK_SECRET: string;
  OWNER_CHAT_ID: string;
  ADMIN_SECRET: string;
  ADMIN_TOTP_SECRET: string;
  ACCOUNT_TOKEN_SECRET: string;
  PASSWORD_PEPPER: string;
}

export interface TokenPayload {
  v: number;        // always 1
  kid: string;      // "k1" or "k2"
  did: string;      // device fingerprint (32 hex)
  st: 'trial' | 'paid' | 'owner';
  tre: number;      // trial end (epoch ms)
  pu: number;       // paid_until (epoch ms)
  iat: number;      // issued at
  exp: number;      // expires at
  nonce: string;    // from app
  minv: number;     // minimum app version required
  msg?: string;     // optional message
  tos_ver: number;  // TOS version accepted
}

export type OrderStatus = 'PENDING' | 'SUBMITTED' | 'APPROVED' | 'REJECTED' | 'EXPIRED' | 'CANCELLED';
export type CodeKind = 'PERCENT' | 'FIXED' | 'FREE_DAYS' | 'FULL';
export type RedemptionState = 'RESERVED' | 'CONSUMED' | 'RELEASED';

export interface DeviceRow {
  device_id: string;
  first_seen: number;
  last_seen: number | null;
  trial_started_at: number | null;
  trial_ends_at: number | null;
  paid_until: number;
  lifetime: number;
  blocked: number;
  block_reason: string | null;
  name: string | null;
  phone: string | null;
  notes: string | null;
  app_ver: number | null;
  model: string | null;
  sdk: number | null;
  cert_hash: string | null;
  tos_accepted: number;
  tos_accepted_at: number | null;
  transfers_used: number;
}

export interface PlanRow {
  id: string;
  title: string;
  days: number;
  price: number;
  active: number;
  sort: number;
}

export interface OrderRow {
  id: string;
  device_id: string;
  plan_id: string;
  base_price: number;
  discount: number;
  final_price: number;
  code: string | null;
  days_granted: number;
  method: string | null;
  txn_ref: string | null;
  payer_name: string | null;
  payer_phone: string | null;
  status: string;
  reject_reason: string | null;
  provider: string;
  provider_ref: string | null;
  created_at: number;
  submitted_at: number | null;
  decided_at: number | null;
  decided_by: string | null;
  expires_at: number;
}

export interface CodeRow {
  code: string;
  kind: string;
  value: number;
  plans: string;
  starts_at: number;
  ends_at: number;
  max_uses: number | null;
  used_count: number;
  reserved_count: number;
  per_device_limit: number;
  min_price: number;
  auto_apply: number;
  campaign: string | null;
  note: string | null;
  active: number;
  created_at: number;
}

export interface SettingRow {
  k: string;
  v: string;
}

export interface EventRow {
  id: number;
  ts: number;
  device_id: string | null;
  type: string;
  detail: string | null;
  actor: string | null;
}

export interface PaymentMethod {
  name: string;
  account: string;
  note: string;
}
