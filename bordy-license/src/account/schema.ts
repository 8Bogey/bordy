import { SyncEntityType } from './types';

interface FieldRule {
  type: 'string' | 'number' | 'integer' | 'boolean' | 'nullable-number' | 'nullable-string';
  max?: number;
  min?: number;
  maxValue?: number;
  reference?: SyncEntityType;
}

type EntitySchema = Record<string, FieldRule>;

const ref = (entity: SyncEntityType): FieldRule => ({ type: 'string', max: 64, reference: entity });
const text = (max: number): FieldRule => ({ type: 'string', max });
const number = (min = -1_000_000_000, maxValue = 1_000_000_000): FieldRule => ({ type: 'number', min, maxValue });
const integer = (min = -1_000_000_000, maxValue = 1_000_000_000): FieldRule => ({ type: 'integer', min, maxValue });

export const ENTITY_SCHEMAS: Record<SyncEntityType, EntitySchema> = {
  suppliers: {
    name: text(200), phone: text(80), url: text(1_000), rating: integer(0, 5),
    defaultMinOrder: integer(0, 1_000_000), deliveryCost: number(0), orderIndex: integer(),
  },
  components: {
    name: text(200), brand: text(200), origin: text(200), customFields: text(16_000),
    orderIndex: integer(), stockCount: integer(0), purchasePrice: number(0), sellPrice: number(0),
  },
  tags: { name: text(120), colorIndex: integer(0, 100), createdAt: integer(0, Number.MAX_SAFE_INTEGER) },
  component_tags: { componentId: ref('components'), tagId: ref('tags') },
  component_variants: {
    componentId: ref('components'), specName: text(200), defaultPurchasePrice: number(0),
    defaultSellPrice: number(0), stockCount: integer(0), isBase: { type: 'boolean' },
  },
  supplier_offers: {
    variantId: ref('component_variants'), supplierId: ref('suppliers'), purchasePrice: number(0),
    minOrder: integer(0, 1_000_000),
  },
  cart_items: { offerId: ref('supplier_offers'), quantity: integer(1, 1_000_000), orderIndex: integer() },
  templates: {
    name: text(200), boxWidth: number(0), boxHeight: number(0), boxDepth: number(0),
    createdAt: integer(0, Number.MAX_SAFE_INTEGER), laborCost: number(0),
  },
  template_items: {
    templateId: ref('templates'), variantId: { type: 'nullable-string', max: 64, reference: 'component_variants' },
    quantity: integer(1, 1_000_000), orderIndex: integer(), manualName: text(200),
    purchasePrice: number(0), sellPrice: number(0), isManual: { type: 'boolean' },
  },
  projects: {
    customerName: text(300), dueDate: { type: 'nullable-number', min: 0, maxValue: Number.MAX_SAFE_INTEGER },
    status: text(40), completedAt: { type: 'nullable-number', min: 0, maxValue: Number.MAX_SAFE_INTEGER },
    laborCost: number(0), receivedAmount: number(0), createdAt: integer(0, Number.MAX_SAFE_INTEGER),
  },
  project_items: {
    projectId: ref('projects'), variantId: { type: 'nullable-string', max: 64, reference: 'component_variants' },
    manualName: text(200), quantity: integer(1, 1_000_000), sellPrice: number(0),
    fromStock: { type: 'boolean' }, isCustomSell: { type: 'boolean' }, lastKnownPurchase: number(0),
    isManual: { type: 'boolean' }, orderIndex: integer(), isShortage: { type: 'boolean' },
    shortageRemaining: integer(0), shortageFromStock: integer(0), shortageManual: integer(0),
    shortageAddedQty: integer(0), shortageManualPurchase: number(0), shortageManualSell: number(0),
  },
  project_notes: {
    projectId: ref('projects'), title: text(300), body: text(32_000), colorIndex: integer(0, 100),
    updatedAt: integer(0, Number.MAX_SAFE_INTEGER),
  },
  business_settings: {
    stockHighThreshold: integer(0), stockMediumThreshold: integer(0), defaultLaborCost: number(0),
    businessName: text(200), businessTagline: text(500), businessPhone: text(80), invoiceFooter: text(1_000),
    showPurchaseInPdf: { type: 'boolean' }, showProfitInPdf: { type: 'boolean' },
    deliveryHour: integer(0, 23), deliveryMinute: integer(0, 59), reminderDaysBefore: integer(0, 365),
    reminderHoursBefore: integer(0, 168),
  },
};

