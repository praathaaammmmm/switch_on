import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { AuditTable } from './Audit'
import { meFor, mockApi, renderApp } from '../test/utils'

describe('audit table', () => {
  it('shows who changed what and expands to before and after', async () => {
    mockApi(({ path }) => (path === '/me' ? { body: meFor('VIEWER') } : undefined))
    renderApp(<AuditTable rows={[{
      id: 7, adminEmail: 'ops@switchon.test', action: 'pricing.service_update', entityType: 'service', entityId: 'PHONE/CHARGING',
      reason: 'Supplier cost up', before: { basePrice: 699 }, after: { basePrice: 799 }, at: '2026-10-21T06:30:00Z',
    }]} />)
    expect(screen.getByText('pricing.service_update')).toBeInTheDocument()
    expect(screen.getByText('Supplier cost up')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'service · PHONE/CHARGING' })).toHaveAttribute('href', '/pricing')
    await userEvent.click(screen.getByText('ops@switchon.test'))
    expect(screen.getByText(/"basePrice": 699/)).toBeInTheDocument()
    expect(screen.getByText(/"basePrice": 799/)).toBeInTheDocument()
  })
})
