import { describe, expect, it } from 'vitest';
import { buildTokenPayload } from '../src/handlers/activate';
import {
  clampTrialSeconds,
  clampExpiryNoticeUnit,
  clampExpiryNoticeValue,
  expiryNoticeEnabled,
  TRIAL_DEFAULT_SECONDS,
  TRIAL_MAX_SECONDS,
  TRIAL_MIN_SECONDS,
} from '../src/handlers/config';
import { DeviceRow } from '../src/types';

const DAY = 86400000;
const DID = 'a'.repeat(32);
const NOW = 1730000000000;

function device(overrides: Partial<DeviceRow> = {}): DeviceRow {
  return {
    device_id: DID,
    first_seen: NOW - 30 * DAY,
    last_seen: NOW,
    trial_started_at: NOW - 30 * DAY,
    trial_ends_at: NOW - 20 * DAY,
    paid_until: 0,
    lifetime: 0,
    blocked: 0,
    block_reason: null,
    name: null,
    phone: null,
    notes: null,
    app_ver: 12,
    model: 'SM-A155F',
    sdk: 34,
    cert_hash: null,
    tos_accepted: 1,
    tos_accepted_at: NOW,
    transfers_used: 0,
    ...overrides,
  };
}

// paidTtlDays=7, graceDays=2 — the production defaults read from settings.
function payload(overrides: Partial<DeviceRow> = {}) {
  return buildTokenPayload(device(overrides), NOW, 'nonce-1', 7, 2, '12', 3);
}

describe('buildTokenPayload status selection', () => {
  it('grants owner status and a century-long expiry to lifetime devices', () => {
    const p = payload({ lifetime: 1, paid_until: 0 });
    expect(p.st).toBe('owner');
    // An offline owner device must not lock itself out when the short paid TTL elapses.
    expect(p.exp).toBe(NOW + 100 * 365 * DAY);
    expect(p.exp).toBeGreaterThan(NOW + 7 * DAY);
  });

  it('prefers lifetime over an expired paid window', () => {
    expect(payload({ lifetime: 1, paid_until: NOW - DAY }).st).toBe('owner');
  });

  it('grants paid status while paid_until is still in the future', () => {
    const p = payload({ paid_until: NOW + 30 * DAY });
    expect(p.st).toBe('paid');
    expect(p.pu).toBe(NOW + 30 * DAY);
  });

  it('falls back to trial once both lifetime and the paid window are gone', () => {
    expect(payload({ paid_until: NOW - DAY }).st).toBe('trial');
  });
});

describe('buildTokenPayload expiry capping', () => {
  it('uses paid_until plus grace when it ends before the TTL cap', () => {
    const p = payload({ paid_until: NOW + DAY });
    expect(p.exp).toBe(NOW + DAY + 2 * DAY);
  });

  it('caps a long remaining subscription at the token TTL', () => {
    const p = payload({ paid_until: NOW + 365 * DAY });
    expect(p.exp).toBe(NOW + 7 * DAY);
  });

  it('caps an active trial at the token TTL too', () => {
    const p = payload({ trial_ends_at: NOW + 30 * DAY, paid_until: 0 });
    expect(p.st).toBe('trial');
    expect(p.exp).toBe(NOW + 7 * DAY);
  });

  it('lets an expired trial run into its grace period', () => {
    const p = payload({ trial_ends_at: NOW - DAY, paid_until: 0 });
    expect(p.exp).toBe(NOW - DAY + 2 * DAY);
  });

  it('gives a trial-less device only the grace window, not the full TTL', () => {
    const p = payload({ trial_ends_at: null, paid_until: 0 });
    expect(p.tre).toBe(0);
    expect(p.exp).toBe(2 * DAY);
    expect(p.exp).toBeLessThan(NOW);
  });
});

