import { describe, expect, it } from 'vitest'
import { emptyFilters, filtersFromParams, filtersToApiQuery, filtersToParams, PAGE_SIZE, updateFilters } from './filters'

describe('repair filters', () => {
  it('round-trips through the URL and leaves defaults out', () => {
    const f = { q: 'SO-482917', status: 'QUOTE_PENDING', category: 'PHONE', from: '2026-10-01', to: '2026-10-21', page: 3 }
    const params = filtersToParams(f)
    expect(params.toString()).toBe('q=SO-482917&status=QUOTE_PENDING&category=PHONE&from=2026-10-01&to=2026-10-21&page=3')
    expect(filtersFromParams(params)).toEqual(f)
    expect(filtersToParams(emptyFilters).toString()).toBe('')
  })

  it('ignores a bad page number', () => {
    expect(filtersFromParams(new URLSearchParams('page=-2')).page).toBe(1)
    expect(filtersFromParams(new URLSearchParams('page=abc')).page).toBe(1)
  })

  it('maps the active and delayed views to their own API flags', () => {
    expect(filtersToApiQuery({ ...emptyFilters, status: 'active' })).toMatchObject({ active: true, status: undefined, delayed: undefined })
    expect(filtersToApiQuery({ ...emptyFilters, status: 'delayed' })).toMatchObject({ delayed: true, status: undefined, active: undefined })
    expect(filtersToApiQuery({ ...emptyFilters, status: 'AT_SHOP', q: '  dell ' })).toMatchObject({ status: 'AT_SHOP', q: 'dell' })
  })

  it('pages by offset', () => {
    expect(filtersToApiQuery({ ...emptyFilters, page: 4 })).toMatchObject({ limit: PAGE_SIZE, offset: 3 * PAGE_SIZE })
  })

  it('goes back to page 1 when any other filter changes', () => {
    const onPage3 = { ...emptyFilters, page: 3 }
    expect(updateFilters(onPage3, { category: 'LAPTOP' }).page).toBe(1)
    expect(updateFilters(onPage3, { page: 4 }).page).toBe(4)
  })
})
