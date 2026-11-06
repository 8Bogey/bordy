-- Update-check cadence: the owner can now pick seconds, minutes or hours (min value 5).
INSERT OR IGNORE INTO settings (k, v) VALUES ('update_check_unit', 'hours');
INSERT OR IGNORE INTO settings (k, v)
  SELECT 'update_check_value', v FROM settings WHERE k = 'update_check_hours';
INSERT OR IGNORE INTO settings (k, v) VALUES ('update_check_value', '12');
UPDATE settings SET v = '5' WHERE k = 'update_check_value' AND CAST(v AS INTEGER) < 5;
