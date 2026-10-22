import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api, errorMessage } from '../api/client'
import type { Brand, Catalog, Category, Issue, PriceHistory, ServiceDetail, ServiceEntry } from '../api/types'
import { useCan } from '../auth/AuthContext'
import { dateTime, inr } from '../lib/format'
import { Button, Dialog, Empty, Field, PageHead, Panel, QueryView, ReasonDialog, Tabs, Tag, useAction } from '../components/ui'

type Tab = 'services' | 'catalogue' | 'brands' | 'history'

export function PricingPage() {
  const [tab, setTab] = useState<Tab>('services')
  const q = useQuery({ queryKey: ['catalog'], queryFn: () => api<Catalog>('/catalog') })
  return (
    <>
      <PageHead eyebrow="Published prices, GST included" title="Services & Pricing" />
      <p className="muted" style={{ margin: 0, maxWidth: 820 }}>
        Changes apply to new estimates straight away. Every booked repair keeps the estimate it was booked with, and sent quotes keep their own lines,
        so editing a price never changes what a customer has already been told. Each change needs a reason and is kept in the price history.
      </p>
      <Tabs label="Pricing sections" value={tab} onChange={setTab} options={[
        { value: 'services', label: 'Services' }, { value: 'catalogue', label: 'Devices & issues' },
        { value: 'brands', label: 'Brand multipliers' }, { value: 'history', label: 'Price history' },
      ]} />
      <QueryView q={q}>
        {(c) => (
          <>
            {tab === 'services' && <ServicesTab c={c} />}
            {tab === 'catalogue' && <CatalogueTab c={c} />}
            {tab === 'brands' && <BrandsTab c={c} />}
            {tab === 'history' && <HistoryTab />}
          </>
        )}
      </QueryView>
    </>
  )
}

// ---- Services ------------------------------------------------------------------------------------------------

