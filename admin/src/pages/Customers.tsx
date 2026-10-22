import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import type { CustomerDetail, CustomerList } from '../api/types'
import { dateOnly, dateTime } from '../lib/format'
import { Docket, Empty, Field, KV, PageHead, Panel, QueryView, StatusTag, Tag } from '../components/ui'
import { RepairTable } from './Repairs'
import { ClaimTable } from './Claims'
import { TicketTable } from './Support'

export function CustomersPage() {
  const [params, setParams] = useSearchParams()
  const applied = params.get('q') ?? ''
  const [search, setSearch] = useState(applied)
  useEffect(() => {
    if (search === applied) return
    const t = setTimeout(() => setParams(search.trim() ? { q: search.trim() } : {}), 300)
    return () => clearTimeout(t)
  }, [search, applied, setParams])
  const navigate = useNavigate()
  const q = useQuery({ queryKey: ['customers', applied], queryFn: () => api<CustomerList>('/customers', { query: { q: applied, limit: 100 } }), placeholderData: keepPreviousData })
  return (
    <>
      <PageHead eyebrow="Guests and registered customers" title="Customers" />
      <div className="toolbar">
        <Field label="Search" className="search">{(id) => <input id={id} className="input" type="search" placeholder="Name, phone, email or customer ID" value={search} onChange={(e) => setSearch(e.target.value)} />}</Field>
      </div>
      <QueryView q={q}>
        {(data) => data.items.length === 0 ? <Empty title="No customers match" /> : (
          <div className="stack">
            <span className="label">{data.total} customers{data.total > data.items.length ? ` · showing ${data.items.length}` : ''}</span>
            <div className="table-wrap">
              <table className="data">
                <thead><tr><th>Customer</th><th>Phone</th><th>Kind</th><th className="right">Repairs</th><th className="right">Active</th><th>Last booking</th><th>Since</th></tr></thead>
                <tbody>
                  {data.items.map((c) => (
                    <tr key={c.id} className="clickable" onClick={() => navigate(`/customers/${c.id}`)}>
                      <td><Link to={`/customers/${c.id}`} onClick={(e) => e.stopPropagation()}>{c.name ?? 'Guest'}</Link><div className="small muted mono">{c.id.slice(0, 8)}</div></td>
                      <td className="mono small">{c.phone ?? '—'}</td>
                      <td><Tag tone={c.kind === 'GUEST' ? 'muted' : 'active'}>{c.kind}</Tag></td>
                      <td className="right num">{c.repairs}</td>
                      <td className="right num">{c.activeRepairs}</td>
                      <td className="small">{dateTime(c.lastRepairAt)}</td>
                      <td className="small muted">{dateOnly(c.createdAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </QueryView>
    </>
  )
}

export function CustomerDetailPage() {
  const { id = '' } = useParams()
  const q = useQuery({ queryKey: ['customer', id], queryFn: () => api<CustomerDetail>(`/customers/${id}`) })
  return (
    <QueryView q={q}>
      {(d) => (
        <>
          <PageHead eyebrow={`Customer · ${d.customer.kind.toLowerCase()}`} title={d.customer.name ?? 'Guest customer'} />
          <div className="grid-2">
            <Docket head="Profile" right={`Since ${dateOnly(d.customer.createdAt)}`}>
              <KV rows={[
                ['Customer ID', <span className="mono small">{d.customer.id}</span>],
                ['Phone', d.customer.phone ?? '—'],
                ['Email', d.customer.email ?? '—'],
                ['Repairs', `${d.customer.repairs} total · ${d.customer.activeRepairs} active`],
              ]} />
            </Docket>
            <Panel title="Addresses">
              {d.addresses.length === 0 ? <span className="muted">No saved addresses.</span> : d.addresses.map(({ address: a, deleted }) => (
                <div key={a.id} className="row between" style={{ opacity: deleted ? 0.5 : 1 }}>
                  <div><strong>{a.label}</strong> {a.isDefault && !deleted && <Tag>Default</Tag>} {deleted && <Tag tone="muted">Deleted</Tag>}
                    <div className="small muted">{[a.line1, a.line2, a.area, `${a.city} ${a.pincode}`].filter(Boolean).join(', ')}</div></div>
                </div>
              ))}
            </Panel>
          </div>
          <Panel title="Repair history">{d.repairs.length === 0 ? <Empty title="No repairs yet" /> : <RepairTable rows={d.repairs} />}</Panel>
          <div className="grid-2">
            <Panel title="Warranty claims">{d.claims.length === 0 ? <span className="muted">None.</span> : <ClaimTable rows={d.claims} />}</Panel>
            <Panel title="Support tickets">{d.tickets.length === 0 ? <span className="muted">None.</span> : <TicketTable rows={d.tickets} />}</Panel>
          </div>
          {d.repairs.some((r) => r.status === 'QUOTE_PENDING') && <Panel><StatusTag status="QUOTE_PENDING" title="A quote is waiting for this customer" /></Panel>}
        </>
      )}
    </QueryView>
  )
}
