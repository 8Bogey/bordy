-- All times are UTC epoch milliseconds

CREATE TABLE IF NOT EXISTS devices (
    device_id        TEXT PRIMARY KEY,
    account_id       TEXT,
    first_seen       INTEGER NOT NULL,
    last_seen        INTEGER,
    trial_started_at INTEGER,
    trial_ends_at    INTEGER,
    paid_until       INTEGER NOT NULL DEFAULT 0,
    lifetime         INTEGER NOT NULL DEFAULT 0,
    blocked          INTEGER NOT NULL DEFAULT 0,
    block_reason     TEXT,
    name             TEXT,
    phone            TEXT,
    notes            TEXT,
    app_ver          INTEGER,
    model            TEXT,
    sdk              INTEGER,
    cert_hash        TEXT,
    tos_accepted     INTEGER NOT NULL DEFAULT 0,
    tos_accepted_at  INTEGER,
    transfers_used   INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_devices_phone ON devices(phone);
CREATE INDEX IF NOT EXISTS idx_devices_paid ON devices(paid_until);

CREATE TABLE IF NOT EXISTS plans (
    id     TEXT PRIMARY KEY,
    title  TEXT NOT NULL,
    days   INTEGER NOT NULL,
    price  INTEGER NOT NULL,
    active INTEGER NOT NULL DEFAULT 1,
    sort   INTEGER NOT NULL DEFAULT 0
);

INSERT OR IGNORE INTO plans VALUES ('monthly', 'شهري', 30, 7500, 1, 1);
INSERT OR IGNORE INTO plans VALUES ('quarterly', '3 أشهر', 90, 20000, 1, 2);
INSERT OR IGNORE INTO plans VALUES ('yearly', 'سنوي', 365, 70000, 1, 3);

CREATE TABLE IF NOT EXISTS orders (
    id            TEXT PRIMARY KEY,
    device_id     TEXT NOT NULL,
    account_id    TEXT,
    plan_id       TEXT NOT NULL,
    base_price    INTEGER NOT NULL,
    discount      INTEGER NOT NULL DEFAULT 0,
    final_price   INTEGER NOT NULL,
    code          TEXT,
    days_granted  INTEGER NOT NULL,
    method        TEXT,
    txn_ref       TEXT,
    payer_name    TEXT,
    payer_phone   TEXT,
    status        TEXT NOT NULL DEFAULT 'PENDING',
    reject_reason TEXT,
    provider      TEXT NOT NULL DEFAULT 'manual',
    provider_ref  TEXT,
    created_at    INTEGER NOT NULL,
    submitted_at  INTEGER,
    decided_at    INTEGER,
    decided_by    TEXT,
    expires_at    INTEGER NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_orders_txn ON orders(method, txn_ref) WHERE txn_ref IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_orders_status ON orders(status, created_at);
CREATE INDEX IF NOT EXISTS idx_orders_device ON orders(device_id);

CREATE TABLE IF NOT EXISTS proofs (
    order_id   TEXT PRIMARY KEY,
    mime       TEXT,
    data       BLOB,
    created_at INTEGER
);

CREATE TABLE IF NOT EXISTS codes (
    code             TEXT PRIMARY KEY,
    kind             TEXT NOT NULL,
    value            INTEGER NOT NULL DEFAULT 0,
    plans            TEXT NOT NULL DEFAULT '*',
    starts_at        INTEGER NOT NULL,
    ends_at          INTEGER NOT NULL,
    max_uses         INTEGER,
    used_count       INTEGER NOT NULL DEFAULT 0,
    reserved_count   INTEGER NOT NULL DEFAULT 0,
    per_device_limit INTEGER NOT NULL DEFAULT 1,
    min_price        INTEGER NOT NULL DEFAULT 0,
    auto_apply       INTEGER NOT NULL DEFAULT 0,
    campaign         TEXT,
    note             TEXT,
    active           INTEGER NOT NULL DEFAULT 1,
    created_at       INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS code_redemptions (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    code       TEXT NOT NULL,
    device_id  TEXT NOT NULL,
    order_id   TEXT,
    state      TEXT NOT NULL,
    created_at INTEGER NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_redeem ON code_redemptions(code, device_id) WHERE state IN ('RESERVED','CONSUMED');

CREATE TABLE IF NOT EXISTS events (
    id        INTEGER PRIMARY KEY AUTOINCREMENT,
    ts        INTEGER NOT NULL,
    device_id TEXT,
    type      TEXT NOT NULL,
    detail    TEXT,
    actor     TEXT
);
CREATE INDEX IF NOT EXISTS idx_events_dev ON events(device_id, ts);

CREATE TABLE IF NOT EXISTS rate_limits (
    k            TEXT PRIMARY KEY,
    window_start INTEGER NOT NULL,
    n            INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS settings (
    k TEXT PRIMARY KEY,
    v TEXT NOT NULL
);

INSERT OR IGNORE INTO settings VALUES ('trial_days', '7');
INSERT OR IGNORE INTO settings VALUES ('trial_seconds', '604800');
INSERT OR IGNORE INTO settings VALUES ('foreground_sync_seconds', '10');
INSERT OR IGNORE INTO settings VALUES ('paid_token_ttl_days', '7');
INSERT OR IGNORE INTO settings VALUES ('grace_days', '2');
INSERT OR IGNORE INTO settings VALUES ('refresh_after_hours', '24');
INSERT OR IGNORE INTO settings VALUES ('order_ttl_hours', '48');
INSERT OR IGNORE INTO settings VALUES ('code_attempts_per_hour_device', '10');
INSERT OR IGNORE INTO settings VALUES ('code_attempts_per_hour_ip', '30');
INSERT OR IGNORE INTO settings VALUES ('transfers_per_year', '2');
INSERT OR IGNORE INTO settings VALUES ('transfer_cooldown_h', '24');
INSERT OR IGNORE INTO settings VALUES ('stack_mode', 'best');
INSERT OR IGNORE INTO settings VALUES ('min_app_version', '1');
INSERT OR IGNORE INTO settings VALUES ('maintenance', 'off');
INSERT OR IGNORE INTO settings VALUES ('tos_text', 'شروط الخدمة لتطبيق بوردي (Bordy). البيانات محفوظة على جهازك فقط والاشتراك لجهاز واحد.');
INSERT OR IGNORE INTO settings VALUES ('privacy_text', 'سياسة الخصوصية لتطبيق بوردي (Bordy). نحن نحترم خصوصيتك وبياناتك لا تُشارك مع أي طرف ثالث.');
INSERT OR IGNORE INTO settings VALUES ('tos_version', '1');
INSERT OR IGNORE INTO settings VALUES ('payment_methods', '[{"name":"آسيا باي","account":"XXXXXXXXXX","note":""},{"name":"زين كاش","account":"XXXXXXXXXX","note":""},{"name":"كي كارد","account":"XXXXXXXXXX","note":""},{"name":"FIB","account":"XXXXXXXXXX","note":""}]');
INSERT OR IGNORE INTO settings VALUES ('support_telegram', 'https://t.me/XXXXXXX');
INSERT OR IGNORE INTO settings VALUES ('support_whatsapp', 'https://wa.me/964XXXXXXXXXX');

CREATE INDEX IF NOT EXISTS idx_devices_account ON devices(account_id);
CREATE INDEX IF NOT EXISTS idx_orders_account ON orders(account_id);

CREATE TABLE IF NOT EXISTS accounts (
    id TEXT PRIMARY KEY,
    username TEXT NOT NULL,
    username_norm TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    password_salt TEXT NOT NULL,
    password_algorithm TEXT NOT NULL DEFAULT 'PBKDF2-SHA256',
    password_iterations INTEGER NOT NULL,
    password_version INTEGER NOT NULL DEFAULT 1,
    status TEXT NOT NULL DEFAULT 'active' CHECK(status IN ('pending','active','locked','disabled')),
    account_epoch INTEGER NOT NULL DEFAULT 1,
    active_device_id TEXT,
    latest_change_id INTEGER NOT NULL DEFAULT 0,
    data_schema_version INTEGER NOT NULL DEFAULT 1,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS account_licenses (
    account_id TEXT PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
    plan_id TEXT,
    status TEXT NOT NULL CHECK(status IN ('trial','paid','owner','expired')),
    trial_ends_at INTEGER NOT NULL DEFAULT 0,
    paid_until INTEGER NOT NULL DEFAULT 0,
    lifetime INTEGER NOT NULL DEFAULT 0,
    source TEXT NOT NULL,
    legacy_device_id TEXT,
    updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS account_devices (
    id TEXT PRIMARY KEY,
    account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    fingerprint_hash TEXT NOT NULL,
    install_id_hash TEXT NOT NULL,
    name TEXT,
    app_version INTEGER,
    status TEXT NOT NULL CHECK(status IN ('pending','active','freeze_pending','frozen','restoring','wipe_pending','wiped','revoked')),
    device_epoch INTEGER NOT NULL DEFAULT 1,
    last_seen_at INTEGER,
    last_sync_at INTEGER,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    UNIQUE(account_id, install_id_hash)
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_account_active_device ON account_devices(account_id) WHERE status = 'active';
CREATE INDEX IF NOT EXISTS idx_account_devices_account ON account_devices(account_id, status);

CREATE TABLE IF NOT EXISTS refresh_sessions (
    id TEXT PRIMARY KEY,
    family_id TEXT NOT NULL,
    account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    device_id TEXT NOT NULL REFERENCES account_devices(id) ON DELETE CASCADE,
    token_hash TEXT NOT NULL UNIQUE,
    account_epoch INTEGER NOT NULL,
    device_epoch INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    used_at INTEGER,
    revoked_at INTEGER,
    replaced_by TEXT,
    created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_refresh_account_device ON refresh_sessions(account_id, device_id);
CREATE INDEX IF NOT EXISTS idx_refresh_family ON refresh_sessions(family_id);

CREATE TABLE IF NOT EXISTS sync_records (
    account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    entity_type TEXT NOT NULL,
    entity_id TEXT NOT NULL,
    revision INTEGER NOT NULL,
    schema_version INTEGER NOT NULL,
    payload_json TEXT,
    deleted_at INTEGER,
    updated_at INTEGER NOT NULL,
    updated_by_type TEXT NOT NULL CHECK(updated_by_type IN ('device','admin','system')),
    updated_by_id TEXT NOT NULL,
    operation_id TEXT NOT NULL,
    PRIMARY KEY(account_id, entity_type, entity_id)
);
CREATE INDEX IF NOT EXISTS idx_sync_records_export ON sync_records(account_id, entity_type, deleted_at, entity_id);

CREATE TABLE IF NOT EXISTS sync_changes (
    change_id INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    operation_id TEXT NOT NULL,
    group_index INTEGER NOT NULL,
    group_size INTEGER NOT NULL,
    entity_type TEXT NOT NULL,
    entity_id TEXT NOT NULL,
    revision INTEGER NOT NULL,
    schema_version INTEGER NOT NULL,
    action TEXT NOT NULL CHECK(action IN ('upsert','delete')),
    payload_json TEXT,
    actor_type TEXT NOT NULL CHECK(actor_type IN ('device','admin','system')),
    actor_id TEXT NOT NULL,
    created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_sync_changes_cursor ON sync_changes(account_id, change_id);
CREATE INDEX IF NOT EXISTS idx_sync_changes_operation ON sync_changes(account_id, operation_id, group_index);

CREATE TABLE IF NOT EXISTS sync_operations (
    account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    operation_id TEXT NOT NULL,
    device_id TEXT,
    request_hash TEXT NOT NULL,
    status TEXT NOT NULL CHECK(status IN ('accepted','conflict','rejected')),
    response_json TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    PRIMARY KEY(account_id, operation_id)
);
CREATE INDEX IF NOT EXISTS idx_sync_operations_expiry ON sync_operations(expires_at);

CREATE TABLE IF NOT EXISTS sync_conflicts (
    id TEXT PRIMARY KEY,
    account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    operation_id TEXT NOT NULL,
    entity_type TEXT NOT NULL,
    entity_id TEXT NOT NULL,
    base_revision INTEGER,
    current_revision INTEGER,
    incoming_json TEXT NOT NULL,
    current_json TEXT,
    actor_type TEXT NOT NULL,
    actor_id TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'open' CHECK(status IN ('open','resolved','discarded')),
    resolution_json TEXT,
    created_at INTEGER NOT NULL,
    resolved_at INTEGER
);
CREATE INDEX IF NOT EXISTS idx_sync_conflicts_account ON sync_conflicts(account_id, status, created_at);

CREATE TABLE IF NOT EXISTS sync_assertions (
    id TEXT PRIMARY KEY,
    ok INTEGER NOT NULL CHECK(ok = 1)
);

CREATE TABLE IF NOT EXISTS device_transfers (
    id TEXT PRIMARY KEY,
    account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    source_device_id TEXT NOT NULL REFERENCES account_devices(id),
    target_device_id TEXT NOT NULL REFERENCES account_devices(id),
    status TEXT NOT NULL CHECK(status IN ('requested','approved','source_frozen','target_restoring','target_confirmed','target_active','source_wipe_pending','complete','rejected','cancelled','expired')),
    requested_at INTEGER NOT NULL,
    approved_at INTEGER,
    frozen_at INTEGER,
    snapshot_cursor INTEGER,
    snapshot_hash TEXT,
    restored_at INTEGER,
    activated_at INTEGER,
    wipe_ack_at INTEGER,
    approved_by TEXT,
    forced_without_source_ack INTEGER NOT NULL DEFAULT 0,
    expires_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_device_transfers_account ON device_transfers(account_id, status);

CREATE TABLE IF NOT EXISTS account_audit_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id TEXT,
    actor_type TEXT NOT NULL CHECK(actor_type IN ('device','admin','system')),
    actor_id TEXT,
    action TEXT NOT NULL,
    target_type TEXT,
    target_id TEXT,
    metadata_json TEXT,
    ip_hash TEXT,
    created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_account_audit_lookup ON account_audit_events(account_id, created_at);
