import { useEffect, useMemo, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api, errorMessage } from '../api/client'
import type { Areas, LineItem, NextStatus, Partners, QuoteDraft, RepairDetail } from '../api/types'
import { Can, useCan } from '../auth/AuthContext'
import { dateOnly, dateTime, device, hour12, inr, slotLabel, todayIst } from '../lib/format'
import { Button, Dialog, Docket, Empty, Field, KV, PageHead, Panel, QueryView, ReasonDialog, StatusTag, Tag, useAction } from '../components/ui'
import { AuditTable } from './Audit'

const key = (id: string) => ['repair', id]

export function RepairDetailPage() {
  const { id = '' } = useParams()
  const q = useQuery({ queryKey: key(id), queryFn: () => api<RepairDetail>(`/repairs/${id}`) })
  return <QueryView q={q} rows={8}>{(d) => <RepairView d={d} />}</QueryView>
}

function RepairView({ d }: { d: RepairDetail }) {
  const r = d.repair
  const s = d.summary
  return (
    <>
      <PageHead eyebrow={device(s)} title={r.id}>
        <div className="row">
          <StatusTag status={r.status} title={s.statusTitle} />
          {s.delayed && <span className="tag delay">{s.delayReason}</span>}
          {r.warrantyClaimFor && <Tag tone="muted">Warranty job for {r.warrantyClaimFor}</Tag>}
        </div>
      </PageHead>
      <div className="grid-2">
        <div className="stack">
          <Docket head="Job card" right={`Booked ${dateTime(r.createdAt)}`} className="scan">
            <KV rows={[
              ['Device', device(s)],
              ['Symptoms', r.symptoms.join(', ') || '—'],
              ['Customer notes', r.notes || '—'],
              ['Estimate', r.warrantyClaimFor ? `Covered by warranty · ${r.turnaround}` : <span className="num">{inr(r.estimateLow)} – {inr(r.estimateHigh)} · {r.turnaround}</span>],
              ['Customer', <Link to={`/customers/${s.customerId}`}>{d.customer.name ?? 'Guest'}</Link>],
              ['Contact', <span className="mono">{r.contactName} · {r.contactPhone}</span>],
              ['Pickup address', [r.address.line1, r.address.line2, r.address.area, `${r.address.city} ${r.address.pincode}`].filter(Boolean).join(', ')],
              ['Pickup window', slotLabel(r.slot.date, r.slot.startHour)],
              ['Seal', r.sealCode ? <span className="mono">{r.sealCode}</span> : '—'],
              ['Pickup partner', r.partner ? `${r.partner.name} · ${r.partner.vehicle}` : 'Not assigned'],
              ['Repair shop', r.shop ? `${r.shop.name} · ${r.shop.area}` : 'Not assigned'],
              ['Technician', s.technicianName ?? '—'],
            ]} />
            {r.cancelReason && <p><strong>Cancelled:</strong> {r.cancelReason}</p>}
          </Docket>
          <QuotePanel d={d} />
          {r.payment && <PaymentPanel d={d} />}
          <Panel title="Customer timeline">
            <span className="hint">What the customer sees in the app, oldest first.</span>
            <ol className="timeline">
              {r.events.map((e, i) => (
                <li key={i} className={e.kind === 'ALERT' ? 'alert' : ''}>
                  <div className="stack" style={{ gap: 2 }}>
                    <div className="row"><strong>{e.title}</strong>{e.status && <span className="tag muted">{e.status.replace(/_/g, ' ')}</span>}</div>
                    {e.message && <span>{e.message}</span>}
                    <span className="label">{dateTime(e.at)} · {e.actorName}{e.metadata.recordedBy === 'admin' ? ' · recorded by staff' : ''}</span>
                  </div>
                </li>
              ))}
            </ol>
          </Panel>
        </div>
        <div className="stack">
          <StatusControl d={d} />
          <Can permission="repairs.manage"><AssignPanel d={d} /></Can>
          {r.canReschedule && <Can permission="repairs.manage"><ReschedulePanel d={d} /></Can>}
          <NotesPanel d={d} />
          {d.claim && (
            <Panel title="Warranty claim">
              <KV rows={[
                ['Status', <StatusTag status={d.claim.status} />],
                ['Problem', d.claim.problem],
                ['Original repair', <Link to={`/repairs/${d.claim.originalRepairId}`}>{d.claim.originalRepairId}</Link>],
                ['Claim job', <Link to={`/repairs/${d.claim.claimRepairId}`}>{d.claim.claimRepairId}</Link>],
              ]} />
              <Link className="link" to="/claims">Review in Warranty Claims</Link>
            </Panel>
          )}
          {d.tickets.length > 0 && (
            <Panel title="Support tickets">
              {d.tickets.map((t) => (
                <div key={t.id} className="row between">
                  <Link className="mono" to={`/support/${t.id}`}>{t.id}</Link><span>{t.topic}</span><StatusTag status={t.status} />
                </div>
              ))}
            </Panel>
          )}
          <Panel title="Audit trail">
            {d.audit.length === 0 ? <Empty title="No staff changes yet" /> : <AuditTable rows={d.audit} compact />}
          </Panel>
        </div>
      </div>
    </>
  )
}

