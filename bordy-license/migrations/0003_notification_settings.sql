-- Owner notification toggle and configurable expiry notice.
-- Safe to re-run: INSERT OR IGNORE keeps any value the owner already saved.

INSERT OR IGNORE INTO settings (k, v) VALUES ('bot_notifications_enabled', '1');
INSERT OR IGNORE INTO settings (k, v) VALUES ('expiry_notice_enabled', '1');
INSERT OR IGNORE INTO settings (k, v) VALUES ('expiry_notice_unit', 'days');
INSERT OR IGNORE INTO settings (k, v) VALUES ('expiry_notice_value', '3');
