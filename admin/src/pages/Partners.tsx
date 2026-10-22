import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api, errorMessage } from '../api/client'
import type { Catalog, Courier, Partners, RepairSummary, Shop, Technician } from '../api/types'
import { useCan } from '../auth/AuthContext'
import { Button, Dialog, Empty, Field, PageHead, Panel, QueryView, Tabs, Tag, useAction } from '../components/ui'
import { RepairTable } from './Repairs'

type Tab = 'shops' | 'couriers'

function Load({ used, capacity }: { used: number; capacity: number }) {
  const pct = capacity > 0 ? Math.min(100, (used / capacity) * 100) : 100
  return (
    <div className="stack" style={{ gap: 4, minWidth: 120 }}>
      <span className="small num">{used} / {capacity}</span>
      <div className={`bar ${used >= capacity ? 'over' : ''}`} role="meter" aria-valuenow={used} aria-valuemax={capacity} aria-label="Load"><span style={{ width: `${pct}%` }} /></div>
    </div>
  )
}

export function PartnersPage() {
  const [tab, setTab] = useState<Tab>('shops')
  const q = useQuery({ queryKey: ['partners'], queryFn: () => api<Partners>('/partners') })
  const catalog = useQuery({ queryKey: ['catalog'], queryFn: () => api<Catalog>('/catalog') })
  const categories = catalog.data?.categories.map((c) => c.code) ?? ['PHONE', 'LAPTOP', 'TABLET', 'PC', 'WEARABLE', 'AUDIO']
  return (
    <>
      <PageHead eyebrow="Verified shops, technicians and pickup partners" title="Partners" />
      <Tabs label="Partner type" value={tab} onChange={setTab} options={[{ value: 'shops', label: 'Repair shops & technicians' }, { value: 'couriers', label: 'Pickup partners' }]} />
      <QueryView q={q}>{(p) => tab === 'shops' ? <ShopsTab shops={p.shops} categories={categories} /> : <CouriersTab couriers={p.couriers} />}</QueryView>
    </>
  )
}

function JobsDialog({ kind, id, name, onClose }: { kind: 'shops' | 'couriers' | 'technicians'; id: string; name: string; onClose: () => void }) {
  const q = useQuery({ queryKey: ['assignments', kind, id], queryFn: () => api<RepairSummary[]>(`/partners/${kind}/${id}/assignments`) })
  return (
    <Dialog title={`${name} · active jobs`} onClose={onClose} wide>
      <QueryView q={q}>{(rows) => rows.length === 0 ? <Empty title="No active jobs" /> : <RepairTable rows={rows} compact />}</QueryView>
    </Dialog>
  )
}

function CapabilityPicker({ value, onChange, categories, label }: { value: string[]; onChange: (v: string[]) => void; categories: string[]; label: string }) {
  return (
    <fieldset className="field" style={{ border: 0, padding: 0, margin: 0 }}>
      <legend className="label">{label}</legend>
      <div className="row">
        {categories.map((c) => (
          <label key={c} className="check small">
            <input type="checkbox" checked={value.includes(c)} onChange={(e) => onChange(e.target.checked ? [...value, c] : value.filter((x) => x !== c))} /> {c}
          </label>
        ))}
      </div>
    </fieldset>
  )
}

// ---- Shops ---------------------------------------------------------------------------------------------------

