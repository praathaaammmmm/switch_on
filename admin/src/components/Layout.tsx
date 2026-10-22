import { useState } from 'react'
import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { api, errorMessage } from '../api/client'
import type { Overview, PermissionCode } from '../api/types'
import { ME_KEY, useAuth } from '../auth/AuthContext'
import { Button, Dialog, Field, useAction } from './ui'

interface NavItem { to: string; label: string; count?: (o: Overview) => number; permission?: PermissionCode }

export const NAV: NavItem[] = [
  { to: '/', label: 'Overview' },
  { to: '/repairs', label: 'Repairs', count: (o) => o.delayedRepairs },
  { to: '/customers', label: 'Customers' },
  { to: '/pricing', label: 'Services & Pricing' },
  { to: '/partners', label: 'Partners' },
  { to: '/logistics', label: 'Pickups & Areas' },
  { to: '/quotes', label: 'Quotes', count: (o) => o.draftQuotes },
  { to: '/payments', label: 'Payments', count: (o) => o.unreconciledPayments },
  { to: '/claims', label: 'Warranty Claims', count: (o) => o.openClaims },
  { to: '/support', label: 'Support', count: (o) => o.unassignedTickets },
  { to: '/team', label: 'Team & Access', permission: 'team.manage' },
  { to: '/audit', label: 'Audit Log' },
]

export function Sidebar() {
  const { can } = useAuth()
  const overview = useQuery({ queryKey: ['overview'], queryFn: () => api<Overview>('/overview'), refetchInterval: 60_000 })
  return (
    <aside className="sidebar">
      <NavLink to="/" className="brand" aria-label="Switch On admin, overview">
        <span className="brand-mark" aria-hidden />
        <span className="display">Switch On</span>
        <span className="tag muted">Admin</span>
      </NavLink>
      <nav className="nav" aria-label="Sections">
        {NAV.filter((n) => !n.permission || can(n.permission)).map((n) => {
          const count = overview.data && n.count ? n.count(overview.data) : 0
          return (
            <NavLink key={n.to} to={n.to} end={n.to === '/'}>
              {n.label}
              {count > 0 && <span className="count" aria-label={`${count} need attention`}>{count}</span>}
            </NavLink>
          )
        })}
      </nav>
      <div className="sidebar-foot">
        <span className="label">Times shown in IST</span>
      </div>
    </aside>
  )
}

function PasswordDialog({ onClose }: { onClose: () => void }) {
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const change = useAction(() => api('/me/password', { method: 'POST', body: { current, new: next } }), { success: 'Password changed. Other sessions were signed out.', onSuccess: onClose })
  return (
    <Dialog title="Change password" onClose={onClose}>
      <Field label="Current password">{(id) => <input id={id} className="input" type="password" autoComplete="current-password" value={current} onChange={(e) => setCurrent(e.target.value)} />}</Field>
      <Field label="New password" hint="At least 12 characters.">{(id) => <input id={id} className="input" type="password" autoComplete="new-password" value={next} onChange={(e) => setNext(e.target.value)} />}</Field>
      {change.error && <p className="error-text">{errorMessage(change.error)}</p>}
      <div className="row"><Button kind="primary" disabled={!current || next.length < 12} busy={change.isPending} onClick={() => change.mutate(undefined)}>Change password</Button></div>
    </Dialog>
  )
}

export function Layout() {
  const { me } = useAuth()
  const qc = useQueryClient()
  const navigate = useNavigate()
  const [pw, setPw] = useState(false)
  const signOut = async () => {
    await api('/session', { method: 'DELETE' }).catch(() => undefined)
    qc.clear()
    qc.setQueryData(ME_KEY, null)
    navigate('/login')
  }
  return (
    <div className="shell">
      <Sidebar />
      <div className="main">
        <header className="topbar">
          <span className="label">Operations console</span>
          <span className="spacer" />
          <span className="small">{me?.name} · <span className="mono muted">{me?.email}</span></span>
          <span className="tag">{me?.role}</span>
          <button className="link" onClick={() => setPw(true)}>Password</button>
          <Button small kind="ghost" onClick={signOut}>Sign out</Button>
        </header>
        <main className="page" id="main">
          <Outlet />
        </main>
      </div>
      {pw && <PasswordDialog onClose={() => setPw(false)} />}
    </div>
  )
}
