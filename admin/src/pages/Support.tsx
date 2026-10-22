import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import type { StaffOption, TicketDetail, TicketSummary } from '../api/types'
import { useCan } from '../auth/AuthContext'
import { dateTime } from '../lib/format'
import { Button, Empty, Field, KV, PageHead, Panel, QueryView, StatusTag, Tabs, Tag, useAction } from '../components/ui'

export function TicketTable({ rows }: { rows: TicketSummary[] }) {
  const navigate = useNavigate()
  return (
    <div className="table-wrap">
      <table className="data">
        <thead><tr><th>Ticket</th><th>Topic</th><th>Customer</th><th>Repair</th><th>Assigned</th><th>Status</th><th>Updated</th></tr></thead>
        <tbody>
          {rows.map((t) => (
            <tr key={t.id} className="clickable" onClick={() => navigate(`/support/${t.id}`)}>
              <td><Link className="mono" to={`/support/${t.id}`} onClick={(e) => e.stopPropagation()}>{t.id}</Link></td>
              <td>{t.topic}<div className="small muted">{t.messages} messages</div></td>
              <td>{t.customerName ?? 'Guest'}</td>
              <td className="mono small">{t.repairId ?? '—'}</td>
              <td>{t.assignedName ?? <Tag tone="delay">Unassigned</Tag>}</td>
              <td><StatusTag status={t.status} /></td>
              <td className="small">{dateTime(t.updatedAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

type Assigned = '' | 'me' | 'unassigned'

export function SupportPage() {
  const [status, setStatus] = useState<'OPEN' | 'CLOSED' | ''>('OPEN')
  const [assigned, setAssigned] = useState<Assigned>('')
  const [search, setSearch] = useState('')
  const q = useQuery({ queryKey: ['tickets', status, assigned, search], queryFn: () => api<TicketSummary[]>('/tickets', { query: { status, assigned, q: search } }) })
  return (
    <>
      <PageHead eyebrow="Customer tickets from the app" title="Support" />
      <div className="toolbar">
        <Tabs label="Ticket status" value={status} onChange={setStatus} options={[{ value: 'OPEN', label: 'Open' }, { value: 'CLOSED', label: 'Closed' }, { value: '', label: 'All' }]} />
        <Tabs label="Assignment" value={assigned} onChange={setAssigned} options={[{ value: '', label: 'Everyone' }, { value: 'me', label: 'Mine' }, { value: 'unassigned', label: 'Unassigned' }]} />
        <Field label="Search" className="search">{(id) => <input id={id} className="input" type="search" value={search} onChange={(e) => setSearch(e.target.value)} placeholder="Ticket, topic, repair or customer" />}</Field>
      </div>
      <QueryView q={q}>{(rows) => rows.length === 0 ? <Empty title="No tickets here" /> : <TicketTable rows={rows} />}</QueryView>
    </>
  )
}

export function TicketPage() {
  const { id = '' } = useParams()
  const can = useCan()('support.manage')
  const q = useQuery({ queryKey: ['ticket', id], queryFn: () => api<TicketDetail>(`/tickets/${id}`) })
  const staff = useQuery({ queryKey: ['staff'], queryFn: () => api<StaffOption[]>('/staff') })
  const [body, setBody] = useState('')
  const [mode, setMode] = useState<'replies' | 'notes'>('replies')
  const invalidate = [['ticket', id], ['tickets'], ['overview']]
  const send = useAction(() => api<TicketDetail>(`/tickets/${id}/${mode}`, { method: 'POST', body: { body: body.trim() } }), {
    invalidate, success: mode === 'replies' ? 'Reply sent to the customer.' : 'Internal note added.', onSuccess: () => setBody(''),
  })
  const assign = useAction((adminId: string) => api<TicketDetail>(`/tickets/${id}/assign`, { method: 'POST', body: { adminId: adminId || null } }), { invalidate, success: 'Assignment updated.' })
  const setStatus = useAction((status: string) => api<TicketDetail>(`/tickets/${id}/status`, { method: 'POST', body: { status } }), { invalidate, success: (t) => `Ticket ${t.ticket.status.toLowerCase()}.` })
  return (
    <QueryView q={q}>
      {({ ticket: t, detail, messages }) => (
        <>
          <PageHead eyebrow={`Support ticket · ${t.topic}`} title={t.id}>
            <div className="row"><StatusTag status={t.status} />
              {can && <Button kind={t.status === 'OPEN' ? 'paper' : 'outline'} busy={setStatus.isPending} onClick={() => setStatus.mutate(t.status === 'OPEN' ? 'CLOSED' : 'OPEN')}>{t.status === 'OPEN' ? 'Close ticket' : 'Reopen'}</Button>}
            </div>
          </PageHead>
          <div className="grid-2">
            <div className="stack">
              <Panel title="Conversation">
                <div className="message customer-msg">
                  <span style={{ whiteSpace: 'pre-wrap' }}>{detail}</span>
                  <span className="label">{t.customerName ?? 'Customer'} · {dateTime(t.createdAt)}</span>
                </div>
                {messages.map((m) => (
                  <div key={m.id} className={`message ${m.visibility === 'INTERNAL' ? 'internal' : ''}`}>
                    <div className="row"><Tag tone={m.visibility === 'INTERNAL' ? 'muted' : 'active'}>{m.visibility === 'INTERNAL' ? 'Internal note' : 'Reply to customer'}</Tag></div>
                    <span style={{ whiteSpace: 'pre-wrap' }}>{m.body}</span>
                    <span className="label">{m.author} · {dateTime(m.at)}</span>
                  </div>
                ))}
                {can && (
                  <>
                    <Tabs label="Message type" value={mode} onChange={setMode} options={[{ value: 'replies', label: 'Reply to customer' }, { value: 'notes', label: 'Internal note' }]} />
                    <Field label={mode === 'replies' ? 'Reply' : 'Note'} hint={mode === 'replies' ? (t.repairId ? `The customer sees this in the app, on the ticket and on ${t.repairId}'s timeline.` : 'The customer sees this in the app.') : 'Only staff see internal notes.'}>
                      {(fid) => <textarea id={fid} className="textarea" value={body} onChange={(e) => setBody(e.target.value)} maxLength={2000} />}
                    </Field>
                    <div className="row"><Button kind={mode === 'replies' ? 'primary' : 'paper'} disabled={!body.trim() || (mode === 'replies' && t.status === 'CLOSED')} busy={send.isPending} onClick={() => send.mutate(undefined)}>{mode === 'replies' ? 'Send reply' : 'Add note'}</Button>
                      {mode === 'replies' && t.status === 'CLOSED' && <span className="hint">Reopen the ticket to reply.</span>}</div>
                  </>
                )}
              </Panel>
            </div>
            <Panel title="Ticket">
              <KV rows={[
                ['Customer', <Link to={`/customers/${t.customerId}`}>{t.customerName ?? 'Guest'}</Link>],
                ['Repair', t.repairId ? <Link className="mono" to={`/repairs/${t.repairId}`}>{t.repairId}</Link> : '—'],
                ['Opened', dateTime(t.createdAt)],
                ['Updated', dateTime(t.updatedAt)],
              ]} />
              <Field label="Assigned to">
                {(fid) => (
                  <select id={fid} className="select" value={t.assignedTo ?? ''} disabled={!can || assign.isPending} onChange={(e) => assign.mutate(e.target.value)}>
                    <option value="">Unassigned</option>
                    {staff.data?.map((s) => <option key={s.id} value={s.id}>{s.name} · {s.role.toLowerCase()}</option>)}
                  </select>
                )}
              </Field>
            </Panel>
          </div>
        </>
      )}
    </QueryView>
  )
}
