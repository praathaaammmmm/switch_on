import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api, errorMessage } from '../api/client'
import type { Areas, LogisticsDay } from '../api/types'
import { useCan } from '../auth/AuthContext'
import { dateOnly, hour12, todayIst } from '../lib/format'
import { Button, Dialog, Empty, Field, PageHead, Panel, QueryView, ReasonDialog, Tag, useAction } from '../components/ui'
import { RepairTable } from './Repairs'

export function LogisticsPage() {
  const [date, setDate] = useState(todayIst())
  const day = useQuery({ queryKey: ['logistics', date], queryFn: () => api<LogisticsDay>('/logistics', { query: { date } }) })
  const areas = useQuery({ queryKey: ['areas'], queryFn: () => api<Areas>('/areas') })
  return (
    <>
      <PageHead eyebrow="Doorstep pickups and returns" title="Pickups & Service Areas">
        <Field label="Day">{(id) => <input id={id} className="input" type="date" value={date} onChange={(e) => setDate(e.target.value || todayIst())} />}</Field>
      </PageHead>
      <QueryView q={day}>
        {(d) => (
          <>
            <Panel title={`Pickup windows · ${dateOnly(d.date)}`}>
              <div className="table-wrap">
                <table className="data">
                  <thead><tr><th>Window</th><th>Booked / capacity</th><th>Status</th></tr></thead>
                  <tbody>
                    {d.slots.map((s) => {
                      const pct = s.capacity > 0 ? Math.min(100, (s.booked / s.capacity) * 100) : 100
                      return (
                        <tr key={s.startHour}>
                          <td className="mono">{hour12(s.startHour)} – {hour12(s.startHour + 2)}</td>
                          <td style={{ minWidth: 220 }}>
                            <div className="stack" style={{ gap: 4 }}>
                              <span className="small num">{s.booked} / {s.capacity}</span>
                              <div className={`bar ${s.booked >= s.capacity ? 'over' : ''}`}><span style={{ width: `${pct}%` }} /></div>
                            </div>
                          </td>
                          <td>{s.blocked ? <Tag tone="alert">Blocked · {s.blockReason}</Tag> : s.booked >= s.capacity ? <Tag tone="delay">Full</Tag> : <Tag tone="done">Open</Tag>}</td>
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
              </div>
            </Panel>
            <div className="grid-2">
              <Panel title={`Pickups · ${d.pickups.length}`}>{d.pickups.length === 0 ? <Empty title="No pickups this day" /> : <RepairTable rows={d.pickups} compact />}</Panel>
              <Panel title={`Out for delivery · ${d.deliveries.length}`}>{d.deliveries.length === 0 ? <Empty title="Nothing out for delivery" /> : <RepairTable rows={d.deliveries} compact />}</Panel>
            </div>
          </>
        )}
      </QueryView>
      <QueryView q={areas}>{(a) => <AreasPanels a={a} />}</QueryView>
    </>
  )
}

function AreasPanels({ a }: { a: Areas }) {
  const can = useCan()('areas.manage')
  const [area, setArea] = useState<{ prefix: string; city: string; active: boolean } | 'new' | null>(null)
  const [template, setTemplate] = useState<{ startHour: number; capacity: number } | 'new' | null>(null)
  const [blackout, setBlackout] = useState(false)
  const [unblock, setUnblock] = useState<{ date: string; startHour: number } | null>(null)
  const remove = useAction((reason: string) => api<Areas>('/areas/blackouts/remove', { method: 'POST', body: { ...unblock, reason } }), {
    invalidate: [['areas'], ['logistics']], success: 'Window reopened.', onSuccess: () => setUnblock(null),
  })
  const byCity = a.areas.reduce<Record<string, typeof a.areas>>((m, x) => ({ ...m, [x.city]: [...(m[x.city] ?? []), x] }), {})
  return (
    <div className="grid-3">
      <Panel title="Service areas" actions={can && <Button small onClick={() => setArea('new')}>Add</Button>}>
        <span className="hint">The first three digits of a pincode. Inactive areas can't book new pickups.</span>
        {Object.entries(byCity).map(([city, list]) => (
          <div key={city} className="stack" style={{ gap: 6 }}>
            <span className="label strong">{city}</span>
            <div className="row">
              {list.map((x) => (
                <button key={x.prefix} className={`tag ${x.active ? 'done' : 'muted'}`} style={{ cursor: can ? 'pointer' : 'default', background: 'none' }} disabled={!can} onClick={() => setArea(x)}>
                  {x.prefix}xxx{!x.active && ' · off'}
                </button>
              ))}
            </div>
          </div>
        ))}
      </Panel>
      <Panel title="Pickup windows" actions={can && <Button small onClick={() => setTemplate('new')}>Add window</Button>}>
        <span className="hint">Two-hour windows offered every day. Capacity 0 closes a window.</span>
        <table className="data">
          <tbody>
            {a.templates.map((t) => (
              <tr key={t.startHour}>
                <td className="mono">{hour12(t.startHour)} – {hour12(t.startHour + 2)}</td>
                <td className="right num">{t.capacity} pickups</td>
                <td className="right">{can && <Button small onClick={() => setTemplate(t)}>Edit</Button>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Panel>
      <Panel title="Blackout dates" actions={can && <Button small onClick={() => setBlackout(true)}>Block</Button>}>
        <span className="hint">Blocked windows can't take new bookings. Existing pickups stay; reschedule them from the repair.</span>
        {a.blackouts.length === 0 ? <span className="muted">No upcoming blackouts.</span> : (
          <table className="data">
            <tbody>
              {a.blackouts.map((b) => (
                <tr key={`${b.date}-${b.startHour}`}>
                  <td className="small">{dateOnly(b.date)}<div className="mono muted">{hour12(b.startHour)}</div></td>
                  <td className="small">{b.reason}</td>
                  <td className="right">{can && <Button small kind="ghost" onClick={() => setUnblock(b)}>Reopen</Button>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Panel>
      {area && <AreaForm area={area === 'new' ? null : area} onClose={() => setArea(null)} />}
      {template && <TemplateForm template={template === 'new' ? null : template} onClose={() => setTemplate(null)} />}
      {blackout && <BlackoutForm templates={a.templates} onClose={() => setBlackout(false)} />}
      {unblock && (
        <ReasonDialog title="Reopen window" body={`${dateOnly(unblock.date)} · ${hour12(unblock.startHour)}`} minLength={3} confirm="Reopen"
          busy={remove.isPending} error={remove.error ? errorMessage(remove.error) : null} onConfirm={(r) => remove.mutate(r)} onClose={() => setUnblock(null)} />
      )}
    </div>
  )
}

function AreaForm({ area, onClose }: { area: { prefix: string; city: string; active: boolean } | null; onClose: () => void }) {
  const [prefix, setPrefix] = useState(area?.prefix ?? '')
  const [city, setCity] = useState(area?.city ?? '')
  const [active, setActive] = useState(area?.active ?? true)
  const [reason, setReason] = useState('')
  const save = useAction(() => api<Areas>('/areas/service-areas', { method: 'PUT', body: { prefix, city, active, reason } }), {
    invalidate: [['areas']], success: 'Service area saved.', onSuccess: onClose,
  })
  return (
    <Dialog title={area ? `Service area ${area.prefix}` : 'Add service area'} onClose={onClose}>
      <div className="form-grid">
        <Field label="Pincode prefix" hint="3 digits, e.g. 560.">{(id) => <input id={id} className="input mono" inputMode="numeric" maxLength={3} value={prefix} disabled={!!area} onChange={(e) => setPrefix(e.target.value.replace(/\D/g, ''))} />}</Field>
        <Field label="City">{(id) => <input id={id} className="input" value={city} onChange={(e) => setCity(e.target.value)} />}</Field>
      </div>
      <label className="check"><input type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} /> Taking new pickups</label>
      <Field label="Note for the audit log (optional)">{(id) => <input id={id} className="input" value={reason} onChange={(e) => setReason(e.target.value)} />}</Field>
      {save.error && <p className="error-text">{errorMessage(save.error)}</p>}
      <div className="row"><Button kind="primary" disabled={!/^[1-9]\d{2}$/.test(prefix) || !city.trim()} busy={save.isPending} onClick={() => save.mutate(undefined)}>Save</Button></div>
    </Dialog>
  )
}

function TemplateForm({ template, onClose }: { template: { startHour: number; capacity: number } | null; onClose: () => void }) {
  const [hour, setHour] = useState(template?.startHour ?? 9)
  const [capacity, setCapacity] = useState(String(template?.capacity ?? 6))
  const save = useAction(() => api<Areas>('/areas/slot-templates', { method: 'PUT', body: { startHour: hour, capacity: Number(capacity) } }), {
    invalidate: [['areas'], ['logistics']], success: 'Pickup window saved.', onSuccess: onClose,
  })
  return (
    <Dialog title={template ? `Window ${hour12(template.startHour)}` : 'Add pickup window'} onClose={onClose}>
      <div className="form-grid">
        <Field label="Starts at">{(id) => (
          <select id={id} className="select" value={hour} disabled={!!template} onChange={(e) => setHour(Number(e.target.value))}>
            {Array.from({ length: 15 }, (_, i) => i + 6).map((h) => <option key={h} value={h}>{hour12(h)} – {hour12(h + 2)}</option>)}
          </select>
        )}</Field>
        <Field label="Pickups per window">{(id) => <input id={id} className="input num" type="number" min={0} max={200} value={capacity} onChange={(e) => setCapacity(e.target.value)} />}</Field>
      </div>
      {save.error && <p className="error-text">{errorMessage(save.error)}</p>}
      <div className="row"><Button kind="primary" busy={save.isPending} onClick={() => save.mutate(undefined)}>Save</Button></div>
    </Dialog>
  )
}

function BlackoutForm({ templates, onClose }: { templates: { startHour: number }[]; onClose: () => void }) {
  const [date, setDate] = useState(todayIst())
  const [hour, setHour] = useState<string>('all')
  const add = useAction((reason: string) => api<Areas>('/areas/blackouts', { method: 'POST', body: { date, startHour: hour === 'all' ? null : Number(hour), reason } }), {
    invalidate: [['areas'], ['logistics']], success: 'Blocked.', onSuccess: onClose,
  })
  return (
    <ReasonDialog title="Block pickups" confirm="Block" minLength={3} danger busy={add.isPending} error={add.error ? errorMessage(add.error) : null}
      label="Reason (e.g. Diwali, heavy rain)" onConfirm={(r) => add.mutate(r)} onClose={onClose}>
      <div className="form-grid">
        <Field label="Date">{(id) => <input id={id} className="input" type="date" min={todayIst()} value={date} onChange={(e) => setDate(e.target.value)} />}</Field>
        <Field label="Window">{(id) => (
          <select id={id} className="select" value={hour} onChange={(e) => setHour(e.target.value)}>
            <option value="all">Whole day</option>
            {templates.map((t) => <option key={t.startHour} value={t.startHour}>{hour12(t.startHour)} – {hour12(t.startHour + 2)}</option>)}
          </select>
        )}</Field>
      </div>
    </ReasonDialog>
  )
}
