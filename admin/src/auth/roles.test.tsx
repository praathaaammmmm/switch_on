import { screen, waitFor } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { Sidebar } from '../components/Layout'
import { Can } from './AuthContext'
import { meFor, mockApi, renderApp } from '../test/utils'
import type { Role } from '../api/types'

const overview = { delayedRepairs: 2, draftQuotes: 0, unreconciledPayments: 1, openClaims: 0, unassignedTickets: 3 }

function setup(role: Role) {
  mockApi(({ path }) => (path === '/me' ? { body: meFor(role) } : path === '/overview' ? { body: overview } : undefined))
  renderApp(<><Sidebar /><Can permission="pricing.manage"><button>Change price</button></Can><Can permission="payments.manage"><button>Refund</button></Can></>)
}

describe('role-based controls', () => {
  it('owners see Team & Access and every control', async () => {
    setup('OWNER')
    expect(await screen.findByRole('link', { name: /team & access/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Change price' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Refund' })).toBeInTheDocument()
  })

  it('viewers can browse but get no team page and no change controls', async () => {
    setup('VIEWER')
    expect(await screen.findByRole('link', { name: /audit log/i })).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByRole('link', { name: /team & access/i })).not.toBeInTheDocument())
    expect(screen.queryByRole('button', { name: 'Change price' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Refund' })).not.toBeInTheDocument()
  })

  it('finance can refund but not change prices', async () => {
    setup('FINANCE')
    expect(await screen.findByRole('button', { name: 'Refund' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Change price' })).not.toBeInTheDocument()
  })

  it('shows attention counts from the overview', async () => {
    setup('OPERATIONS')
    expect(await screen.findByLabelText('3 need attention')).toBeInTheDocument()
    expect(screen.getByLabelText('2 need attention')).toBeInTheDocument()
  })
})