// ---- Status --------------------------------------------------------------------------------------------------

function StatusControl({ d }: { d: RepairDetail }) {
  const can = useCan()
  const [target, setTarget] = useState<NextStatus | null>(null)
  const [note, setNote] = useState('')
  const change = useAction((v: { to: string; note: string }) => api<RepairDetail>(`/repairs/${d.repair.id}/status`, { method: 'POST', body: v }), {
    invalidate: [key(d.repair.id), ['repairs'], ['overview']],
    success: (r) => `Moved to ${r.summary.statusTitle}.`,
    onSuccess: () => { setTarget(null); setNote('') },
  })
  const next = d.nextStatuses
  return (
    <Panel title="Status">
      <div className="row"><span className="label">Now</span><StatusTag status={d.repair.status} title={d.summary.statusTitle} /></div>
      {next.length === 0 ? (
        <span className="muted">This repair has reached the end of its lifecycle.</span>
      ) : (
        <div className="stack" style={{ gap: 8 }}>
          <span className="label">Next steps the lifecycle allows</span>
          {next.map((n) => (
            <div key={n.status} className="row between" style={{ borderTop: '1px solid var(--line)', paddingTop: 8 }}>
              <div className="stack" style={{ gap: 2, flex: 1, minWidth: 200 }}>
                <strong>{n.title}</strong>
                <span className={`small ${n.allowed ? 'muted' : ''}`}>{n.hint}</span>
              </div>
              <Button small kind={n.status === 'CANCELLED' || n.status === 'PICKUP_FAILED' || n.status === 'REPAIR_NOT_POSSIBLE' ? 'danger' : 'outline'}
                disabled={!n.allowed || !can('repairs.manage')} onClick={() => { setTarget(n); setNote('') }}
                title={!can('repairs.manage') ? 'Your role can view but not change repairs' : undefined}>
                Move here
              </Button>
            </div>
          ))}
        </div>
      )}
      {target && (
        <Dialog title={`Move to ${target.title}`} onClose={() => setTarget(null)}>
          <p className="muted">{target.hint}</p>
          <Field label={target.needsNote ? 'Note (required)' : 'Note (optional)'} hint={target.status === 'CANCELLED' ? 'The customer sees this as the cancellation reason.' : 'Shown on the customer timeline where relevant, and kept in the audit log.'}>
            {(id) => <textarea id={id} className="textarea" value={note} onChange={(e) => setNote(e.target.value)} maxLength={300} />}
          </Field>
          {change.error && <p className="error-text">{errorMessage(change.error)}</p>}
          <div className="row">
            <Button kind="primary" disabled={target.needsNote && note.trim().length < 3} busy={change.isPending} onClick={() => change.mutate({ to: target.status, note: note.trim() })}>Confirm</Button>
            <Button kind="ghost" onClick={() => setTarget(null)}>Back</Button>
          </div>
        </Dialog>
      )}
    </Panel>
  )
}

// ---- Assignment ----------------------------------------------------------------------------------------------

