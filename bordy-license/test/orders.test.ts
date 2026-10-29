import { describe, expect, it } from 'vitest';
import { applyDiscount, isExpired, sniffImageMime } from '../src/handlers/orders';
import { CodeRow, OrderRow } from '../src/types';

const NOW = 1730000000000;

function code(overrides: Partial<CodeRow> = {}): CodeRow {
  return {
    code: 'TEST',
    kind: 'PERCENT',
    value: 10,
    plans: '*',
    starts_at: NOW - 86400000,
    ends_at: NOW + 86400000,
    max_uses: null,
    used_count: 0,
    reserved_count: 0,
    per_device_limit: 1,
    min_price: 0,
    auto_apply: 0,
    campaign: null,
    note: null,
    active: 1,
    created_at: NOW,
    ...overrides,
  };
}

function order(overrides: Partial<OrderRow> = {}): OrderRow {
  return {
    id: 'MB-ABC',
    device_id: 'a'.repeat(32),
    plan_id: 'm1',
    base_price: 10000,
    discount: 0,
    final_price: 10000,
    code: null,
    days_granted: 30,
    method: null,
    txn_ref: null,
    payer_name: null,
    payer_phone: null,
    status: 'PENDING',
    reject_reason: null,
    provider: 'manual',
    provider_ref: null,
    created_at: NOW,
    submitted_at: null,
    decided_at: null,
    decided_by: null,
    expires_at: NOW + 86400000,
    ...overrides,
  };
}

describe('applyDiscount PERCENT', () => {
  it('takes the percentage of the base price and rounds the discount to 250', () => {
    expect(applyDiscount(code({ kind: 'PERCENT', value: 25 }), 10000, 30)).toEqual({
      discount: 2500,
      finalPrice: 7500,
      daysGranted: 30,
    });
  });

  it('rounds a discount that is not a multiple of 250', () => {
    // 15% of 1000 = 150 -> 250, and the remainder is rounded again.
    expect(applyDiscount(code({ kind: 'PERCENT', value: 15 }), 1000, 30)).toEqual({
      discount: 250,
      finalPrice: 750,
      daysGranted: 30,
    });
  });

  it('never charges a negative price at the maximum allowed percentage', () => {
    expect(applyDiscount(code({ kind: 'PERCENT', value: 100 }), 5000, 30)).toEqual({
      discount: 5000,
      finalPrice: 0,
      daysGranted: 30,
    });
  });
});

describe('applyDiscount FIXED', () => {
  it('subtracts a fixed amount within the price', () => {
    expect(applyDiscount(code({ kind: 'FIXED', value: 2500 }), 10000, 30)).toEqual({
      discount: 2500,
      finalPrice: 7500,
      daysGranted: 30,
    });
  });

  it('caps the discount at the price so the user is never paid to buy', () => {
    // FIXED is not bounded to 100 at creation time, so the guard must live here.
    const result = applyDiscount(code({ kind: 'FIXED', value: 999999 }), 1000, 30);
    expect(result.discount).toBe(1000);
    expect(result.finalPrice).toBe(0);
    expect(result.finalPrice).toBeGreaterThanOrEqual(0);
  });
});

describe('applyDiscount FULL and FREE_DAYS', () => {
  it('makes a FULL order free without extending the plan', () => {
    expect(applyDiscount(code({ kind: 'FULL', value: 100 }), 10000, 30)).toEqual({
      discount: 10000,
      finalPrice: 0,
      daysGranted: 30,
    });
  });

  it('grants FREE_DAYS as extra days at zero price', () => {
    expect(applyDiscount(code({ kind: 'FREE_DAYS', value: 15 }), 10000, 30)).toEqual({
      discount: 0,
      finalPrice: 0,
      daysGranted: 45,
    });
  });
});

describe('applyDiscount unknown kinds', () => {
  it('leaves the price untouched instead of silently zeroing it', () => {
    // A row written by hand or by an older schema must never make the app free.
    expect(applyDiscount(code({ kind: 'BOGUS' as CodeRow['kind'], value: 50 }), 10000, 30)).toEqual({
      discount: 0,
      finalPrice: 10000,
      daysGranted: 30,
    });
  });
});

