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

export type ActivateBody = z.infer<typeof activateBodySchema>;
export type RefreshBody = z.infer<typeof refreshBodySchema>;