function ServicesTab({ c }: { c: Catalog }) {
  const can = useCan()
  const [category, setCategory] = useState('')
  const [open, setOpen] = useState<ServiceEntry | null>(null)
  const [creating, setCreating] = useState(false)
  const entries = c.entries.filter((e) => !category || e.category === category)
  const cat = (code: string) => c.categories.find((x) => x.code === code)
  return (
    <div className="stack">
      <div className="toolbar">
        <Field label="Device">{(id) => (
          <select id={id} className="select" value={category} onChange={(e) => setCategory(e.target.value)}>
            <option value="">All devices</option>
            {c.categories.map((x) => <option key={x.code} value={x.code}>{x.label}</option>)}
          </select>
        )}</Field>
        <span className="spacer" />
        {can('pricing.manage') && <Button kind="primary" onClick={() => setCreating(true)}>Offer a new repair</Button>}
      </div>
      <div className="table-wrap">
        <table className="data">
          <thead><tr><th>Device</th><th>Repair</th><th className="right">Base price</th><th className="right">Diagnosis fee</th><th>Turnaround</th><th>Availability</th><th className="right">Bookings</th><th>Updated</th></tr></thead>
          <tbody>
            {entries.map((e) => (
              <tr key={`${e.category}/${e.issue}`} className="clickable" onClick={() => setOpen(e)}>
                <td>{cat(e.category)?.label ?? e.category}{cat(e.category)?.active === false && <div><Tag tone="muted">Device hidden</Tag></div>}</td>
                <td><button className="link" style={{ textDecoration: 'none', color: 'inherit', font: 'inherit', textTransform: 'none', letterSpacing: 0 }}>{e.label}</button></td>
                <td className="right num">{inr(e.basePrice)}</td>
                <td className="right num">{e.diagnosisFee ? inr(e.diagnosisFee) : '—'}</td>
                <td className="small">{e.turnaround}</td>
                <td>{e.active ? <Tag tone="done">Offered</Tag> : <Tag tone="muted">Inactive</Tag>}</td>
                <td className="right num">{e.bookings}</td>
                <td className="small muted">{dateTime(e.updatedAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {open && <ServiceDialog entry={open} categoryLabel={cat(open.category)?.label ?? open.category} onClose={() => setOpen(null)} />}
      {creating && <NewServiceDialog c={c} onClose={() => setCreating(false)} />}
    </div>
  )
}

function ServiceDialog({ entry, categoryLabel, onClose }: { entry: ServiceEntry; categoryLabel: string; onClose: () => void }) {
  const can = useCan()
  const q = useQuery({ queryKey: ['service', entry.category, entry.issue], queryFn: () => api<ServiceDetail>(`/catalog/services/${entry.category}/${entry.issue}`) })
  const [basePrice, setBasePrice] = useState(String(entry.basePrice))
  const [fee, setFee] = useState(String(entry.diagnosisFee))
  const [turnaround, setTurnaround] = useState(entry.turnaround)
  const [label, setLabel] = useState(entry.labelOverride ?? '')
  const [active, setActive] = useState(entry.active)
  const [reason, setReason] = useState('')
  const change: Record<string, unknown> = {}
  if (Number(basePrice) !== entry.basePrice) change.basePrice = Number(basePrice)
  if (Number(fee) !== entry.diagnosisFee) change.diagnosisFee = Number(fee)
  if (turnaround.trim() !== entry.turnaround) change.turnaround = turnaround.trim()
  if (label.trim() !== (entry.labelOverride ?? '')) { if (label.trim()) change.labelOverride = label.trim(); else change.clearLabelOverride = true }
  if (active !== entry.active) change.active = active
  const changed = Object.keys(change).length > 0
  const validPrice = Number.isInteger(Number(basePrice)) && Number(basePrice) >= 1 && Number.isInteger(Number(fee)) && Number(fee) >= 0
  const save = useAction(() => api<ServiceDetail>(`/catalog/services/${entry.category}/${entry.issue}`, { method: 'PATCH', body: { ...change, reason: reason.trim() } }), {
    invalidate: [['catalog'], ['service', entry.category, entry.issue], ['price-history']], success: 'Service updated.', onSuccess: onClose,
  })
  const editable = can('pricing.manage')
  return (
    <Dialog title={`${categoryLabel} · ${entry.label}`} onClose={onClose} wide>
      <div className="grid-2">
        <div className="stack">
          <div className="form-grid">
            <Field label="Base price (₹)">{(id) => <input id={id} className="input num" type="number" min={1} value={basePrice} disabled={!editable} onChange={(e) => setBasePrice(e.target.value)} />}</Field>
            <Field label="Diagnosis fee (₹)" hint="Stored with the service; not yet charged by the app.">{(id) => <input id={id} className="input num" type="number" min={0} value={fee} disabled={!editable} onChange={(e) => setFee(e.target.value)} />}</Field>
            <Field label="Turnaround">{(id) => <input id={id} className="input" value={turnaround} disabled={!editable} maxLength={40} onChange={(e) => setTurnaround(e.target.value)} />}</Field>
            <Field label="Customer label" hint="Leave blank to use the issue's name.">{(id) => <input id={id} className="input" value={label} disabled={!editable} maxLength={40} onChange={(e) => setLabel(e.target.value)} />}</Field>
          </div>
          <label className="check"><input type="checkbox" checked={active} disabled={!editable} onChange={(e) => setActive(e.target.checked)} /> Offered to customers</label>
          {editable && (
            <>
              <Field label="Reason for this change (required)" hint="At least 5 characters. Kept in the price history and audit log.">
                {(id) => <textarea id={id} className="textarea" value={reason} onChange={(e) => setReason(e.target.value)} maxLength={300} />}
              </Field>
              {save.error && <p className="error-text">{errorMessage(save.error)}</p>}
              <div className="row">
                <Button kind="primary" disabled={!changed || !validPrice || reason.trim().length < 5} busy={save.isPending} onClick={() => save.mutate(undefined)}>Save change</Button>
                {!changed && <span className="hint">Change a field to save.</span>}
              </div>
            </>
          )}
        </div>
        <QueryView q={q} rows={3}>
          {(d) => (
            <div className="stack">
              <span className="label">Current estimates by brand</span>
              <div className="table-wrap">
                <table className="data">
                  <tbody>{d.estimates.map((e) => <tr key={e.brand ?? '-'}><td>{e.brand ?? 'Any brand (unknown)'}</td><td className="right num">{inr(e.low)} – {inr(e.high)}</td></tr>)}</tbody>
                </table>
              </div>
              <span className="label">Price history</span>
              {d.history.length === 0 ? <span className="muted small">No changes since launch.</span> : <HistoryTable rows={d.history} compact />}
            </div>
          )}
        </QueryView>
      </div>
    </Dialog>
  )
}

function NewServiceDialog({ c, onClose }: { c: Catalog; onClose: () => void }) {
  const [category, setCategory] = useState(c.categories[0]?.code ?? '')
  const missing = c.issues.filter((i) => !c.entries.some((e) => e.category === category && e.issue === i.code))
  const [issue, setIssue] = useState('')
  const [basePrice, setBasePrice] = useState('')
  const [fee, setFee] = useState('0')
  const [turnaround, setTurnaround] = useState('2–4 days')
  const create = useAction((reason: string) => api<ServiceDetail>('/catalog/services', {
    method: 'POST', body: { category, issue: issue || missing[0]?.code, basePrice: Number(basePrice), diagnosisFee: Number(fee), turnaround, reason },
  }), { invalidate: [['catalog'], ['price-history']], success: 'Service added to the catalogue.', onSuccess: onClose })
  return (
    <ReasonDialog title="Offer a new repair" confirm="Add service" busy={create.isPending} error={create.error ? errorMessage(create.error) : null}
      onConfirm={(reason) => create.mutate(reason)} onClose={onClose}>
      <div className="form-grid">
        <Field label="Device">{(id) => <select id={id} className="select" value={category} onChange={(e) => { setCategory(e.target.value); setIssue('') }}>{c.categories.map((x) => <option key={x.code} value={x.code}>{x.label}</option>)}</select>}</Field>
        <Field label="Repair" error={missing.length === 0 ? 'Every repair is already offered for this device.' : null}>{(id) => <select id={id} className="select" value={issue || missing[0]?.code || ''} onChange={(e) => setIssue(e.target.value)}>{missing.map((i) => <option key={i.code} value={i.code}>{i.label}</option>)}</select>}</Field>
        <Field label="Base price (₹)">{(id) => <input id={id} className="input num" type="number" min={1} value={basePrice} onChange={(e) => setBasePrice(e.target.value)} />}</Field>
        <Field label="Diagnosis fee (₹)">{(id) => <input id={id} className="input num" type="number" min={0} value={fee} onChange={(e) => setFee(e.target.value)} />}</Field>
        <Field label="Turnaround">{(id) => <input id={id} className="input" value={turnaround} onChange={(e) => setTurnaround(e.target.value)} />}</Field>
      </div>
    </ReasonDialog>
  )
}

// ---- Devices & issues ----------------------------------------------------------------------------------------

function CatalogueTab({ c }: { c: Catalog }) {
  const can = useCan()
  const [cat, setCat] = useState<Category | null>(null)
  const [issue, setIssue] = useState<Issue | null>(null)
  const [label, setLabel] = useState('')
  const [hint, setHint] = useState('')
  const toggle = useAction((reason: string) => api<Catalog>(`/catalog/categories/${cat!.code}`, { method: 'PATCH', body: { active: !cat!.active, reason } }), {
    invalidate: [['catalog']], success: 'Device category updated.', onSuccess: () => setCat(null),
  })
  const editIssue = useAction((reason: string) => api<Catalog>(`/catalog/issues/${issue!.code}`, { method: 'PATCH', body: { label, hint, reason } }), {
    invalidate: [['catalog']], success: 'Issue updated.', onSuccess: () => setIssue(null),
  })
  return (
    <div className="grid-2">
      <Panel title="Device categories">
        <div className="table-wrap">
          <table className="data">
            <thead><tr><th>Device</th><th>Code</th><th>Services</th><th>Status</th><th /></tr></thead>
            <tbody>
              {c.categories.map((x) => (
                <tr key={x.code}>
                  <td>{x.label}</td><td className="mono small">{x.code}</td>
                  <td className="num">{c.entries.filter((e) => e.category === x.code && e.active).length} offered</td>
                  <td>{x.active ? <Tag tone="done">Shown</Tag> : <Tag tone="muted">Hidden</Tag>}</td>
                  <td className="right">{can('pricing.manage') && <Button small kind={x.active ? 'danger' : 'outline'} onClick={() => setCat(x)}>{x.active ? 'Hide' : 'Show'}</Button>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <span className="hint">A hidden device disappears from the app's booking flow. Existing repairs aren't affected.</span>
      </Panel>
      <Panel title="Repair issues">
        <div className="table-wrap">
          <table className="data">
            <thead><tr><th>Issue</th><th>Hint shown in the app</th><th /></tr></thead>
            <tbody>
              {c.issues.map((i) => (
                <tr key={i.code}>
                  <td>{i.label}<div className="mono small muted">{i.code}</div></td><td className="small">{i.hint}</td>
                  <td className="right">{can('pricing.manage') && <Button small onClick={() => { setIssue(i); setLabel(i.label); setHint(i.hint) }}>Edit</Button>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Panel>
      {cat && (
        <ReasonDialog title={`${cat.active ? 'Hide' : 'Show'} ${cat.label}`} danger={cat.active} confirm={cat.active ? 'Hide from customers' : 'Show to customers'}
          body={cat.active ? 'Customers will no longer be able to book this device.' : 'Customers will be able to book this device again.'}
          busy={toggle.isPending} error={toggle.error ? errorMessage(toggle.error) : null} onConfirm={(r) => toggle.mutate(r)} onClose={() => setCat(null)} />
      )}
      {issue && (
        <ReasonDialog title={`Edit ${issue.label}`} confirm="Save" busy={editIssue.isPending} error={editIssue.error ? errorMessage(editIssue.error) : null}
          onConfirm={(r) => editIssue.mutate(r)} onClose={() => setIssue(null)}>
          <Field label="Label">{(id) => <input id={id} className="input" value={label} maxLength={30} onChange={(e) => setLabel(e.target.value)} />}</Field>
          <Field label="Hint">{(id) => <input id={id} className="input" value={hint} maxLength={60} onChange={(e) => setHint(e.target.value)} />}</Field>
        </ReasonDialog>
      )}
    </div>
  )
}

// ---- Brands --------------------------------------------------------------------------------------------------

function BrandsTab({ c }: { c: Catalog }) {
  const can = useCan()
  const [brand, setBrand] = useState<Brand | null>(null)
  const [value, setValue] = useState('')
  const save = useAction((reason: string) => api<Catalog>(`/catalog/brands/${brand!.id}`, { method: 'PATCH', body: { multiplier: Number(value), reason } }), {
    invalidate: [['catalog'], ['price-history']], success: 'Multiplier updated.', onSuccess: () => setBrand(null),
  })
  return (
    <Panel title="Brand multipliers">
      <span className="hint">An estimate for a known brand is base price × multiplier (rounded to end in 99), up to 1.35× that for the high end.</span>
      <div className="table-wrap">
        <table className="data">
          <thead><tr><th>Device</th><th>Brand</th><th className="right">Multiplier</th><th /></tr></thead>
          <tbody>
            {c.brands.map((b) => (
              <tr key={b.id}>
                <td>{c.categories.find((x) => x.code === b.category)?.label}</td><td>{b.name}</td><td className="right num mono">{b.multiplier.toFixed(2)}×</td>
                <td className="right">{can('pricing.manage') && <Button small onClick={() => { setBrand(b); setValue(String(b.multiplier)) }}>Edit</Button>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {brand && (
        <ReasonDialog title={`${brand.name} multiplier`} confirm="Save" busy={save.isPending} error={save.error ? errorMessage(save.error) : null}
          body="Changes every new estimate for this brand. Booked repairs keep their estimates." onConfirm={(r) => save.mutate(r)} onClose={() => setBrand(null)}>
          <Field label="Multiplier" hint="Between 0.30 and 5.00.">{(id) => <input id={id} className="input num" type="number" step="0.05" min={0.3} max={5} value={value} onChange={(e) => setValue(e.target.value)} />}</Field>
        </ReasonDialog>
      )}
    </Panel>
  )
}

// ---- History -------------------------------------------------------------------------------------------------

export function HistoryTable({ rows, compact }: { rows: PriceHistory[]; compact?: boolean }) {
  return (
    <div className="table-wrap">
      <table className="data">
        <thead><tr><th>When</th>{!compact && <th>What</th>}<th>Field</th><th>Change</th><th>Reason</th>{!compact && <th>By</th>}</tr></thead>
        <tbody>
          {rows.map((h) => (
            <tr key={h.id}>
              <td className="small mono">{dateTime(h.at)}</td>
              {!compact && <td className="small">{h.category}{h.issue ? ` / ${h.issue}` : ''}{h.brand ? ` / ${h.brand}` : ''}</td>}
              <td className="mono small">{h.field}</td>
              <td className="small num">{h.oldValue ?? '—'} → <strong>{h.newValue ?? '—'}</strong></td>
              <td className="small">{h.reason}{compact && <div className="muted">{h.changedBy}</div>}</td>
              {!compact && <td className="small muted">{h.changedBy}</td>}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function HistoryTab() {
  const q = useQuery({ queryKey: ['price-history'], queryFn: () => api<PriceHistory[]>('/catalog/history', { query: { limit: 500 } }) })
  return <QueryView q={q}>{(rows) => rows.length === 0 ? <Empty title="No price changes yet" /> : <HistoryTable rows={rows} />}</QueryView>
}