function AssignPanel({ d }: { d: RepairDetail }) {
  const partners = useQuery({ queryKey: ['partners'], queryFn: () => api<Partners>('/partners') })
  const [partnerId, setPartnerId] = useState(d.partnerId ?? '')
  const [shopId, setShopId] = useState(d.shopId ?? '')
  const [technicianId, setTechnicianId] = useState(d.technicianId ?? '')
  useEffect(() => { setPartnerId(d.partnerId ?? ''); setShopId(d.shopId ?? ''); setTechnicianId(d.technicianId ?? '') }, [d.partnerId, d.shopId, d.technicianId])
  const save = useAction(() => api<RepairDetail>(`/repairs/${d.repair.id}/assign`, {
    method: 'POST',
    body: {
      partnerId: partnerId !== (d.partnerId ?? '') ? partnerId : null,
      shopId: shopId !== (d.shopId ?? '') ? shopId : null,
      technicianId: technicianId !== (d.technicianId ?? '') ? technicianId : null,
    },
  }), { invalidate: [key(d.repair.id), ['repairs'], ['partners']], success: 'Assignment saved.' })

  const category = d.repair.deviceCategory
  const shops = partners.data?.shops.filter((s) => s.active && s.capabilities.includes(category)) ?? []
  const shop = partners.data?.shops.find((s) => s.id === shopId)
  const techs = shop?.technicians.filter((t) => t.active && (t.skills.length === 0 || t.skills.includes(category))) ?? []
  const shopLocked = !['PICKUP_REQUESTED', 'PICKUP_SCHEDULED', 'PARTNER_ASSIGNED', 'PICKUP_FAILED', 'PICKED_UP'].includes(d.repair.status)
  const closed = ['CANCELLED', 'DELIVERED', 'PAYMENT_PENDING', 'PAYMENT_COMPLETED', 'WARRANTY_ACTIVE', 'DRAFT'].includes(d.repair.status)
  const changed = partnerId !== (d.partnerId ?? '') || shopId !== (d.shopId ?? '') || technicianId !== (d.technicianId ?? '')

  if (closed) return null
  return (
    <Panel title="Assignment">
      <Field label="Pickup partner" hint={d.repair.status === 'PICKUP_SCHEDULED' ? 'Assigning moves the repair to Partner assigned.' : undefined}>
        {(id) => (
          <select id={id} className="select" value={partnerId} onChange={(e) => setPartnerId(e.target.value)}>
            <option value="">Not assigned</option>
            {partners.data?.couriers.filter((c) => c.active || c.id === d.partnerId).map((c) => (
              <option key={c.id} value={c.id}>{c.name} · {c.vehicle} · {c.assignedToday}/{c.dailyCapacity} today</option>
            ))}
          </select>
        )}
      </Field>
      <Field label="Repair shop" hint={shopLocked ? 'Locked: the device is already at the shop.' : `Only active shops that repair ${category.toLowerCase()} devices.`}>
        {(id) => (
          <select id={id} className="select" value={shopId} disabled={shopLocked} onChange={(e) => { setShopId(e.target.value); setTechnicianId('') }}>
            <option value="">Not assigned</option>
            {shops.map((s) => <option key={s.id} value={s.id}>{s.name} · {s.activeJobs}/{s.capacity} jobs</option>)}
          </select>
        )}
      </Field>
      <Field label="Technician">
        {(id) => (
          <select id={id} className="select" value={technicianId} disabled={!shopId} onChange={(e) => setTechnicianId(e.target.value)}>
            <option value="">{shopId ? 'Not assigned' : 'Choose a shop first'}</option>
            {techs.map((t) => <option key={t.id} value={t.id}>{t.name} · {t.activeJobs} {t.activeJobs === 1 ? 'job' : 'jobs'}</option>)}
          </select>
        )}
      </Field>
      <div className="row"><Button kind="paper" disabled={!changed} busy={save.isPending} onClick={() => save.mutate(undefined)}>Save assignment</Button></div>
    </Panel>
  )
}

// ---- Reschedule and cancel -----------------------------------------------------------------------------------

