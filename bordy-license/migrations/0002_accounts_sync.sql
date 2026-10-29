-- Account ownership, secure sessions and bidirectional business-data sync.
-- Apply once to an existing bordy-license D1 database.

ALTER TABLE devices ADD COLUMN account_id TEXT;
ALTER TABLE orders ADD COLUMN account_id TEXT;
CREATE INDEX IF NOT EXISTS idx_devices_account ON devices(account_id);
CREATE INDEX IF NOT EXISTS idx_orders_account ON orders(account_id);

CREATE TABLE IF NOT EXISTS accounts (
    id                  TEXT PRIMARY KEY,
    username            TEXT NOT NULL,
    username_norm       TEXT NOT NULL UNIQUE,
    password_hash       TEXT NOT NULL,
    password_salt       TEXT NOT NULL,
    password_algorithm  TEXT NOT NULL DEFAULT 'PBKDF2-SHA256',
    password_iterations INTEGER NOT NULL,
    password_version    INTEGER NOT NULL DEFAULT 1,
    status              TEXT NOT NULL DEFAULT 'active' CHECK(status IN ('pending','active','locked','disabled')),
    account_epoch       INTEGER NOT NULL DEFAULT 1,
    active_device_id    TEXT,
    latest_change_id    INTEGER NOT NULL DEFAULT 0,
    data_schema_version INTEGER NOT NULL DEFAULT 1,
    created_at          INTEGER NOT NULL,
    updated_at          INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS account_licenses (
    account_id       TEXT PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
    plan_id          TEXT,
    status           TEXT NOT NULL CHECK(status IN ('trial','paid','owner','expired')),
    trial_ends_at    INTEGER NOT NULL DEFAULT 0,
    paid_until       INTEGER NOT NULL DEFAULT 0,
    lifetime         INTEGER NOT NULL DEFAULT 0,
    source           TEXT NOT NULL,
    legacy_device_id TEXT,
    updated_at       INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS account_devices (
    id                TEXT PRIMARY KEY,
    account_id        TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    fingerprint_hash  TEXT NOT NULL,
    install_id_hash   TEXT NOT NULL,
    name              TEXT,
    app_version       INTEGER,
    status            TEXT NOT NULL CHECK(status IN ('pending','active','freeze_pending','frozen','restoring','wipe_pending','wiped','revoked')),
    device_epoch      INTEGER NOT NULL DEFAULT 1,
    last_seen_at      INTEGER,
    last_sync_at      INTEGER,
    created_at        INTEGER NOT NULL,
    updated_at        INTEGER NOT NULL,
    UNIQUE(account_id, install_id_hash)
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_account_active_device
    ON account_devices(account_id) WHERE status = 'active';
CREATE INDEX IF NOT EXISTS idx_account_devices_account ON account_devices(account_id, status);

CREATE TABLE IF NOT EXISTS refresh_sessions (
    id             TEXT PRIMARY KEY,
    family_id      TEXT NOT NULL,
    account_id     TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    device_id      TEXT NOT NULL REFERENCES account_devices(id) ON DELETE CASCADE,
    token_hash     TEXT NOT NULL UNIQUE,
    account_epoch  INTEGER NOT NULL,
    device_epoch   INTEGER NOT NULL,
    expires_at     INTEGER NOT NULL,
    used_at        INTEGER,
    revoked_at     INTEGER,
    replaced_by    TEXT,
    created_at     INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_refresh_account_device ON refresh_sessions(account_id, device_id);
CREATE INDEX IF NOT EXISTS idx_refresh_family ON refresh_sessions(family_id);

CREATE TABLE IF NOT EXISTS sync_records (
    account_id      TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    entity_type     TEXT NOT NULL,
    entity_id       TEXT NOT NULL,
    revision        INTEGER NOT NULL,
    schema_version  INTEGER NOT NULL,
    payload_json    TEXT,
    deleted_at      INTEGER,
    updated_at      INTEGER NOT NULL,
    updated_by_type TEXT NOT NULL CHECK(updated_by_type IN ('device','admin','system')),
    updated_by_id   TEXT NOT NULL,
    operation_id    TEXT NOT NULL,
    PRIMARY KEY(account_id, entity_type, entity_id)
);
CREATE INDEX IF NOT EXISTS idx_sync_records_export ON sync_records(account_id, entity_type, deleted_at, entity_id);

CREATE TABLE IF NOT EXISTS sync_changes (
    change_id       INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id      TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    operation_id    TEXT NOT NULL,
    group_index     INTEGER NOT NULL,
    group_size      INTEGER NOT NULL,
    entity_type     TEXT NOT NULL,
    entity_id       TEXT NOT NULL,
    revision        INTEGER NOT NULL,
    schema_version  INTEGER NOT NULL,
    action          TEXT NOT NULL CHECK(action IN ('upsert','delete')),
    payload_json    TEXT,
    actor_type      TEXT NOT NULL CHECK(actor_type IN ('device','admin','system')),
    actor_id        TEXT NOT NULL,
    created_at      INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_sync_changes_cursor ON sync_changes(account_id, change_id);
CREATE INDEX IF NOT EXISTS idx_sync_changes_operation ON sync_changes(account_id, operation_id, group_index);

CREATE TABLE IF NOT EXISTS sync_operations (
    account_id    TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    operation_id  TEXT NOT NULL,
    device_id     TEXT,
    request_hash  TEXT NOT NULL,
    status        TEXT NOT NULL CHECK(status IN ('accepted','conflict','rejected')),
    response_json TEXT NOT NULL,
    created_at    INTEGER NOT NULL,
    expires_at    INTEGER NOT NULL,
    PRIMARY KEY(account_id, operation_id)
);
CREATE INDEX IF NOT EXISTS idx_sync_operations_expiry ON sync_operations(expires_at);

CREATE TABLE IF NOT EXISTS sync_conflicts (
    id               TEXT PRIMARY KEY,
    account_id       TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    operation_id     TEXT NOT NULL,
    entity_type      TEXT NOT NULL,
    entity_id        TEXT NOT NULL,
    base_revision    INTEGER,
    current_revision INTEGER,
    incoming_json    TEXT NOT NULL,
    current_json     TEXT,
    actor_type       TEXT NOT NULL,
    actor_id         TEXT NOT NULL,
    status           TEXT NOT NULL DEFAULT 'open' CHECK(status IN ('open','resolved','discarded')),
    resolution_json  TEXT,
    created_at       INTEGER NOT NULL,
    resolved_at      INTEGER
);
CREATE INDEX IF NOT EXISTS idx_sync_conflicts_account ON sync_conflicts(account_id, status, created_at);

-- A failing CHECK inside D1 batch aborts the entire operation and preserves atomicity.
CREATE TABLE IF NOT EXISTS sync_assertions (
    id TEXT PRIMARY KEY,
    ok INTEGER NOT NULL CHECK(ok = 1)
);

CREATE TABLE IF NOT EXISTS device_transfers (
    id                        TEXT PRIMARY KEY,
    account_id                TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    source_device_id          TEXT NOT NULL REFERENCES account_devices(id),
    target_device_id          TEXT NOT NULL REFERENCES account_devices(id),
    status                    TEXT NOT NULL CHECK(status IN ('requested','approved','source_frozen','target_restoring','target_confirmed','target_active','source_wipe_pending','complete','rejected','cancelled','expired')),
    requested_at              INTEGER NOT NULL,
    approved_at               INTEGER,
    frozen_at                 INTEGER,
    snapshot_cursor           INTEGER,
    snapshot_hash             TEXT,
    restored_at               INTEGER,
    activated_at              INTEGER,
    wipe_ack_at               INTEGER,
    approved_by               TEXT,
    forced_without_source_ack INTEGER NOT NULL DEFAULT 0,
    expires_at                INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_device_transfers_account ON device_transfers(account_id, status);

CREATE TABLE IF NOT EXISTS account_audit_events (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id    TEXT,
    actor_type    TEXT NOT NULL CHECK(actor_type IN ('device','admin','system')),
    actor_id      TEXT,
    action        TEXT NOT NULL,
    target_type   TEXT,
    target_id     TEXT,
    metadata_json TEXT,
    ip_hash       TEXT,
    created_at    INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_account_audit_lookup ON account_audit_events(account_id, created_at);
