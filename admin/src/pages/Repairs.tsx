import { useEffect, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import type { AdminMeta, Catalog, RepairList, RepairSummary } from '../api/types'
import { dateTime, device, inr, slotLabel } from '../lib/format'
import { filtersFromParams, filtersToApiQuery, filtersToParams, PAGE_SIZE, updateFilters, VIEWS, type RepairFilters } from '../lib/filters'
import { Button, Empty, Field, PageHead, QueryView, StatusTag, Tag } from '../components/ui'

/** Repairs as a table. Rows open the repair. */
export function RepairTable({ rows, compact }: { rows: RepairSummary[]; compact?: boolean }) {
  const navigate = useNavigate()
  return (
    <div className="table-wrap">
      <table className="data">
        <thead>
          <tr>
            <th>Repair</th><th>Device</th><th>Status</th>{!compact && <th>Customer</th>}<th>Pickup slot</th>{!compact && <th>Partner / shop</th>}{!compact && <th className="right">Estimate</th>}{!compact && <th>Created</th>}
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.id} className="clickable" onClick={() => navigate(`/repairs/${r.id}`)}>
              <td><Link className="mono" to={`/repairs/${r.id}`} onClick={(e) => e.stopPropagation()}>{r.id}</Link>{r.warrantyClaimFor && <div><Tag tone="muted">Warranty job</Tag></div>}</td>
              <td>{device(r)}</td>
              <td><div className="stack" style={{ gap: 4 }}><StatusTag status={r.status} title={r.statusTitle} />{r.delayed && <span className="tag delay" title={r.delayReason ?? ''}>Delayed</span>}</div></td>
              {!compact && <td>{r.contactName}<div className="small muted mono">{r.contactPhone} · {r.city} {r.pincode}</div></td>}
              <td className="small">{slotLabel(r.slotDate, r.slotStartHour)}{compact && r.delayReason && <div className="small red">{r.delayReason}</div>}</td>
              {!compact && <td className="small">{r.partnerName ?? '—'}<div className="muted">{r.shopName ?? 'No shop yet'}{r.technicianName ? ` · ${r.technicianName}` : ''}</div></td>}
              {!compact && <td className="right num small">{inr(r.estimateLow)}–{inr(r.estimateHigh)}</td>}
              {!compact && <td className="small muted">{dateTime(r.createdAt)}</td>}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

export function RepairsPage() {
  const [params, setParams] = useSearchParams()
  const filters = filtersFromParams(params)
  // Reads the current URL when applied, so a delayed search can't undo a filter chosen in the meantime.
  const set = (change: Partial<RepairFilters>) => setParams((prev) => filtersToParams(updateFilters(filtersFromParams(prev), change)))

  // Search applies after a short pause so each keystroke doesn't hit the API.
  const [search, setSearch] = useState(filters.q)
  useEffect(() => setSearch(filters.q), [filters.q])
  useEffect(() => {
    if (search === filters.q) return
    const t = setTimeout(() => set({ q: search }), 300)
    return () => clearTimeout(t)
  }, [search, filters.q]) // eslint-disable-line react-hooks/exhaustive-deps

  const meta = useQuery({ queryKey: ['meta'], queryFn: () => api<AdminMeta>('/meta'), staleTime: Infinity })
  const catalog = useQuery({ queryKey: ['catalog'], queryFn: () => api<Catalog>('/catalog') })
  const q = useQuery({
    queryKey: ['repairs', filtersToApiQuery(filters)],
    queryFn: () => api<RepairList>('/repairs', { query: filtersToApiQuery(filters) }),
    placeholderData: keepPreviousData,
  })
  const pages = q.data ? Math.max(1, Math.ceil(q.data.total / PAGE_SIZE)) : 1
  const anyFilter = filtersToParams({ ...filters, page: 1 }).toString() !== ''

  return (
    <>
      <PageHead eyebrow="Every booking and warranty job" title="Repairs" />
      <div className="toolbar">
        <Field label="Search" className="search">
          {(id) => <input id={id} className="input" type="search" placeholder="Repair ID, name, phone, brand or model" value={search} onChange={(e) => setSearch(e.target.value)} />}
        </Field>
        <Field label="Status">
          {(id) => (
            <select id={id} className="select" value={filters.status} onChange={(e) => set({ status: e.target.value })}>
              <option value="">All statuses</option>
              <option value={VIEWS.ACTIVE}>Active (in progress)</option>
              <option value={VIEWS.DELAYED}>Delayed</option>
              <optgroup label="Exact status">
                {meta.data?.statuses.map((s) => <option key={s.code} value={s.code}>{s.title}</option>)}
              </optgroup>
            </select>
          )}
        </Field>
        <Field label="Device">
          {(id) => (
            <select id={id} className="select" value={filters.category} onChange={(e) => set({ category: e.target.value })}>
              <option value="">All devices</option>
              {catalog.data?.categories.map((c) => <option key={c.code} value={c.code}>{c.label}</option>)}
            </select>
          )}
        </Field>
        <Field label="Booked from">{(id) => <input id={id} className="input" type="date" value={filters.from} onChange={(e) => set({ from: e.target.value })} />}</Field>
        <Field label="Booked to">{(id) => <input id={id} className="input" type="date" value={filters.to} onChange={(e) => set({ to: e.target.value })} />}</Field>
        {anyFilter && <Button kind="ghost" onClick={() => { setSearch(''); setParams(new URLSearchParams()) }}>Clear</Button>}
      </div>
      <QueryView q={q} rows={8}>
        {(data) => data.items.length === 0 ? (
          <Empty title="No repairs match">{anyFilter ? 'Try fewer filters.' : 'Bookings from the app appear here.'}</Empty>
        ) : (
          <div className="stack">
            <span className="label" aria-live="polite">{data.total} {data.total === 1 ? 'repair' : 'repairs'}</span>
            <RepairTable rows={data.items} />
            {pages > 1 && (
              <div className="row">
                <Button small disabled={filters.page <= 1} onClick={() => set({ page: filters.page - 1 })}>Previous</Button>
                <span className="label">Page {filters.page} of {pages}</span>
                <Button small disabled={filters.page >= pages} onClick={() => set({ page: filters.page + 1 })}>Next</Button>
              </div>
            )}
          </div>
        )}
      </QueryView>
    </>
  )
}
