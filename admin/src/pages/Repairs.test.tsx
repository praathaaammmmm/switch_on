import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { RepairsPage } from './Repairs'
import { meFor, mockApi, renderApp } from '../test/utils'
import type { RepairSummary } from '../api/types'

const repair = (id: string, extra: Partial<RepairSummary> = {}): RepairSummary => ({
  id, status: 'PICKUP_SCHEDULED', statusTitle: 'Pickup scheduled', deviceCategory: 'PHONE', issue: 'SCREEN', brand: 'Samsung', model: null,
  customerId: 'c1', customerName: 'Priya', contactName: 'Priya Sharma', contactPhone: '9845012873', city: 'Bengaluru', pincode: '560038',
  slotDate: '2026-10-22', slotStartHour: 13, estimateLow: 2299, estimateHigh: 3099, createdAt: '2026-10-21T04:00:00Z', updatedAt: '2026-10-21T04:00:00Z',
  partnerName: null, shopName: null, technicianName: null, delayed: false, delayReason: null, warrantyClaimFor: null, ...extra,
})

function setup(route = '/repairs') {
  const calls = mockApi(({ path, query }) => {
    if (path === '/me') return { body: meFor('OPERATIONS') }
    if (path === '/meta') return { body: { statuses: [{ code: 'AT_SHOP', title: 'At repair shop', next: [], beforePickup: false }], roles: {} } }
    if (path === '/catalog') return { body: { categories: [{ code: 'PHONE', label: 'Phone', sortOrder: 0, active: true }, { code: 'LAPTOP', label: 'Laptop', sortOrder: 1, active: true }], issues: [], entries: [], brands: [] } }
    if (path === '/repairs') {
      const items = query.get('delayed') === 'true' ? [repair('SO-200002', { delayed: true, delayReason: 'Pickup window ended without a pickup' })] : [repair('SO-100001'), repair('SO-200002')]
      return { body: { items: query.get('q') ? items.filter((r) => r.id.includes(query.get('q')!)) : items, total: items.length } }
    }
  })
  renderApp(<RepairsPage />, { route, path: '/repairs' })
  return calls
}

const repairCalls = (calls: ReturnType<typeof setup>) => calls.filter((c) => c.path === '/repairs')

describe('repair list filtering', () => {
  it('lists repairs and filters by search after a pause', async () => {
    const calls = setup()
    expect(await screen.findByText('SO-100001')).toBeInTheDocument()
    await userEvent.type(screen.getByLabelText('Search'), '1000')
    await waitFor(() => expect(repairCalls(calls).at(-1)!.query.get('q')).toBe('1000'))
    await waitFor(() => expect(screen.queryByText('SO-200002')).not.toBeInTheDocument())
    expect(screen.getByText('SO-100001')).toBeInTheDocument()
  })

  it('sends the delayed view as its own flag and shows the delay', async () => {
    const calls = setup()
    await screen.findByText('SO-100001')
    await userEvent.selectOptions(screen.getByLabelText('Status'), 'delayed')
    await waitFor(() => expect(repairCalls(calls).at(-1)!.query.get('delayed')).toBe('true'))
    expect(repairCalls(calls).at(-1)!.query.get('status')).toBeNull()
    expect(await screen.findByTitle('Pickup window ended without a pickup')).toHaveTextContent('Delayed')
  })

  it('reads filters from the URL so filtered views can be shared', async () => {
    const calls = setup('/repairs?category=LAPTOP&status=AT_SHOP&page=2')
    await waitFor(() => expect(repairCalls(calls).length).toBeGreaterThan(0))
    const q = repairCalls(calls)[0].query
    expect(q.get('category')).toBe('LAPTOP')
    expect(q.get('status')).toBe('AT_SHOP')
    expect(q.get('offset')).toBe('25')
  })
})