function ReschedulePanel({ d }: { d: RepairDetail }) {
  const areas = useQuery({ queryKey: ['areas'], queryFn: () => api<Areas>('/areas') })
  const [date, setDate] = useState(d.repair.slot.date)
  const [hour, setHour] = useState(d.repair.slot.startHour)
  const [confirming, setConfirming] = useState<'reschedule' | 'cancel' | null>(null)
  const reschedule = useAction((reason: string) => api<RepairDetail>(`/repairs/${d.repair.id}/reschedule`, { method: 'POST', body: { date, startHour: hour, reason } }), {
    invalidate: [key(d.repair.id), ['repairs'], ['logistics']], success: 'Pickup rescheduled.', onSuccess: () => setConfirming(null),
  })
  const cancel = useAction((reason: string) => api<RepairDetail>(`/repairs/${d.repair.id}/cancel`, { method: 'POST', body: { reason } }), {
    invalidate: [key(d.repair.id), ['repairs'], ['overview']], success: 'Repair cancelled.', onSuccess: () => setConfirming(null),
  })
  const blocked = areas.data?.blackouts.find((b) => b.date === date && b.startHour === hour)
  return (
    <Panel title="Pickup window">
      <span className="muted">Now: {slotLabel(d.repair.slot.date, d.repair.slot.startHour)}</span>
      <div className="form-grid">
        <Field label="Date">{(id) => <input id={id} className="input" type="date" min={todayIst()} value={date} onChange={(e) => setDate(e.target.value)} />}</Field>
        <Field label="Window" error={blocked ? `Blocked: ${blocked.reason}` : null}>
          {(id) => (
            <select id={id} className="select" value={hour} onChange={(e) => setHour(Number(e.target.value))}>
              {areas.data?.templates.filter((t) => t.capacity > 0).map((t) => <option key={t.startHour} value={t.startHour}>{hour12(t.startHour)} – {hour12(t.startHour + 2)}</option>)}
            </select>
          )}
        </Field>
      </div>
      <div className="row">
        <Button kind="paper" disabled={!!blocked || (date === d.repair.slot.date && hour === d.repair.slot.startHour)} onClick={() => setConfirming('reschedule')}>Reschedule</Button>
        {d.repair.canCancel && <Button kind="danger" onClick={() => setConfirming('cancel')}>Cancel repair</Button>}
      </div>
      {confirming === 'reschedule' && (
        <ReasonDialog title="Reschedule pickup" body={`New window: ${slotLabel(date, hour)}. The customer sees the new window in the app.`} minLength={3}
          confirm="Reschedule" busy={reschedule.isPending} error={reschedule.error ? errorMessage(reschedule.error) : null}
          onConfirm={(reason) => reschedule.mutate(reason)} onClose={() => setConfirming(null)} />
      )}
      {confirming === 'cancel' && (
        <ReasonDialog title="Cancel repair" body="Cancels before pickup at no charge. The reason is shown to the customer." minLength={3} danger
          confirm="Cancel repair" busy={cancel.isPending} error={cancel.error ? errorMessage(cancel.error) : null}
          onConfirm={(reason) => cancel.mutate(reason)} onClose={() => setConfirming(null)} />
      )}
    </Panel>
  )
}

// ---- Internal notes ------------------------------------------------------------------------------------------

function NotesPanel({ d }: { d: RepairDetail }) {
  const [body, setBody] = useState('')
  const add = useAction(() => api<RepairDetail>(`/repairs/${d.repair.id}/notes`, { method: 'POST', body: { body: body.trim() } }), {
    invalidate: [key(d.repair.id)], success: 'Note added.', onSuccess: () => setBody(''),
  })
  return (
    <Panel title="Internal notes">
      <span className="hint">Staff only. Never shown to the customer. Notes can't be edited or deleted.</span>
      {d.notes.length === 0 && <span className="muted">No notes yet.</span>}
      {d.notes.map((n) => (
        <div key={n.id} className="message internal">
          <span style={{ whiteSpace: 'pre-wrap' }}>{n.body}</span>
          <span className="label">{n.author} · {dateTime(n.at)}</span>
        </div>
      ))}
      <Can permission="repairs.note">
        <Field label="Add a note">{(id) => <textarea id={id} className="textarea" value={body} onChange={(e) => setBody(e.target.value)} maxLength={2000} />}</Field>
        <div className="row"><Button kind="paper" disabled={!body.trim()} busy={add.isPending} onClick={() => add.mutate(undefined)}>Add note</Button></div>
      </Can>
    </Panel>
  )
}

// ---- Quote ---------------------------------------------------------------------------------------------------