export const STRICT_FIELDS: Partial<Record<SyncEntityType, Set<string>>> = {
  components: new Set(['stockCount', 'purchasePrice', 'sellPrice']),
  component_variants: new Set(['stockCount', 'defaultPurchasePrice', 'defaultSellPrice']),
  supplier_offers: new Set(['purchasePrice']),
  cart_items: new Set(['quantity']),
  template_items: new Set(['quantity', 'purchasePrice', 'sellPrice']),
  projects: new Set(['laborCost', 'receivedAmount', 'status', 'completedAt']),
  project_items: new Set([
    'quantity', 'sellPrice', 'lastKnownPurchase', 'shortageRemaining', 'shortageFromStock',
    'shortageManual', 'shortageAddedQty', 'shortageManualPurchase', 'shortageManualSell',
  ]),
};

export function isEntityType(value: string): value is SyncEntityType {
  return Object.prototype.hasOwnProperty.call(ENTITY_SCHEMAS, value);
}

export function validateEntityId(value: string): boolean {
  return /^[A-Za-z0-9_-]{16,64}$/.test(value);
}

export function validatePayload(entity: SyncEntityType, payload: unknown): string | null {
  if (!payload || typeof payload !== 'object' || Array.isArray(payload)) return 'بيانات السجل ليست كائنًا';
  const object = payload as Record<string, unknown>;
  const schema = ENTITY_SCHEMAS[entity];
  const keys = Object.keys(object);
  const unknown = keys.find(key => !schema[key]);
  if (unknown) return `حقل غير مسموح: ${unknown}`;
  const missing = Object.keys(schema).find(key => !(key in object));
  if (missing) return `حقل مطلوب مفقود: ${missing}`;

  for (const [name, rule] of Object.entries(schema)) {
    const value = object[name];
    if (rule.type === 'nullable-number' && value === null) continue;
    if (rule.type === 'nullable-string' && value === null) continue;
    if ((rule.type === 'string' || rule.type === 'nullable-string') && typeof value === 'string') {
      if (value.length > (rule.max || 64_000)) return `الحقل ${name} أطول من المسموح`;
      if (rule.reference && !validateEntityId(value)) return `مرجع ${name} غير صالح`;
      continue;
    }
    if ((rule.type === 'number' || rule.type === 'nullable-number') && typeof value === 'number' && Number.isFinite(value)) {
      if (value < (rule.min ?? -Infinity) || value > (rule.maxValue ?? Infinity)) return `قيمة ${name} خارج النطاق`;
      continue;
    }
    if (rule.type === 'integer' && typeof value === 'number' && Number.isSafeInteger(value)) {
      if (value < (rule.min ?? -Infinity) || value > (rule.maxValue ?? Infinity)) return `قيمة ${name} خارج النطاق`;
      continue;
    }
    if (rule.type === 'boolean' && typeof value === 'boolean') continue;
    return `نوع الحقل ${name} غير صالح`;
  }
  return null;
}

export function referencedRecords(
  entity: SyncEntityType,
  payload: Record<string, unknown>,
): Array<{ entityType: SyncEntityType; entityId: string }> {
  const refs: Array<{ entityType: SyncEntityType; entityId: string }> = [];
  for (const [name, rule] of Object.entries(ENTITY_SCHEMAS[entity])) {
    const value = payload[name];
    if (rule.reference && typeof value === 'string') refs.push({ entityType: rule.reference, entityId: value });
  }
  return refs;
}

export function canonicalJson(value: unknown): string {
  const normalize = (item: unknown): unknown => {
    if (Array.isArray(item)) return item.map(normalize);
    if (item && typeof item === 'object') {
      return Object.fromEntries(Object.keys(item as Record<string, unknown>).sort()
        .map(key => [key, normalize((item as Record<string, unknown>)[key])]));
    }
    return item;
  };
  return JSON.stringify(normalize(value));
}
