// Display helpers. Money is whole rupees; times are shown in Asia/Kolkata like the customer app.

const IST = 'Asia/Kolkata'
const inrFormat = new Intl.NumberFormat('en-IN')

export const inr = (n: number) => `₹${inrFormat.format(n)}`

export function dateTime(iso: string | null | undefined): string {
  if (!iso) return '—'
  return new Intl.DateTimeFormat('en-IN', { timeZone: IST, day: '2-digit', month: 'short', hour: 'numeric', minute: '2-digit' }).format(new Date(iso))
}

export function dateOnly(isoOrDate: string | null | undefined): string {
  if (!isoOrDate) return '—'
  // Plain dates (2026-10-22) are calendar days already; don't shift them through a timezone.
  const d = /^\d{4}-\d{2}-\d{2}$/.test(isoOrDate) ? new Date(`${isoOrDate}T12:00:00+05:30`) : new Date(isoOrDate)
  return new Intl.DateTimeFormat('en-IN', { timeZone: IST, day: '2-digit', month: 'short', year: 'numeric' }).format(d)
}

export const hour12 = (h: number) => `${((h + 11) % 12) + 1} ${h % 24 < 12 ? 'AM' : 'PM'}`
export const slotLabel = (date: string, startHour: number) => `${dateOnly(date)} · ${hour12(startHour)}–${hour12(startHour + 2)}`

/** Today's date in IST as yyyy-mm-dd. */
export function todayIst(now = new Date()): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: IST, year: 'numeric', month: '2-digit', day: '2-digit' }).format(now)
}

export const titleCase = (s: string) => s.toLowerCase().replace(/(^|[\s_])\w/g, (m) => m.toUpperCase()).replace(/_/g, ' ')

export const device = (r: { deviceCategory: string; issue: string; brand: string | null; model: string | null }) =>
  [titleCase(r.deviceCategory), titleCase(r.issue)].join(' · ') + (r.brand ? ` · ${r.brand}${r.model ? ' ' + r.model : ''}` : '')

/** Status → colour family for tags. */
export function statusTone(status: string): 'alert' | 'done' | 'muted' | 'active' {
  if (['QUOTE_PENDING', 'PICKUP_FAILED', 'PAYMENT_PENDING', 'REPAIR_NOT_POSSIBLE', 'QUOTE_DECLINED'].includes(status)) return 'alert'
  if (['DELIVERED', 'PAYMENT_COMPLETED', 'WARRANTY_ACTIVE', 'RESOLVED', 'CLOSED', 'APPROVED'].includes(status)) return 'done'
  if (['CANCELLED', 'DRAFT', 'REJECTED', 'DECLINED'].includes(status)) return 'muted'
  return 'active'
}