function QuotePanel({ d }: { d: RepairDetail }) {
  const quote = d.repair.quote
  const editable = ['AT_SHOP', 'DIAGNOSED'].includes(d.repair.status) && !quote
  if (quote) {
    return (
      <Docket head="Quote" right={quote.decision ?? 'Awaiting customer'}>
        <p><strong>Diagnosis:</strong> {quote.diagnosis || '—'}</p>
        {quote.findings.length > 0 && <ul className="small">{quote.findings.map((f) => <li key={f}>{f}</li>)}</ul>}
        <LineItemsTable items={quote.items} />
        <KV rows={[
          ['Total', <strong className="num">{inr(quote.total)}</strong>],
          ['Estimate', `${inr(d.repair.estimateLow)} – ${inr(d.repair.estimateHigh)}`],
          ['Warranty', quote.warranty],
          ['Sent', dateTime(quote.sentAt)],
          ['Decided', quote.decidedAt ? `${quote.decision} · ${dateTime(quote.decidedAt)}` : 'Waiting for the customer'],
        ]} />
        {quote.declineReason && <p><strong>Decline reason:</strong> {quote.declineReason}</p>}
      </Docket>
    )
  }
  if (editable) return <Can permission="quotes.manage" fallback={<Panel title="Quote"><span className="muted">The shop is preparing the quote.</span></Panel>}><QuoteEditor d={d} /></Can>
  return null
}

export function LineItemsTable({ items }: { items: LineItem[] }) {
  return (
    <div className="table-wrap">
      <table className="data">
        <thead><tr><th>Line</th><th>Kind</th><th className="right">Amount</th></tr></thead>
        <tbody>
          {items.map((l, i) => <tr key={i}><td>{l.label}<div className="small muted">{l.detail}</div></td><td className="mono small">{l.kind}</td><td className="right num">{inr(l.amount)}</td></tr>)}
        </tbody>
      </table>
    </div>
  )
}

const blankLine = (): LineItem => ({ label: '', detail: '', kind: 'PART', amount: 0 })

