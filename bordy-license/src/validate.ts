import { z } from 'zod';

/**
 * مخططات مسارات /v1 العامة.
 * `.passthrough()` مقصود: الإصدارات المنشورة من التطبيق قد ترسل حقولاً إضافية،
 * ورفضها يكسر التوافق. التحقق هنا للشكل فقط؛ قواعد القيم تبقى في المعالجات.
 */
export const activateBodySchema = z
  .object({
    did: z.string().min(1),
    nonce: z.string().min(1),
    ver: z.number().optional(),
    model: z.string().optional(),
    sdk: z.number().optional(),
    cert_hash: z.string().optional(),
    owner_code: z.string().optional(),
  })
  .passthrough();

export const refreshBodySchema = z
  .object({
    did: z.string().min(1),
    nonce: z.string().min(1),
  })
  .passthrough();

export const checkCodeBodySchema = z
  .object({
    did: z.string().min(1),
    code: z.string().min(1),
    plan_id: z.string().optional(),
  })
  .passthrough();

export const redeemBodySchema = z
  .object({
    did: z.string().min(1),
    code: z.string().min(1),
  })
  .passthrough();

export const transferBodySchema = z
  .object({
    from_did: z.string().min(1),
    to_did: z.string().min(1),
    admin_secret: z.string().optional(),
  })
  .passthrough();

export const createOrderBodySchema = z
  .object({
    did: z.string().min(1),
    plan_id: z.string().min(1),
    code: z.string().optional(),
    method: z.string().optional(),
    payer_name: z.string().optional(),
    payer_phone: z.string().optional(),
  })
  .passthrough();

/** `method` اختياري: الإصدارات القديمة ترسل الإيصال بدونه. */
export const submitProofBodySchema = z
  .object({
    did: z.string().min(1),
    method: z.string().optional(),
    txn_ref: z.string().optional(),
    payer_name: z.string().optional(),
    payer_phone: z.string().optional(),
    proof_data: z.string().optional(),
    image: z.string().optional(),
    proof_mime: z.string().optional(),
  })
  .passthrough();

export const cancelOrderBodySchema = z
  .object({
    did: z.string().min(1),
  })
  .passthrough();

export type ActivateBody = z.infer<typeof activateBodySchema>;
export type RefreshBody = z.infer<typeof refreshBodySchema>;
