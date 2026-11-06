-- Immediate update prompt (trigger) the owner can fire from the panel.
-- off = idle; the id is a timestamp stamp so a device dismisses each firing once.
INSERT OR IGNORE INTO settings (k, v) VALUES ('update_trigger_mode', 'off');
INSERT OR IGNORE INTO settings (k, v) VALUES ('update_trigger_id', '0');
INSERT OR IGNORE INTO settings (k, v) VALUES ('update_trigger_message', '');