function QuoteEditor({ d }: { d: RepairDetail }) {
  const draft = d.quoteDraft
  const [diagnosis, setDiagnosis] = useState(draft?.diagnosis ?? '')
  const [findings, setFindings] = useState((draft?.findings ?? []).join('\n'))
  const [items, setItems] = useState<LineItem[]>(draft?.items.length ? draft.items : [blankLine(), { ...blankLine(), kind: 'LABOUR', label: 'Labour' }, { label: 'Pickup and drop', detail: 'Doorstep, both ways', kind: 'FEE', amount: 0 }])
  const [warranty, setWarranty] = useState(draft?.warrantyText ?? '90 days on parts and labour')
  const [confirm, setConfirm] = useState(false)
  const total = useMemo(() => items.reduce((n, l) => n + (Number.isFinite(l.amount) ? l.amount : 0), 0), [items])
  const body = () => ({ diagnosis: diagnosis.trim(), findings: findings.split('\n').map((f) => f.trim()).filter(Boolean), items: items.filter((l) => l.label.trim()), warrantyText: warranty.trim() })
  const save = useAction(() => api<QuoteDraft>(`/repairs/${d.repair.id}/quote-draft`, { method: 'PUT', body: body() }), { invalidate: [key(d.repair.id), ['quotes']], success: 'Draft saved.' })
  const submit = useAction(async () => {
    await api(`/repairs/${d.repair.id}/quote-draft`, { method: 'PUT', body: body() })
    return api<RepairDetail>(`/repairs/${d.repair.id}/quote/submit`, { method: 'POST', body: {} })
  }, { invalidate: [key(d.repair.id), ['quotes'], ['repairs'], ['overview']], success: 'Quote sent to the customer.', onSuccess: () => setConfirm(false) })
  const setLine = (i: number, change: Partial<LineItem>) => setItems((list) => list.map((l, j) => (j === i ? { ...l, ...change } : l)))
  const verdict = total < d.repair.estimateLow ? 'below the estimate' : total <= d.repair.estimateHigh ? 'within the estimate' : `${inr(total - d.repair.estimateHigh)} above the estimate`

  return (
    <Panel title="Quote draft" actions={draft && <span className="label">Saved {dateTime(draft.updatedAt)} · {draft.updatedBy}</span>}>
      <Field label="Diagnosis" hint="Shown to the customer with the quote.">{(id) => <textarea id={id} className="textarea" value={diagnosis} onChange={(e) => setDiagnosis(e.target.value)} maxLength={300} />}</Field>
      <Field label="Findings" hint="One per line.">{(id) => <textarea id={id} className="textarea" value={findings} onChange={(e) => setFindings(e.target.value)} />}</Field>
      <div className="table-wrap">
        <table className="data editor">
          <colgroup><col style={{ width: '34%' }} /><col style={{ width: '24%' }} /><col style={{ width: '16%' }} /><col style={{ width: '16%' }} /><col style={{ width: '10%' }} /></colgroup>
          <thead><tr><th>Line</th><th>Detail</th><th>Kind</th><th className="right">Amount (₹)</th><th /></tr></thead>
          <tbody>
            {items.map((l, i) => (
              <tr key={i}>
                <td><input aria-label={`Line ${i + 1} label`} className="input" value={l.label} onChange={(e) => setLine(i, { label: e.target.value })} /></td>
                <td><input aria-label={`Line ${i + 1} detail`} className="input" value={l.detail} onChange={(e) => setLine(i, { detail: e.target.value })} /></td>
                <td>
                  <select aria-label={`Line ${i + 1} kind`} className="select" value={l.kind} onChange={(e) => setLine(i, { kind: e.target.value as LineItem['kind'] })}>
                    <option value="PART">Part</option><option value="LABOUR">Labour</option><option value="FEE">Fee</option>
                  </select>
                </td>
                <td><input aria-label={`Line ${i + 1} amount`} className="input num" type="number" min={0} step={1} value={l.amount} onChange={(e) => setLine(i, { amount: Math.max(0, Math.round(Number(e.target.value) || 0)) })} /></td>
                <td><button className="link" onClick={() => setItems((list) => list.filter((_, j) => j !== i))}>Remove</button></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="row"><Button small onClick={() => setItems((list) => [...list, blankLine()])} disabled={items.length >= 20}>Add line</Button></div>
      <Field label="Warranty">{(id) => <input id={id} className="input" value={warranty} onChange={(e) => setWarranty(e.target.value)} maxLength={80} />}</Field>
      <div className="row between">
        <div><span className="label">Total</span> <strong className="num" style={{ fontSize: 22 }}>{inr(total)}</strong> <span className="small muted">· {verdict} ({inr(d.repair.estimateLow)}–{inr(d.repair.estimateHigh)})</span></div>
        <div className="row">
          <Button kind="paper" busy={save.isPending} onClick={() => save.mutate(undefined)}>Save draft</Button>
          <Button kind="primary" disabled={!diagnosis.trim() || !items.some((l) => l.label.trim())} onClick={() => setConfirm(true)}>Submit to customer</Button>
        </div>
      </div>
      {confirm && (
        <Dialog title="Send quote to customer" onClose={() => setConfirm(false)}>
          <p>The customer will be asked to approve <strong>{inr(total)}</strong> ({verdict}). Nothing is repaired until they approve. You can't edit a quote after sending it.</p>
          {d.repair.status === 'AT_SHOP' && <p className="muted">This also records the diagnosis and moves the repair to Diagnosis complete.</p>}
          {submit.error && <p className="error-text">{errorMessage(submit.error)}</p>}
          <div className="row"><Button kind="primary" busy={submit.isPending} onClick={() => submit.mutate(undefined)}>Send quote</Button><Button kind="ghost" onClick={() => setConfirm(false)}>Back</Button></div>
        </Dialog>
      )}
    </Panel>
  )
}

// ---- Payment -------------------------------------------------------------------------------------------------

function PaymentPanel({ d }: { d: RepairDetail }) {
  const p = d.repair.payment!
  const refunded = d.refunds.reduce((n, r) => n + r.amount, 0)
  return (
    <Panel title="Payment">
      <KV rows={[
        ['Paid', <span className="num">{inr(p.amount)} · {p.method} · {dateTime(p.at)}</span>],
        ['Reference', <span className="mono small">{p.reference}</span>],
        ['Refunded', refunded ? inr(refunded) : '—'],
        ['Invoice', d.repair.invoiceNo ? <Link className="mono" to={`/payments/${d.repair.id}`}>{d.repair.invoiceNo}</Link> : '—'],
        ['Warranty', d.repair.warranty ? `${dateOnly(d.repair.warranty.startsOn)} – ${dateOnly(d.repair.warranty.endsOn)}` : '—'],
      ]} />
    </Panel>
  )
}
