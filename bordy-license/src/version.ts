/**
 * Version strings are dotted numbers like "1.0" or "2.1.3".
 * Owners type them on Arabic keyboards, so Arabic-Indic digits (٠-٩) and the
 * Arabic decimal separator (٫) must be normalized before validation.
 */

export function normalizeVersionInput(input: string): string {
  return input
    .replace(/[٠-٩]/g, c => String.fromCharCode(c.charCodeAt(0) - 0x0660 + 0x0030))
    .replace(/[۰-۹]/g, c => String.fromCharCode(c.charCodeAt(0) - 0x06F0 + 0x0030))
    .replace(/[٫,]/g, '.')
    .trim();
}

export function isValidVersion(input: string): boolean {
  return /^\d+(\.\d+){0,3}$/.test(normalizeVersionInput(input));
}

/** Canonical form: normalized, no leading zeros per segment ("01.0" -> "1.0"). */
export function canonicalVersion(input: string): string {
  return normalizeVersionInput(input)
    .split('.')
    .map(seg => String(parseInt(seg, 10)))
    .join('.');
}

/** Numeric dotted comparison: "1" == "1.0", "1.9" < "1.10". Returns negative if a < b. */
export function compareVersions(a: string, b: string): number {
  const pa = canonicalVersion(a).split('.').map(s => parseInt(s, 10));
  const pb = canonicalVersion(b).split('.').map(s => parseInt(s, 10));
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const x = pa[i] ?? 0;
    const y = pb[i] ?? 0;
    if (x !== y) return x - y;
  }
  return 0;
}