function ShopsTab({ shops, categories }: { shops: Shop[]; categories: string[] }) {
  const can = useCan()
  const [editing, setEditing] = useState<Shop | 'new' | null>(null)
  const [tech, setTech] = useState<{ shop: Shop; tech: Technician | null } | null>(null)
  const [jobs, setJobs] = useState<{ kind: 'shops' | 'technicians'; id: string; name: string } | null>(null)
  return (
    <div className="stack">
      {can('partners.manage') && <div className="row"><Button kind="primary" onClick={() => setEditing('new')}>Add repair shop</Button></div>}
      {shops.map((s) => (
        <Panel key={s.id} title={
          <div className="row"><h3 className="display">{s.name}</h3>{s.active ? <Tag tone="done">Active</Tag> : <Tag tone="muted">Inactive</Tag>}</div>
        } actions={
          <div className="row">
            <Button small kind="ghost" onClick={() => setJobs({ kind: 'shops', id: s.id, name: s.name })}>Jobs</Button>
            {can('partners.manage') && <Button small onClick={() => setEditing(s)}>Edit</Button>}
          </div>
        }>
          <div className="grid-3">
            <div className="stack" style={{ gap: 4 }}><span className="label">Area · verified since</span><span>{s.area} · {s.verifiedSince}</span></div>
            <div className="stack" style={{ gap: 4 }}><span className="label">Rating</span><span className="num">{s.rating.toFixed(1)} ★ · {s.reviews} reviews</span></div>
            <div className="stack" style={{ gap: 4 }}><span className="label">Jobs in the shop / capacity</span><Load used={s.activeJobs} capacity={s.capacity} /></div>
          </div>
          <div className="row"><span className="label">Repairs</span>{s.capabilities.map((c) => <Tag key={c} tone="muted">{c}</Tag>)}</div>
          <div className="table-wrap">
            <table className="data">
              <thead><tr><th>Technician</th><th>Skills</th><th className="right">Active jobs</th><th>Status</th><th /></tr></thead>
              <tbody>
                {s.technicians.length === 0 && <tr><td colSpan={5} className="muted">No technicians yet.</td></tr>}
                {s.technicians.map((t) => (
                  <tr key={t.id}>
                    <td>{t.name}</td>
                    <td className="small">{t.skills.length ? t.skills.join(', ') : 'All devices'}</td>
                    <td className="right num">{t.activeJobs}</td>
                    <td>{t.active ? <Tag tone="done">Active</Tag> : <Tag tone="muted">Inactive</Tag>}</td>
                    <td className="right"><div className="row" style={{ justifyContent: 'flex-end' }}>
                      <Button small kind="ghost" onClick={() => setJobs({ kind: 'technicians', id: t.id, name: t.name })}>Jobs</Button>
                      {can('partners.manage') && <Button small onClick={() => setTech({ shop: s, tech: t })}>Edit</Button>}
                    </div></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {can('partners.manage') && <div><Button small onClick={() => setTech({ shop: s, tech: null })}>Add technician</Button></div>}
        </Panel>
      ))}
      {editing && <ShopForm shop={editing === 'new' ? null : editing} categories={categories} onClose={() => setEditing(null)} />}
      {tech && <TechnicianForm shops={shops} shop={tech.shop} tech={tech.tech} categories={categories} onClose={() => setTech(null)} />}
      {jobs && <JobsDialog {...jobs} onClose={() => setJobs(null)} />}
    </div>
  )
}

function ShopForm({ shop, categories, onClose }: { shop: Shop | null; categories: string[]; onClose: () => void }) {
  const [name, setName] = useState(shop?.name ?? '')
  const [area, setArea] = useState(shop?.area ?? '')
  const [capacity, setCapacity] = useState(String(shop?.capacity ?? 20))
  const [caps, setCaps] = useState<string[]>(shop?.capabilities ?? categories)
  const [active, setActive] = useState(shop?.active ?? true)
  const [reason, setReason] = useState('')
  const save = useAction(() => api<Partners>(shop ? `/partners/shops/${shop.id}` : '/partners/shops', {
    method: shop ? 'PUT' : 'POST', body: { name, area, capacity: Number(capacity), capabilities: caps, active, verifiedSince: shop?.verifiedSince ?? '', reason },
  }), { invalidate: [['partners']], success: shop ? 'Shop updated.' : 'Shop added.', onSuccess: onClose })
  return (
    <Dialog title={shop ? `Edit ${shop.name}` : 'Add repair shop'} onClose={onClose}>
      <div className="form-grid">
        <Field label="Name">{(id) => <input id={id} className="input" value={name} onChange={(e) => setName(e.target.value)} />}</Field>
        <Field label="Area">{(id) => <input id={id} className="input" value={area} onChange={(e) => setArea(e.target.value)} />}</Field>
        <Field label="Capacity (jobs in the shop)">{(id) => <input id={id} className="input num" type="number" min={0} value={capacity} onChange={(e) => setCapacity(e.target.value)} />}</Field>
      </div>
      <CapabilityPicker label="Devices this shop repairs" value={caps} onChange={setCaps} categories={categories} />
      <label className="check"><input type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} /> Active (can receive new jobs)</label>
      <Field label="Note for the audit log (optional)">{(id) => <input id={id} className="input" value={reason} onChange={(e) => setReason(e.target.value)} />}</Field>
      {save.error && <p className="error-text">{errorMessage(save.error)}</p>}
      <div className="row"><Button kind="primary" disabled={!name.trim() || !area.trim()} busy={save.isPending} onClick={() => save.mutate(undefined)}>Save</Button></div>
    </Dialog>
  )
}

function TechnicianForm({ shops, shop, tech, categories, onClose }: { shops: Shop[]; shop: Shop; tech: Technician | null; categories: string[]; onClose: () => void }) {
  const [shopId, setShopId] = useState(tech?.shopId ?? shop.id)
  const [name, setName] = useState(tech?.name ?? '')
  const [skills, setSkills] = useState<string[]>(tech?.skills ?? shop.capabilities)
  const [active, setActive] = useState(tech?.active ?? true)
  const save = useAction(() => api<Partners>(tech ? `/partners/technicians/${tech.id}` : '/partners/technicians', {
    method: tech ? 'PUT' : 'POST', body: { shopId, name, skills, active },
  }), { invalidate: [['partners']], success: tech ? 'Technician updated.' : 'Technician added.', onSuccess: onClose })
  return (
    <Dialog title={tech ? `Edit ${tech.name}` : `Add technician · ${shop.name}`} onClose={onClose}>
      <Field label="Name">{(id) => <input id={id} className="input" value={name} onChange={(e) => setName(e.target.value)} />}</Field>
      <Field label="Shop">{(id) => <select id={id} className="select" value={shopId} onChange={(e) => setShopId(e.target.value)}>{shops.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}</select>}</Field>
      <CapabilityPicker label="Skills (none selected = all devices)" value={skills} onChange={setSkills} categories={categories} />
      <label className="check"><input type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} /> Active</label>
      {save.error && <p className="error-text">{errorMessage(save.error)}</p>}
      <div className="row"><Button kind="primary" disabled={!name.trim()} busy={save.isPending} onClick={() => save.mutate(undefined)}>Save</Button></div>
    </Dialog>
  )
}

// ---- Couriers ------------------------------------------------------------------------------------------------

function CouriersTab({ couriers }: { couriers: Courier[] }) {
  const can = useCan()
  const [editing, setEditing] = useState<Courier | 'new' | null>(null)
  const [jobs, setJobs] = useState<Courier | null>(null)
  return (
    <div className="stack">
      {can('partners.manage') && <div className="row"><Button kind="primary" onClick={() => setEditing('new')}>Add pickup partner</Button></div>}
      <div className="table-wrap">
        <table className="data">
          <thead><tr><th>Partner</th><th>Phone</th><th>Vehicle</th><th>Rating</th><th>Pickups today / capacity</th><th className="right">Active jobs</th><th>Status</th><th /></tr></thead>
          <tbody>
            {couriers.map((c) => (
              <tr key={c.id}>
                <td>{c.name}</td><td className="mono small">{c.phone}</td><td className="small">{c.vehicle}</td>
                <td className="num small">{c.rating.toFixed(1)} ★ · {c.trips} trips</td>
                <td><Load used={c.assignedToday} capacity={c.dailyCapacity} /></td>
                <td className="right num">{c.activeJobs}</td>
                <td>{c.active ? <Tag tone="done">Active</Tag> : <Tag tone="muted">Inactive</Tag>}</td>
                <td className="right"><div className="row" style={{ justifyContent: 'flex-end' }}>
                  <Button small kind="ghost" onClick={() => setJobs(c)}>Jobs</Button>
                  {can('partners.manage') && <Button small onClick={() => setEditing(c)}>Edit</Button>}
                </div></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {editing && <CourierForm courier={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
      {jobs && <JobsDialog kind="couriers" id={jobs.id} name={jobs.name} onClose={() => setJobs(null)} />}
    </div>
  )
}

function CourierForm({ courier, onClose }: { courier: Courier | null; onClose: () => void }) {
  const [name, setName] = useState(courier?.name ?? '')
  const [phone, setPhone] = useState(courier?.phone ?? '')
  const [vehicle, setVehicle] = useState(courier?.vehicle ?? '')
  const [capacity, setCapacity] = useState(String(courier?.dailyCapacity ?? 12))
  const [active, setActive] = useState(courier?.active ?? true)
  const save = useAction(() => api<Partners>(courier ? `/partners/couriers/${courier.id}` : '/partners/couriers', {
    method: courier ? 'PUT' : 'POST', body: { name, phone, vehicle, dailyCapacity: Number(capacity), active },
  }), { invalidate: [['partners']], success: courier ? 'Partner updated.' : 'Partner added.', onSuccess: onClose })
  return (
    <Dialog title={courier ? `Edit ${courier.name}` : 'Add pickup partner'} onClose={onClose}>
      <div className="form-grid">
        <Field label="Name">{(id) => <input id={id} className="input" value={name} onChange={(e) => setName(e.target.value)} />}</Field>
        <Field label="Mobile" hint="10-digit Indian number.">{(id) => <input id={id} className="input mono" inputMode="tel" value={phone} onChange={(e) => setPhone(e.target.value)} />}</Field>
        <Field label="Vehicle">{(id) => <input id={id} className="input" value={vehicle} onChange={(e) => setVehicle(e.target.value)} placeholder="Honda Activa · KA 03 MX 4821" />}</Field>
        <Field label="Pickups per day">{(id) => <input id={id} className="input num" type="number" min={0} value={capacity} onChange={(e) => setCapacity(e.target.value)} />}</Field>
      </div>
      <label className="check"><input type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} /> Active (can be assigned)</label>
      {save.error && <p className="error-text">{errorMessage(save.error)}</p>}
      <div className="row"><Button kind="primary" disabled={!name.trim() || !vehicle.trim() || !phone.trim()} busy={save.isPending} onClick={() => save.mutate(undefined)}>Save</Button></div>
    </Dialog>
  )
}
