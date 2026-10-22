import type { ReactElement } from 'react'
import { render } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { vi } from 'vitest'
import { AuthProvider } from '../auth/AuthContext'
import { ToastProvider } from '../components/ui'
import type { AdminMe, Role } from '../api/types'

export const ROLE_PERMISSIONS: Record<Role, AdminMe['permissions']> = {
  OWNER: ['repairs.manage', 'repairs.note', 'pricing.manage', 'partners.manage', 'areas.manage', 'quotes.manage', 'payments.manage', 'claims.manage', 'support.manage', 'team.manage'],
  OPERATIONS: ['repairs.manage', 'repairs.note', 'pricing.manage', 'partners.manage', 'areas.manage', 'quotes.manage', 'claims.manage', 'support.manage'],
  SUPPORT: ['repairs.note', 'claims.manage', 'support.manage'],
  FINANCE: ['payments.manage'],
  VIEWER: [],
}

export const meFor = (role: Role): AdminMe => ({ id: 'u1', email: `${role.toLowerCase()}@switchon.test`, name: `${role} Tester`, role, permissions: ROLE_PERMISSIONS[role] })

export interface Call { method: string; path: string; query: URLSearchParams; body: unknown; headers: Record<string, string> }
type Handler = (call: Call) => { status?: number; body?: unknown } | undefined

/** Replaces fetch with a router over admin API paths. Unmatched calls get a 404. Returns the recorded calls. */
export function mockApi(handler: Handler) {
  const calls: Call[] = []
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const url = new URL(String(input), 'http://localhost')
    const call: Call = {
      method: init?.method ?? 'GET', path: url.pathname.replace('/v1/admin', ''), query: url.searchParams,
      body: init?.body ? JSON.parse(String(init.body)) : undefined, headers: (init?.headers ?? {}) as Record<string, string>,
    }
    calls.push(call)
    const res = handler(call) ?? { status: 404, body: { error: { code: 'not_found', message: 'Not found.' } } }
    const status = res.status ?? 200
    return new Response(status === 204 ? null : JSON.stringify(res.body ?? {}), { status, headers: { 'Content-Type': 'application/json' } })
  })
  return calls
}

export function renderApp(ui: ReactElement, { route = '/', path = '*' }: { route?: string; path?: string } = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[route]}>
        <AuthProvider>
          <ToastProvider>
            <Routes><Route path={path} element={ui} /><Route path="*" element={<div>elsewhere</div>} /></Routes>
          </ToastProvider>
        </AuthProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}
