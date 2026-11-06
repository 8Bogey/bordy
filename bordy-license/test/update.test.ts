import { describe, expect, it } from 'vitest';
import {
  canonicalVersion,
  compareVersions,
  isValidVersion,
  normalizeVersionInput,
} from '../src/version';
import {
  checkIntervalSeconds,
  clampCheckUnit,
  clampCheckValue,
  clampTriggerId,
  clampTriggerMode,
  readTrigger,
  saneVersions,
  triggerMessageText,
  triggerPayload,
  wifiOnlyEnabled,
  UPDATE_CHECK_DEFAULT_UNIT,
  UPDATE_CHECK_DEFAULT_VALUE,
  UPDATE_CHECK_MIN,
} from '../src/update-config';

describe('normalizeVersionInput', () => {
  it('converts Arabic-Indic and Extended Arabic-Indic digits', () => {
    expect(normalizeVersionInput('١.٠')).toBe('1.0');
    expect(normalizeVersionInput('۲.۱')).toBe('2.1');
  });

  it('converts Arabic decimal separator and commas, and trims', () => {
    expect(normalizeVersionInput('1٫2')).toBe('1.2');
    expect(normalizeVersionInput('1,2')).toBe('1.2');
    expect(normalizeVersionInput(' 1.0 ')).toBe('1.0');
  });
});

describe('isValidVersion', () => {
  it('accepts dotted numeric versions like 1.0 and 2.1', () => {
    expect(isValidVersion('1.0')).toBe(true);
    expect(isValidVersion('2.1')).toBe(true);
    expect(isValidVersion('١.٢')).toBe(true);
  });

  it('rejects letters, empty and trailing dots', () => {
    expect(isValidVersion('')).toBe(false);
    expect(isValidVersion('1.')).toBe(false);
    expect(isValidVersion('a.b')).toBe(false);
    expect(isValidVersion('1.0-beta')).toBe(false);
  });
});

describe('canonicalVersion', () => {
  it('strips leading zeros per segment', () => {
    expect(canonicalVersion('01.00')).toBe('1.0');
  });
});

describe('compareVersions', () => {
  it('treats "1" and "1.0" as equal', () => {
    expect(compareVersions('1', '1.0')).toBe(0);
  });

  it('compares numerically per segment, not lexicographically', () => {
    expect(compareVersions('1.9', '1.10')).toBeLessThan(0);
    expect(compareVersions('1.10', '1.9')).toBeGreaterThan(0);
    expect(compareVersions('2.0', '1.9')).toBeGreaterThan(0);
  });

  it('handles different segment counts with missing = 0', () => {
    expect(compareVersions('1.0.1', '1.0')).toBeGreaterThan(0);
    expect(compareVersions('1.0', '1.0.0')).toBe(0);
  });
});

describe('update check cadence', () => {
  it('defaults to hours for unknown units', () => {
    expect(clampCheckUnit(undefined)).toBe(UPDATE_CHECK_DEFAULT_UNIT);
    expect(clampCheckUnit('days')).toBe(UPDATE_CHECK_DEFAULT_UNIT);
    expect(clampCheckUnit('minutes')).toBe('minutes');
    expect(clampCheckUnit('seconds')).toBe('seconds');
  });

  it('never goes below 5 and clamps to the unit ceiling', () => {
    expect(UPDATE_CHECK_MIN).toBe(5);
    expect(clampCheckValue('0', 'seconds')).toBe(5);
    expect(clampCheckValue('3', 'minutes')).toBe(5);
    expect(clampCheckValue('abc', 'hours')).toBe(UPDATE_CHECK_DEFAULT_VALUE);
    expect(clampCheckValue('30', 'seconds')).toBe(30);
    expect(clampCheckValue('10', 'minutes')).toBe(10);
    expect(clampCheckValue('999999', 'seconds')).toBe(86400);
    expect(clampCheckValue('999999', 'minutes')).toBe(1440);
    expect(clampCheckValue('999999', 'hours')).toBe(168);
  });

  it('converts the chosen unit to seconds', () => {
    expect(checkIntervalSeconds('seconds', 5)).toBe(5);
    expect(checkIntervalSeconds('minutes', 5)).toBe(300);
    expect(checkIntervalSeconds('hours', 12)).toBe(43200);
  });

  it('wifi-only defaults to on and honors explicit off', () => {
    expect(wifiOnlyEnabled(undefined)).toBe(true);
    expect(wifiOnlyEnabled('1')).toBe(true);
    expect(wifiOnlyEnabled('0')).toBe(false);
    expect(wifiOnlyEnabled('off')).toBe(false);
  });
});

describe('update trigger', () => {
  it('defaults to off and rejects unknown modes', () => {
    expect(clampTriggerMode(undefined)).toBe('off');
    expect(clampTriggerMode('FORCE')).toBe('off');
    expect(clampTriggerMode('optional')).toBe('optional');
    expect(clampTriggerMode('forced')).toBe('forced');
  });

  it('treats a missing or malformed stamp as 0', () => {
    expect(clampTriggerId(undefined)).toBe(0);
    expect(clampTriggerId('0')).toBe(0);
    expect(clampTriggerId('-5')).toBe(0);
    expect(clampTriggerId('abc')).toBe(0);
    expect(clampTriggerId('1730000000000')).toBe(1730000000000);
  });

  it('trims and caps the prompt message', () => {
    expect(triggerMessageText('  تحديث جديد  ')).toBe('تحديث جديد');
    expect(triggerMessageText('أ'.repeat(500))).toHaveLength(200);
  });

  it('reads the trigger from settings rows', () => {
    const t = readTrigger(
      { update_trigger_mode: 'forced', update_trigger_id: '1730000000000', update_trigger_message: ' ثبّت الآن ' },
      '1.2',
    );
    expect(t).toEqual({ mode: 'forced', id: 1730000000000, message: 'ثبّت الآن', latestVersion: '1.2' });
    expect(readTrigger({}, '1.0')).toEqual({ mode: 'off', id: 0, message: '', latestVersion: '1.0' });
  });

  it('sends the trigger with the key names the Android client actually reads', () => {
    const payload = triggerPayload(readTrigger(
      { update_trigger_mode: 'optional', update_trigger_id: '1730000000000', update_trigger_message: 'ثبّت' },
      '1.2',
    ));
    expect(payload).toEqual({ mode: 'optional', id: 1730000000000, message: 'ثبّت', latest_version: '1.2' });
    expect(payload).not.toHaveProperty('latestVersion');
    expect(triggerPayload(null)).toEqual({ mode: 'off' });
  });

  it('never lets latest sit below the enforced minimum', () => {
    expect(saneVersions('1.1', '1.0').latestVersion).toBe('1.1');
    expect(saneVersions(undefined, '2.0')).toEqual({ minVersion: '1.0', latestVersion: '2.0' });
    expect(saneVersions('٢٫٥', '٢٫٧')).toEqual({ minVersion: '2.5', latestVersion: '2.7' });
  });
});
