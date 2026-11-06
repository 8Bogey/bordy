-- Self-update system: version gate values + defaults for background download.
-- Safe to re-run: INSERT OR IGNORE keeps any value the owner already saved.
-- The owner explicitly wants the minimum allowed version to be 1.0 right now.
UPDATE settings SET v = '1.0' WHERE k = 'min_app_version';

INSERT OR IGNORE INTO settings (k, v) VALUES ('latest_app_version', '1.0');
INSERT OR IGNORE INTO settings (k, v) VALUES ('update_download_url', '');
INSERT OR IGNORE INTO settings (k, v) VALUES ('update_notes', '');
INSERT OR IGNORE INTO settings (k, v) VALUES ('update_check_hours', '12');
INSERT OR IGNORE INTO settings (k, v) VALUES ('update_wifi_only', '1');
INSERT OR IGNORE INTO settings (k, v) VALUES ('update_file', '');
