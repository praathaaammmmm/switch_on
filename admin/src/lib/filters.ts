// The repair list's filters, kept in the URL so a filtered view can be shared and survives a reload.

export interface RepairFilters {
  q: string
  status: string // a status code, or one of the views below
  category: string
  from: string
  to: string
  page: number
}

export const PAGE_SIZE = 25
export const VIEWS = { ACTIVE: 'active', DELAYED: 'delayed' } as const

export const emptyFilters: RepairFilters = { q: '', status: '', category: '', from: '', to: '', page: 1 }

export function filtersFromParams(p: URLSearchParams): RepairFilters {
  const page = Number.parseInt(p.get('page') ?? '1', 10)
  return {
    q: p.get('q') ?? '',
    status: p.get('status') ?? '',
    category: p.get('category') ?? '',
    from: p.get('from') ?? '',
    to: p.get('to') ?? '',
    page: Number.isFinite(page) && page > 0 ? page : 1,
  }
}

/** URL parameters for [f], leaving out defaults so links stay short. */
export function filtersToParams(f: RepairFilters): URLSearchParams {
  const p = new URLSearchParams()
  if (f.q.trim()) p.set('q', f.q.trim())
  if (f.status) p.set('status', f.status)
  if (f.category) p.set('category', f.category)
  if (f.from) p.set('from', f.from)
  if (f.to) p.set('to', f.to)
  if (f.page > 1) p.set('page', String(f.page))
  return p
}

/** The query the admin API expects for [f]. The two views become their own flags rather than a status. */
export function filtersToApiQuery(f: RepairFilters) {
  return {
    q: f.q.trim() || undefined,
    status: f.status && f.status !== VIEWS.ACTIVE && f.status !== VIEWS.DELAYED ? f.status : undefined,
    active: f.status === VIEWS.ACTIVE || undefined,
    delayed: f.status === VIEWS.DELAYED || undefined,
    category: f.category || undefined,
    from: f.from || undefined,
    to: f.to || undefined,
    limit: PAGE_SIZE,
    offset: (f.page - 1) * PAGE_SIZE,
  }
}

/** Any change other than paging sends you back to page 1. */
export function updateFilters(f: RepairFilters, change: Partial<RepairFilters>): RepairFilters {
  return { ...f, ...change, page: 'page' in change ? change.page ?? 1 : 1 }
}
