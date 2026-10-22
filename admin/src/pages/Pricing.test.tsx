import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { PricingPage } from './Pricing'
import { meFor, mockApi, renderApp } from '../test/utils'
import type { Role } from '../api/types'

const entry = { category: 'PHONE', issue: 'CHARGING', label: 'Charging', basePrice: 699, diagnosisFee: 0, turnaround: '24–48 hours', labelOverride: null, active: true, updatedAt: '2026-10-01T00:00:00Z', bookings: 4 }

function setup(role: Role) {
  return mockApi(({ path, method }) => {
    if (path === '/me') return { body: meFor(role) }
    if (path === '/catalog') return { body: { categories: [{ code: 'PHONE', label: 'Phone', sortOrder: 0, active: true }], issues: [{ code: 'CHARGING', label: 'Charging', hint: '', sortOrder: 2 }], entries: [entry], brands: [] } }
    if (path === '/catalog/services/PHONE/CHARGING' && method === 'GET') return { body: { entry, estimates: [{ brand: null, low: 699, high: 1599 }], history: [] } }
    if (path === '/catalog/services/PHONE/CHARGING' && method === 'PATCH') return { body: { entry: { ...entry, basePrice: 799 }, estimates: [], history: [] } }
  })
}

describe('price changes', () => {
  it('need a reason before they can be saved, and send it', async () => {
    const calls = setup('OWNER')
    renderApp(<PricingPage />)
    await userEvent.click(await screen.findByText('Charging', { selector: 'button' }))
    const dialog = await screen.findByRole('dialog')
    const price = within(dialog).getByLabelText('Base price (₹)')
    await userEvent.clear(price)
    await userEvent.type(price, '799')
    const save = within(dialog).getByRole('button', { name: 'Save change' })
    expect(save).toBeDisabled()
    await userEvent.type(within(dialog).getByLabelText('Reason for this change (required)'), 'abc')
    expect(save).toBeDisabled()
    await userEvent.type(within(dialog).getByLabelText('Reason for this change (required)'), ' supplier cost')
    expect(save).toBeEnabled()
    await userEvent.click(save)
    await waitFor(() => expect(calls.some((c) => c.method === 'PATCH')).toBe(true))
    expect(calls.find((c) => c.method === 'PATCH')!.body).toEqual({ basePrice: 799, reason: 'abc supplier cost' })
  })

  it('are read-only for roles without pricing access', async () => {
    setup('SUPPORT')
    renderApp(<PricingPage />)
    await userEvent.click(await screen.findByText('Charging', { selector: 'button' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByLabelText('Base price (₹)')).toBeDisabled()
    expect(within(dialog).queryByRole('button', { name: 'Save change' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Offer a new repair' })).not.toBeInTheDocument()
  })
})
