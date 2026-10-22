import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api, errorMessage } from '../api/client'
import type { AdminMeta, AdminUser, Role } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { dateTime } from '../lib/format'
import { Button, Dialog, Field, PageHead, Panel, QueryView, Tag, useAction } from '../components/ui'

const ROLES: { value: Role; label: string; text: string }[] = [
  { value: 'OWNER', label: 'Owner', text: 'Everything, including the team and pricing.' },
  { value: 'OPERATIONS', label: 'Operations', text: 'Repairs, assignment, quotes, partners, pickups, pricing, claims and support.' },
  { value: 'SUPPORT', label: 'Support', text: 'Tickets, claims and internal notes on repairs.' },
  { value: 'FINANCE', label: 'Finance', text: 'Refunds and reconciliation.' },
  { value: 'VIEWER', label: 'Viewer', text: 'Read-only.' },
]

export function TeamPage() {
  const { me } = useAuth()
  const q = useQuery({ queryKey: ['team'], queryFn: () => api<AdminUser[]>('/team') })
  const meta = useQuery({ queryKey: ['meta'], queryFn: () => api<AdminMeta>('/meta'), staleTime: Infinity })
  const [editing, setEditing] = useState<AdminUser | 'new' | null>(null)
  const [resetting, setResetting] = useState<AdminUser | null>(null)
  return (
    <>
      <PageHead eyebrow="Staff accounts and roles" title="Team & Access"><Button kind="primary" onClick={() => setEditing('new')}>Add team member</Button></PageHead>
      <QueryView q={q}>
        {(users) => (
          <div className="table-wrap">
            <table className="data">
              <thead><tr><th>Name</th><th>Email</th><th>Role</th><th>Status</th><th>Last sign-in</th><th /></tr></thead>
              <tbody>
                {users.map((u) => (
                  <tr key={u.id}>
                    <td>{u.name}{u.id === me?.id && <span className="muted small"> (you)</span>}</td>
                    <td className="mono small">{u.email}</td>
                    <td><Tag>{u.role}</Tag></td>
                    <td>{u.active ? <Tag tone="done">Active</Tag> : <Tag tone="muted">Deactivated</Tag>}</td>
                    <td className="small">{dateTime(u.lastLoginAt)}</td>
                    <td className="right"><div className="row" style={{ justifyContent: 'flex-end' }}>
                      <Button small onClick={() => setEditing(u)}>Edit</Button>
                      <Button small kind="ghost" onClick={() => setResetting(u)}>Set password</Button>
                    </div></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </QueryView>
      <Panel title="What each role can do">
        <div className="table-wrap">
          <table className="data">
            <thead><tr><th>Role</th><th>Summary</th><th>Permissions</th></tr></thead>
            <tbody>{ROLES.map((r) => <tr key={r.value}><td><Tag>{r.label}</Tag></td><td className="small">{r.text}</td><td className="mono small">{meta.data?.roles[r.value]?.join(', ') || 'read only'}</td></tr>)}</tbody>
          </table>
        </div>
        <span className="hint">Every role can read the dashboard except this page. There is always at least one active owner, and nobody can change their own role.</span>
      </Panel>
      {editing && <UserForm user={editing === 'new' ? null : editing} self={editing !== 'new' && editing.id === me?.id} onClose={() => setEditing(null)} />}
      {resetting && <PasswordForm user={resetting} onClose={() => setResetting(null)} />}
    </>
  )
}

function UserForm({ user, self, onClose }: { user: AdminUser | null; self: boolean; onClose: () => void }) {
  const [email, setEmail] = useState(user?.email ?? '')
  const [name, setName] = useState(user?.name ?? '')
  const [role, setRole] = useState<Role>(user?.role ?? 'SUPPORT')
  const [active, setActive] = useState(user?.active ?? true)
  const [password, setPassword] = useState('')
  const save = useAction(() => user
    ? api<AdminUser>(`/team/${user.id}`, { method: 'PATCH', body: { name, role, active } })
    : api<AdminUser>('/team', { method: 'POST', body: { email, name, role, password } }), { invalidate: [['team'], ['staff']], success: user ? 'Team member updated.' : 'Team member added.', onSuccess: onClose })
  return (
    <Dialog title={user ? `Edit ${user.name}` : 'Add team member'} onClose={onClose}>
      {!user && <Field label="Work email">{(id) => <input id={id} className="input" type="email" value={email} onChange={(e) => setEmail(e.target.value)} />}</Field>}
      <Field label="Name">{(id) => <input id={id} className="input" value={name} onChange={(e) => setName(e.target.value)} />}</Field>
      <Field label="Role" hint={self ? "You can't change your own role." : ROLES.find((r) => r.value === role)?.text}>
        {(id) => <select id={id} className="select" value={role} disabled={self} onChange={(e) => setRole(e.target.value as Role)}>{ROLES.map((r) => <option key={r.value} value={r.value}>{r.label}</option>)}</select>}
      </Field>
      {!user && <Field label="Temporary password" hint="At least 12 characters. Share it privately; they can change it after signing in.">{(id) => <input id={id} className="input" type="password" autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} />}</Field>}
      {user && !self && <label className="check"><input type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} /> Active (deactivating signs them out everywhere)</label>}
      {save.error && <p className="error-text">{errorMessage(save.error)}</p>}
      <div className="row"><Button kind="primary" disabled={!name.trim() || (!user && (!email.trim() || password.length < 12))} busy={save.isPending} onClick={() => save.mutate(undefined)}>Save</Button></div>
    </Dialog>
  )
}

function PasswordForm({ user, onClose }: { user: AdminUser; onClose: () => void }) {
  const [password, setPassword] = useState('')
  const save = useAction(() => api<AdminUser>(`/team/${user.id}/password`, { method: 'POST', body: { password } }), { success: 'Password set. Their other sessions were signed out.', onSuccess: onClose })
  return (
    <Dialog title={`Set password · ${user.name}`} onClose={onClose}>
      <Field label="New password" hint="At least 12 characters.">{(id) => <input id={id} className="input" type="password" autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} />}</Field>
      {save.error && <p className="error-text">{errorMessage(save.error)}</p>}
      <div className="row"><Button kind="primary" disabled={password.length < 12} busy={save.isPending} onClick={() => save.mutate(undefined)}>Set password</Button></div>
    </Dialog>
  )
}
