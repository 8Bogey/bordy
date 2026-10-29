import { describe, expect, it } from 'vitest';
import {
  base64urlDecode,
  base64urlEncode,
  formatBaghdadTime,
  isValidDeviceId,
  jsonResponse,
  normalizeCode,
  parsePaymentMethods,
  roundPrice,
  timingSafeEqualString,
} from '../src/utils';

describe('normalizeCode', () => {
  it('converts Arabic-Indic digits and strips separators', () => {
    expect(normalizeCode('خصم ١٢٣')).toBe('خصم123');
    expect(normalizeCode('ab-12_3')).toBe('AB123');
  });

  it('converts Extended Arabic-Indic digits', () => {
    expect(normalizeCode('۱۲۳')).toBe('123');
  });
});

describe('roundPrice', () => {
  it('rounds to the nearest 250 IQD', () => {
    expect(roundPrice(7620)).toBe(7500);
    expect(roundPrice(7640)).toBe(7750);
    expect(roundPrice(7500)).toBe(7500);
    expect(roundPrice(0)).toBe(0);
  });
});

describe('isValidDeviceId', () => {
  it('accepts exactly 32 hex characters', () => {
    expect(isValidDeviceId('0123456789abcdef0123456789ABCDEF')).toBe(true);
  });

  it('rejects wrong length or non-hex characters', () => {
    expect(isValidDeviceId('0123456789abcdef')).toBe(false);
    expect(isValidDeviceId('g123456789abcdef0123456789abcdef')).toBe(false);
  });
});

describe('base64url', () => {
  it('round-trips bytes without padding', () => {
    const bytes = new Uint8Array([0, 1, 2, 250, 251, 255]);
    const encoded = base64urlEncode(bytes);
    expect(encoded).not.toMatch(/[+/=]/);
    expect(Array.from(base64urlDecode(encoded))).toEqual(Array.from(bytes));
  });
});

describe('parsePaymentMethods', () => {
  it('returns empty array for invalid input', () => {
    expect(parsePaymentMethods('not json')).toEqual([]);
    expect(parsePaymentMethods(null)).toEqual([]);
    expect(parsePaymentMethods('{"a":1}')).toEqual([]);
  });

  it('keeps only entries with a name and account, trimmed', () => {
    const raw = JSON.stringify([
      { name: ' زين كاش ', account: ' 0770 ', note: 'تحويل' },
      { name: '', account: '1' },
      { name: 'FIB', account: 'IQ00' },
    ]);
    expect(parsePaymentMethods(raw)).toEqual([
      { name: 'زين كاش', account: '0770', note: 'تحويل' },
      { name: 'FIB', account: 'IQ00', note: '' },
    ]);
  });
});

describe('formatBaghdadTime', () => {
  it('formats UTC+3', () => {
    expect(formatBaghdadTime(Date.UTC(2026, 0, 2, 0, 30))).toBe('2026-01-02 03:30');
  });
});

describe('timingSafeEqualString', () => {
  it('compares equal and different strings', async () => {
    expect(await timingSafeEqualString('secret', 'secret')).toBe(true);
    expect(await timingSafeEqualString('secret', 'secreT')).toBe(false);
    expect(await timingSafeEqualString('', '')).toBe(false);
  });
});

describe('jsonResponse', () => {
  it('adds server_time and no-store headers', async () => {
    const res = jsonResponse({ ok: true });
    const body = await res.json() as { ok: boolean; server_time: number };
    expect(res.status).toBe(200);
    expect(res.headers.get('Cache-Control')).toBe('no-store');
    expect(body.ok).toBe(true);
    expect(typeof body.server_time).toBe('number');
  });
});
