import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api, errorMessage } from '../api/client'
import type { Invoice, Payments } from '../api/types'
import { useCan } from '../auth/AuthContext'
import { dateOnly, dateTime, inr } from '../lib/format'
import { Button, Docket, Empty, Field, Kpi, KV, PageHead, Panel, QueryView, ReasonDialog, Tag, useAction } from '../components/ui'
import { LineItemsTable } from './RepairDetail'

export function PaymentsPage() {
  const [unreconciled, setUnreconciled] = useState(false)
  const [method, setMethod] = useState('')
  const navigate = useNavigate()
  const q = useQuery({ queryKey: ['payments', unreconciled, method], queryFn: () => api<Payments>('/payments', { query: { unreconciled, method } }) })
  return (
    <>
      <PageHead eyebrow="Payments, invoices and refunds" title="Payments & Invoices" />
      <p className="muted" style={{ margin: 0 }}>Customers pay in the app after delivery. There is no payment gateway yet: refunds recorded here must also be made in the payment provider.</p>
      <QueryView q={q}>
        {(p) => (
          <>
            <div className="kpis">
              <Kpi label="Collected" value={inr(p.totals.collected)} />
              <Kpi label="Refunded" value={inr(p.totals.refunded)} />
              <Kpi label="Net" value={inr(p.totals.net)} />
              <Kpi label="Unreconciled" value={p.totals.unreconciled} alert={p.totals.unreconciled > 0} />
            </div>
            <div className="toolbar">
              <Field label="Method">{(id) => (
                <select id={id} className="select" value={method} onChange={(e) => setMethod(e.target.value)}>
                  <option value="">All methods</option><option value="UPI">UPI</option><option value="CARD">Card</option><option value="CASH">Cash</option>
                </select>
              )}</Field>
              <label className="check" style={{ paddingBottom: 10 }}><input type="checkbox" checked={unreconciled} onChange={(e) => setUnreconciled(e.target.checked)} /> Unreconciled only</label>
            </div>
            {p.items.length === 0 ? <Empty title="No payments match" /> : (
              <div className="table-wrap">
                <table className="data">
                  <thead><tr><th>Repair</th><th>Invoice</th><th>Customer</th><th>Method</th><th className="right">Paid</th><th className="right">Refunded</th><th className="right">Net</th><th>Paid at</th><th>Reconciled</th></tr></thead>
                  <tbody>
                    {p.items.map((r) => (
                      <tr key={r.repairId} className="clickable" onClick={() => navigate(`/payments/${r.repairId}`)}>
                        <td><Link className="mono" to={`/payments/${r.repairId}`} onClick={(e) => e.stopPropagation()}>{r.repairId}</Link></td>
                        <td className="mono small">{r.invoiceNo ?? '—'}</td>
                        <td>{r.customerName}</td>
                        <td><Tag tone="muted">{r.method}</Tag></td>
                        <td className="right num">{inr(r.amount)}</td>
                        <td className="right num">{r.refunded ? inr(r.refunded) : '—'}</td>
                        <td className="right num"><strong>{inr(r.net)}</strong></td>
                        <td className="small">{dateTime(r.at)}</td>
                        <td>{r.reconciledAt ? <Tag tone="done">Reconciled</Tag> : <Tag tone="delay">Pending</Tag>}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </>
        )}
      </QueryView>
    </>
  )
}

export function InvoicePage() {
  const { repairId = '' } = useParams()
  const can = useCan()('payments.manage')
  const q = useQuery({ queryKey: ['invoice', repairId], queryFn: () => api<Invoice>(`/repairs/${repairId}/invoice`) })
  const [refunding, setRefunding] = useState(false)
  const [reconciling, setReconciling] = useState(false)
  const [amount, setAmount] = useState('')
  const refund = useAction((reason: string) => api<Invoice>(`/payments/${repairId}/refunds`, { method: 'POST', body: { amount: Number(amount), reason } }), {
    invalidate: [['invoice', repairId], ['payments'], ['overview'], ['repair', repairId]], success: 'Refund recorded.', onSuccess: () => { setRefunding(false); setAmount('') },
  })
  const reconcile = useAction((note: string) => api<Invoice>(`/payments/${repairId}/reconcile`, { method: 'POST', body: { note } }), {
    invalidate: [['invoice', repairId], ['payments'], ['overview']], success: 'Marked as reconciled.', onSuccess: () => setReconciling(false),
  })
  return (
    <QueryView q={q}>
      {({ invoice: inv, payment, refunds }) => {
        const remaining = payment.amount - payment.refunded
        return (
          <>
            <PageHead eyebrow={`Repair ${repairId}`} title={inv.invoiceNo}>
              <div className="row">
                <Link className="btn ghost" to={`/repairs/${repairId}`}>Open repair</Link>
                {can && !payment.reconciledAt && <Button onClick={() => setReconciling(true)}>Mark reconciled</Button>}
                {can && remaining > 0 && <Button kind="danger" onClick={() => setRefunding(true)}>Record refund</Button>}
              </div>
            </PageHead>
            <div className="grid-2">
              <Docket head="Tax invoice" right={dateOnly(inv.issuedAt)}>
                <KV rows={[['Billed to', inv.billedTo], ['Address', inv.address], ['GSTIN', <span className="mono">{inv.gstin}</span>]]} />
                <LineItemsTable items={inv.items} />
                <hr className="dashed" />
                <KV rows={[
                  ['Taxable value', <span className="num">{inr(inv.taxable)}</span>],
                  ['CGST 9%', <span className="num">{inr(inv.cgst)}</span>],
                  ['SGST 9%', <span className="num">{inr(inv.sgst)}</span>],
                  ['Total', <strong className="num" style={{ fontSize: 20 }}>{inr(inv.total)}</strong>],
                  ['Paid by', `${inv.payment.method} · ${dateTime(inv.payment.at)}`],
                  ['Reference', <span className="mono small">{inv.payment.reference}</span>],
                  ['Warranty until', dateOnly(inv.warrantyEndsOn)],
                ]} />
                <span className="stamp">Paid</span>
              </Docket>
              <div className="stack">
                <Panel title="Reconciliation">
                  {payment.reconciledAt
                    ? <KV rows={[['Reconciled', dateTime(payment.reconciledAt)], ['By', payment.reconciledBy], ['Note', payment.reconciledNote ?? '—']]} />
                    : <span className="muted">Not yet matched against the settlement report.</span>}
                </Panel>
                <Panel title="Refunds">
                  <KV rows={[['Paid', inr(payment.amount)], ['Refunded', inr(payment.refunded)], ['Can still refund', inr(remaining)]]} />
                  {refunds.length === 0 ? <span className="muted">No refunds.</span> : (
                    <table className="data">
                      <tbody>{refunds.map((r) => <tr key={r.id}><td className="num">{inr(r.amount)}</td><td className="small">{r.reason}<div className="muted">{r.by} · {dateTime(r.at)}</div></td></tr>)}</tbody>
                    </table>
                  )}
                </Panel>
              </div>
            </div>
            {refunding && (
              <ReasonDialog title="Record refund" danger confirm="Record refund" busy={refund.isPending} error={refund.error ? errorMessage(refund.error) : null}
                body={`Up to ${inr(remaining)}. The customer sees "Refund issued" on their repair. Make the refund in the payment provider too.`}
                onConfirm={(r) => refund.mutate(r)} onClose={() => setRefunding(false)}>
                <Field label="Amount (₹)" error={Number(amount) > remaining ? `At most ${inr(remaining)}.` : null}>{(id) => <input id={id} className="input num" type="number" min={1} max={remaining} value={amount} onChange={(e) => setAmount(e.target.value)} />}</Field>
              </ReasonDialog>
            )}
            {reconciling && (
              <ReasonDialog title="Mark reconciled" label="Settlement reference or note" minLength={0} confirm="Mark reconciled" busy={reconcile.isPending}
                error={reconcile.error ? errorMessage(reconcile.error) : null} onConfirm={(n) => reconcile.mutate(n)} onClose={() => setReconciling(false)} />
            )}
          </>
        )
      }}
    </QueryView>
  )
}
