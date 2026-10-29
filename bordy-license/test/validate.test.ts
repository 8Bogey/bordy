import { describe, expect, it } from 'vitest';
import {
  activateBodySchema,
  cancelOrderBodySchema,
  checkCodeBodySchema,
  createOrderBodySchema,
  redeemBodySchema,
  refreshBodySchema,
  submitProofBodySchema,
  transferBodySchema,
} from '../src/validate';

const DID = 'a'.repeat(32);

describe('activateBodySchema', () => {
  it('accepts the minimum payload the app sends', () => {
    const parsed = activateBodySchema.safeParse({ did: DID, nonce: 'n1' });
    expect(parsed.success).toBe(true);
  });

  it('accepts the full payload with owner code', () => {
    const parsed = activateBodySchema.safeParse({
      did: DID, nonce: 'n1', ver: 12, model: 'SM-A155F', sdk: 34, cert_hash: 'AB:CD', owner_code: 'X',
    });
    expect(parsed.success).toBe(true);
  });

  it('keeps unknown fields for backward compatibility', () => {
    const parsed = activateBodySchema.safeParse({ did: DID, nonce: 'n1', future_field: 'x' });
    expect(parsed.success).toBe(true);
    expect(parsed.success && parsed.data).toMatchObject({ future_field: 'x' });
  });

  it('rejects missing or non-string did', () => {
    expect(activateBodySchema.safeParse({ nonce: 'n1' }).success).toBe(false);
    expect(activateBodySchema.safeParse({ did: 123, nonce: 'n1' }).success).toBe(false);
    expect(activateBodySchema.safeParse({ did: '', nonce: 'n1' }).success).toBe(false);
  });

  it('rejects missing nonce', () => {
    expect(activateBodySchema.safeParse({ did: DID }).success).toBe(false);
  });

  it('rejects a non-numeric ver', () => {
    expect(activateBodySchema.safeParse({ did: DID, nonce: 'n1', ver: '12' }).success).toBe(false);
  });

  it('rejects null bodies', () => {
    expect(activateBodySchema.safeParse(null).success).toBe(false);
    expect(activateBodySchema.safeParse(undefined).success).toBe(false);
  });
});

describe('refreshBodySchema', () => {
  it('accepts did + nonce', () => {
    expect(refreshBodySchema.safeParse({ did: DID, nonce: 'n1' }).success).toBe(true);
  });

  it('rejects an empty nonce', () => {
    expect(refreshBodySchema.safeParse({ did: DID, nonce: '' }).success).toBe(false);
  });
});

describe('checkCodeBodySchema / redeemBodySchema', () => {
  it('accepts did + code, with optional plan_id', () => {
    expect(checkCodeBodySchema.safeParse({ did: DID, code: 'RAMADAN10' }).success).toBe(true);
    expect(checkCodeBodySchema.safeParse({ did: DID, code: 'X', plan_id: 'plus' }).success).toBe(true);
    expect(redeemBodySchema.safeParse({ did: DID, code: 'X' }).success).toBe(true);
  });

  it('rejects a missing code', () => {
    expect(checkCodeBodySchema.safeParse({ did: DID }).success).toBe(false);
    expect(redeemBodySchema.safeParse({ code: 'X' }).success).toBe(false);
  });
});

describe('transferBodySchema', () => {
  it('accepts both device ids with an optional admin secret', () => {
    expect(transferBodySchema.safeParse({ from_did: DID, to_did: 'b'.repeat(32) }).success).toBe(true);
    expect(transferBodySchema.safeParse({ from_did: DID, to_did: 'b'.repeat(32), admin_secret: 's' }).success).toBe(true);
  });

  it('rejects a missing destination', () => {
    expect(transferBodySchema.safeParse({ from_did: DID }).success).toBe(false);
  });
});

describe('createOrderBodySchema', () => {
  it('accepts the payload the app sends with payment details', () => {
    expect(createOrderBodySchema.safeParse({
      did: DID, plan_id: 'pro', code: 'RAMADAN10', method: 'asia', payer_name: 'أحمد', payer_phone: '0770',
    }).success).toBe(true);
  });

  it('rejects a missing plan', () => {
    expect(createOrderBodySchema.safeParse({ did: DID }).success).toBe(false);
  });
});

describe('submitProofBodySchema', () => {
  it('accepts a legacy proof without a payment method', () => {
    expect(submitProofBodySchema.safeParse({ did: DID, image: 'base64...' }).success).toBe(true);
  });

  it('accepts the preferred proof keys', () => {
    expect(submitProofBodySchema.safeParse({
      did: DID, method: 'fib', txn_ref: 'T1', proof_data: 'base64...', proof_mime: 'image/jpeg',
    }).success).toBe(true);
  });

  it('rejects a missing device id', () => {
    expect(submitProofBodySchema.safeParse({ method: 'fib' }).success).toBe(false);
  });
});

describe('cancelOrderBodySchema', () => {
  it('accepts and rejects by did only', () => {
    expect(cancelOrderBodySchema.safeParse({ did: DID }).success).toBe(true);
    expect(cancelOrderBodySchema.safeParse({ did: '' }).success).toBe(false);
    expect(cancelOrderBodySchema.safeParse(null).success).toBe(false);
  });
});