describe('sniffImageMime', () => {
  const bytes = (...values: number[]) => Uint8Array.from(values);

  it('accepts a JPEG by its magic bytes', () => {
    expect(sniffImageMime(bytes(0xff, 0xd8, 0xff, 0xe0, 1, 2, 3, 4, 5, 6, 7, 8))).toBe('image/jpeg');
  });

  it('accepts a PNG', () => {
    expect(sniffImageMime(bytes(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))).toBe('image/png');
  });

  it('accepts a WEBP only when RIFF is followed by WEBP', () => {
    expect(sniffImageMime(bytes(
      0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50,
    ))).toBe('image/webp');
    expect(sniffImageMime(bytes(
      0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00, 0x41, 0x56, 0x49, 0x53,
    ))).toBeNull();
  });

  it('separates HEIC from HEIF by brand and rejects other ftyp brands', () => {
    const ftyp = (brand: string) => bytes(0, 0, 0, 24, ...'ftyp'.split('').map(c => c.charCodeAt(0)), ...brand.split('').map(c => c.charCodeAt(0)));
    expect(sniffImageMime(ftyp('heic'))).toBe('image/heic');
    expect(sniffImageMime(ftyp('heix'))).toBe('image/heic');
    expect(sniffImageMime(ftyp('mif1'))).toBe('image/heif');
    // Video containers share the ftyp box and must not pass as a receipt photo.
    expect(sniffImageMime(ftyp('avc1'))).toBeNull();
    expect(sniffImageMime(ftyp('isom'))).toBeNull();
  });

  it('rejects anything that is not a known image header', () => {
    expect(sniffImageMime(bytes(0x4d, 0x5a, 0x90, 0x00, 1, 2, 3, 4, 5, 6, 7, 8))).toBeNull(); // MZ/PE
    expect(sniffImageMime(bytes(...'%PDF-1.4'.split('').map(c => c.charCodeAt(0)), 0, 0, 0, 0))).toBeNull();
    expect(sniffImageMime(bytes(0x7b, 0x22, 0x61, 0x22, 0x3a, 0x31, 0x7d, 0, 0, 0, 0, 0))).toBeNull(); // JSON
  });

  it('rejects buffers shorter than the signature instead of reading past the end', () => {
    expect(sniffImageMime(bytes())).toBeNull();
    expect(sniffImageMime(bytes(0xff, 0xd8))).toBeNull();
    expect(sniffImageMime(bytes(0x89, 0x50, 0x4e))).toBeNull();
    expect(sniffImageMime(bytes(0, 0, 0, 24, ...'ftyp'.split('').map(c => c.charCodeAt(0)), 0, 0, 0))).toBeNull();
  });

  it('requires the third JPEG byte so a truncated header is not enough', () => {
    expect(sniffImageMime(bytes(0xff, 0xd8, 0x00, 4, 5, 6, 7, 8, 9, 10, 11, 12))).toBeNull();
  });
});

describe('isExpired', () => {
  it('expires only open states past their deadline', () => {
    expect(isExpired(order({ status: 'PENDING', expires_at: NOW - 1 }), NOW)).toBe(true);
    expect(isExpired(order({ status: 'SUBMITTED', expires_at: NOW - 1 }), NOW)).toBe(true);
  });

  it('keeps an open order alive until its deadline', () => {
    expect(isExpired(order({ status: 'PENDING', expires_at: NOW + 1 }), NOW)).toBe(false);
    expect(isExpired(order({ status: 'PENDING', expires_at: NOW }), NOW)).toBe(true);
  });

  it('never re-expires a decided order, even past its deadline', () => {
    // An APPROVED order past expires_at must not be swept back to EXPIRED.
    for (const status of ['APPROVED', 'REJECTED', 'EXPIRED', 'CANCELLED'] as const) {
      expect(isExpired(order({ status, expires_at: NOW - 1000 }), NOW)).toBe(false);
    }
  });
});
