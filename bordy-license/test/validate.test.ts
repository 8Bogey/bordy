import { describe, expect, it } from 'vitest';
import { activateBodySchema, refreshBodySchema } from '../src/validate';

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