describe('buildTokenPayload pass-through fields', () => {
  it('echoes the token envelope the app verifies against', () => {
    const p = payload({ lifetime: 1 });
    expect(p).toMatchObject({
      v: 1,
      kid: 'k1',
      did: DID,
      iat: NOW,
      exp: NOW + 100 * 365 * DAY,
      nonce: 'nonce-1',
      minv: 12,
      minv_name: '12',
      tos_ver: 3,
    });
  });

  it('keeps a decimal minimum version for name-based comparison', () => {
    const p = buildTokenPayload(device({ lifetime: 1 }), NOW, 'nonce-2', 7, 2, '1.0', 3);
    expect(p.minv).toBe(1);
    expect(p.minv_name).toBe('1.0');
  });

  it('keeps the caller nonce verbatim so the app can match it', () => {
    const nonce = 'x/y+z==';
    expect(buildTokenPayload(device({ lifetime: 1 }), NOW, nonce, 7, 2, '1', 1).nonce).toBe(nonce);
  });
});

describe('clampTrialSeconds', () => {
  it('uses trial_seconds when it is a sane positive number', () => {
    expect(clampTrialSeconds('3600', '7')).toBe(3600);
  });

  it('clamps an absurdly long trial to the maximum', () => {
    expect(clampTrialSeconds('99999999999', null)).toBe(TRIAL_MAX_SECONDS);
  });

  it('clamps a too-short trial to the minimum', () => {
    expect(clampTrialSeconds('1', null)).toBe(TRIAL_MIN_SECONDS);
  });

  it('falls back to the legacy trial_days when trial_seconds is unusable', () => {
    expect(clampTrialSeconds(undefined, '14')).toBe(14 * 86400);
    expect(clampTrialSeconds('abc', '3')).toBe(3 * 86400);
  });

  it('treats an explicit zero as "no free trial" instead of falling back', () => {
    expect(clampTrialSeconds('0', '3')).toBe(0);
    expect(clampTrialSeconds('0', null)).toBe(0);
  });

  it('defaults to a week when neither setting is valid', () => {
    expect(clampTrialSeconds(null, null)).toBe(TRIAL_DEFAULT_SECONDS);
    expect(clampTrialSeconds('', '')).toBe(TRIAL_DEFAULT_SECONDS);
    expect(clampTrialSeconds('-5', '-1')).toBe(TRIAL_DEFAULT_SECONDS);
  });

  it('never returns below the minimum even via the legacy path', () => {
    expect(clampTrialSeconds(undefined, '0')).toBe(TRIAL_DEFAULT_SECONDS);
  });
});

describe('expiry notice settings', () => {
  it('accepts the three supported units and rejects anything else', () => {
    expect(clampExpiryNoticeUnit('days')).toBe('days');
    expect(clampExpiryNoticeUnit('hours')).toBe('hours');
    expect(clampExpiryNoticeUnit('minutes')).toBe('minutes');
    expect(clampExpiryNoticeUnit('weeks')).toBe('days');
    expect(clampExpiryNoticeUnit(undefined)).toBe('days');
  });

  it('clamps the value to the range of the chosen unit', () => {
    expect(clampExpiryNoticeValue('3', 'days')).toBe(3);
    expect(clampExpiryNoticeValue('0', 'days')).toBe(3);
    expect(clampExpiryNoticeValue('99999', 'days')).toBe(365);
    expect(clampExpiryNoticeValue('99999', 'hours')).toBe(8760);
    expect(clampExpiryNoticeValue('9999999', 'minutes')).toBe(525600);
    expect(clampExpiryNoticeValue('abc', 'hours')).toBe(3);
  });

  it('treats a missing key as enabled and explicit off as disabled', () => {
    expect(expiryNoticeEnabled(undefined)).toBe(true);
    expect(expiryNoticeEnabled(null)).toBe(true);
    expect(expiryNoticeEnabled('1')).toBe(true);
    expect(expiryNoticeEnabled('0')).toBe(false);
    expect(expiryNoticeEnabled('off')).toBe(false);
  });
});
