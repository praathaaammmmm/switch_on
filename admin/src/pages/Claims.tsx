import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api, errorMessage } from '../api/client'
import type { Claim } from '../api/types'
import { useCan } from '../auth/AuthContext'
import { dateOnly, dateTime } from '../lib/format'
import { Button, Dialog, Empty, Field, KV, PageHead, QueryView, StatusTag, Tabs, useAction } from '../components/ui'

type Filter = 'live' | 'OPEN' | 'INFO_REQUESTED' | 'APPROVED' | 'RESOLVED' | 'REJECTED' | ''
const LIVE = ['OPEN', 'INFO_REQUESTED', 'APPROVED']

export function ClaimTable({ rows, onOpen }: { rows: Claim[]; onOpen?: (c: Claim) => void }) {
  return (
    <div className="table-wrap">
      <table className="data">
        <thead><tr><th>Opened</th><th>Customer</th><th>Problem</th><th>Original</th><th>Claim job</th><th>Claim</th>{onOpen && <th />}</tr></thead>
        <tbody>
          {rows.map((c) => (
            <tr key={c.id}>
              <td className="small">{dateTime(c.openedAt)}</td>
              <td>{c.customerName ?? 'Guest'}</td>
              <td>{c.problem}</td>
              <td><Link className="mono" to={`/repairs/${c.originalRepairId}`}>{c.originalRepairId}</Link></td>
              <td><Link className="mono" to={`/repairs/${c.claimRepairId}`}>{c.claimRepairId}</Link><div><StatusTag status={c.claimRepairStatus} /></div></td>
              <td><StatusTag status={c.status} /></td>
              {onOpen && <td className="right"><Button small onClick={() => onOpen(c)}>Review</Button></td>}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

export function ClaimsPage() {
  const [filter, setFilter] = useState<Filter>('live')
  const [open, setOpen] = useState<Claim | null>(null)
  const q = useQuery({ queryKey: ['claims', filter], queryFn: () => api<Claim[]>('/claims', { query: { status: filter === 'live' ? '' : filter } }) })
  return (
    <>
      <PageHead eyebrow="90-day cover on parts and labour" title="Warranty Claims" />
      <Tabs label="Claim status" value={filter} onChange={setFilter} options={[
        { value: 'live', label: 'Needs review' }, { value: 'INFO_REQUESTED', label: 'Info requested' }, { value: 'APPROVED', label: 'Approved' },
        { value: 'RESOLVED', label: 'Resolved' }, { value: 'REJECTED', label: 'Rejected' }, { value: '', label: 'All' },
      ]} />
      <QueryView q={q}>
        {(rows) => {
          const list = filter === 'live' ? rows.filter((c) => LIVE.includes(c.status)) : rows
          return list.length === 0 ? <Empty title="No claims here" /> : <ClaimTable rows={list} onOpen={setOpen} />
        }}
      </QueryView>
      {open && <ClaimDialog claim={open} onClose={() => setOpen(null)} />}
    </>
  )
}

type Action = 'approve' | 'request-info' | 'reject' | 'resolve'

function ClaimDialog({ claim, onClose }: { claim: Claim; onClose: () => void }) {
  const can = useCan()('claims.manage')
  const [action, setAction] = useState<Action | null>(null)
  const [note, setNote] = useState('')
  const run = useAction(() => api<Claim>(`/claims/${claim.id}/${action}`, { method: 'POST', body: { note: note.trim() } }), {
    invalidate: [['claims'], ['overview'], ['repair', claim.claimRepairId]], success: (c) => `Claim ${c.status.toLowerCase().replace('_', ' ')}.`, onSuccess: onClose,
  })
  const live = LIVE.includes(claim.status)
  const needsNote = action === 'reject' || action === 'request-info'
  const labels: Record<Action, string> = { approve: 'Approve', 'request-info': 'Request information', reject: 'Reject', resolve: 'Resolve' }
  return (
    <Dialog title={`Claim · ${claim.problem}`} onClose={onClose} wide>
      <KV rows={[
        ['Status', <StatusTag status={claim.status} />],
        ['Customer', <Link to={`/customers/${claim.customerId}`}>{claim.customerName ?? 'Guest'}</Link>],
        ['Details', claim.detail || '—'],
        ['Original repair', <Link to={`/repairs/${claim.originalRepairId}`}>{claim.originalRepairId}</Link>],
        ['Cover ends', dateOnly(claim.warrantyEndsOn)],
        ['Claim job', <span><Link to={`/repairs/${claim.claimRepairId}`}>{claim.claimRepairId}</Link> · <StatusTag status={claim.claimRepairStatus} /></span>],
        ['Decision note', claim.decisionNote ?? '—'],
      ]} />
      {can && live && (
        <>
          <div className="row">
            {(['approve', 'request-info', 'reject', 'resolve'] as Action[])
              .filter((a) => !(a === 'approve' && claim.status === 'APPROVED') && !(a === 'request-info' && claim.status === 'INFO_REQUESTED'))
              .map((a) => <Button key={a} small kind={action === a ? 'paper' : a === 'reject' ? 'danger' : 'outline'} onClick={() => setAction(a)}>{labels[a]}</Button>)}
          </div>
          {action && (
            <>
              <Field label={action === 'request-info' ? 'Question for the customer (required)' : action === 'reject' ? 'Reason the customer will see (required)' : 'Note (optional)'}
                hint={action === 'reject' ? 'A claim job not yet picked up is cancelled; one at the shop is returned unrepaired, free.' : action === 'resolve' ? 'Resolve once the claim job is delivered or cancelled.' : undefined}>
                {(id) => <textarea id={id} className="textarea" value={note} onChange={(e) => setNote(e.target.value)} maxLength={300} />}
              </Field>
              {run.error && <p className="error-text">{errorMessage(run.error)}</p>}
              <div className="row"><Button kind={action === 'reject' ? 'danger' : 'primary'} disabled={needsNote && note.trim().length < 5} busy={run.isPending} onClick={() => run.mutate(undefined)}>{labels[action]}</Button></div>
            </>
          )}
        </>
      )}
    </Dialog>
  )
}
