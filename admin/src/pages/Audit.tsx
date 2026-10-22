import { Fragment, useState } from 'react'
import { Link } from 'react-router-dom'
import { useInfiniteQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import type { AuditEntry, AuditResponse } from '../api/types'
import { dateTime } from '../lib/format'
import { Button, Empty, Field, PageHead, QueryView } from '../components/ui'

const entityLink = (e: AuditEntry) => {
  switch (e.entityType) {
    case 'repair': case 'payment': return `/repairs/${e.entityId}`
    case 'ticket': return `/support/${e.entityId}`
    case 'service': case 'category': case 'issue': case 'brand': return '/pricing'
    case 'shop': case 'courier': case 'technician': return '/partners'
    case 'service_area': case 'slot_template': case 'slot_block': return '/logistics'
    case 'claim': return '/claims'
    default: return null
  }
}

const show = (v: unknown) => (v === null || v === undefined ? null : JSON.stringify(v, null, 1))

/** Audit rows, newest first. Expand a row to see the before and after values. */
export function AuditTable({ rows, compact }: { rows: AuditEntry[]; compact?: boolean }) {
  const [open, setOpen] = useState<number | null>(null)
  return (
    <div className="table-wrap">
      <table className="data">
        <thead><tr><th>When</th><th>Who</th><th>Action</th>{!compact && <th>Record</th>}<th>Reason</th></tr></thead>
        <tbody>
          {rows.map((e) => {
            const link = entityLink(e)
            const detail = show(e.before) || show(e.after)
            return (
              <Fragment key={e.id}>
                <tr className={detail ? 'clickable' : ''} onClick={() => detail && setOpen(open === e.id ? null : e.id)} aria-expanded={detail ? open === e.id : undefined}>
                  <td className="small mono">{dateTime(e.at)}</td>
                  <td className="small">{e.adminEmail}</td>
                  <td className="mono small">{e.action}</td>
                  {!compact && <td className="small">{link ? <Link to={link} onClick={(ev) => ev.stopPropagation()}>{e.entityType} · {e.entityId}</Link> : `${e.entityType} · ${e.entityId}`}</td>}
                  <td className="small">{e.reason ?? <span className="muted">—</span>}</td>
                </tr>
                {open === e.id && (
                  <tr><td colSpan={compact ? 4 : 5}>
                    <div className="grid-2">
                      <div><span className="label">Before</span><pre className="json">{show(e.before) ?? '—'}</pre></div>
                      <div><span className="label">After</span><pre className="json">{show(e.after) ?? '—'}</pre></div>
                    </div>
                  </td></tr>
                )}
              </Fragment>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}

export function AuditPage() {
  const [filters, setFilters] = useState({ entityType: '', entityId: '', admin: '', action: '' })
  const q = useInfiniteQuery({
    queryKey: ['audit', filters],
    queryFn: ({ pageParam }) => api<AuditResponse>('/audit', { query: { ...filters, before: pageParam ?? undefined, limit: 50 } }),
    initialPageParam: null as number | null,
    getNextPageParam: (last) => last.nextBefore,
  })
  const set = (k: keyof typeof filters) => (e: { target: { value: string } }) => setFilters((f) => ({ ...f, [k]: e.target.value }))
  return (
    <>
      <PageHead eyebrow="Every admin change, append-only" title="Audit Log" />
      <p className="muted" style={{ margin: 0 }}>Rows are written in the same database transaction as the change and can't be edited or deleted, even directly in the database.</p>
      <div className="toolbar">
        <Field label="Record type">{(id) => (
          <select id={id} className="select" value={filters.entityType} onChange={set('entityType')}>
            <option value="">All</option>
            {['repair', 'service', 'category', 'issue', 'brand', 'shop', 'technician', 'courier', 'service_area', 'slot_template', 'slot_block', 'payment', 'claim', 'ticket', 'admin_user'].map((t) => <option key={t} value={t}>{t}</option>)}
          </select>
        )}</Field>
        <Field label="Record ID">{(id) => <input id={id} className="input" value={filters.entityId} onChange={set('entityId')} placeholder="e.g. SO-482917" />}</Field>
        <Field label="Staff email">{(id) => <input id={id} className="input" value={filters.admin} onChange={set('admin')} />}</Field>
        <Field label="Action starts with">{(id) => <input id={id} className="input" value={filters.action} onChange={set('action')} placeholder="e.g. pricing." />}</Field>
      </div>
      <QueryView q={{ ...q, data: q.data }}>
        {(data) => {
          const rows = data.pages.flatMap((p) => p.items)
          return rows.length === 0 ? <Empty title="No audit entries match" /> : (
            <div className="stack">
              <AuditTable rows={rows} />
              {q.hasNextPage && <div><Button busy={q.isFetchingNextPage} onClick={() => q.fetchNextPage()}>Load older entries</Button></div>}
            </div>
          )
        }}
      </QueryView>
    </>
  )
}
