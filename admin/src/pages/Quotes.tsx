import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import type { QuoteListItem } from '../api/types'
import { dateTime, inr } from '../lib/format'
import { Empty, PageHead, QueryView, StatusTag, Tabs } from '../components/ui'

type State = 'DRAFT' | 'PENDING' | 'APPROVED' | 'DECLINED'

export function QuotesPage() {
  const [state, setState] = useState<State>('DRAFT')
  const navigate = useNavigate()
  const q = useQuery({ queryKey: ['quotes', state], queryFn: () => api<QuoteListItem[]>('/quotes', { query: { state } }) })
  return (
    <>
      <PageHead eyebrow="Itemised quotes: parts, labour, warranty" title="Quotes" />
      <p className="muted" style={{ margin: 0 }}>Drafts are prepared on the repair while the device is at the shop, then submitted to the customer. Only the customer approves or declines.</p>
      <Tabs label="Quote state" value={state} onChange={setState} options={[
        { value: 'DRAFT', label: 'Drafts' }, { value: 'PENDING', label: 'Awaiting customer' }, { value: 'APPROVED', label: 'Approved' }, { value: 'DECLINED', label: 'Declined' },
      ]} />
      <QueryView q={q}>
        {(rows) => rows.length === 0 ? <Empty title="No quotes here">{state === 'DRAFT' ? 'Open a repair that is at the shop to start a quote.' : undefined}</Empty> : (
          <div className="table-wrap">
            <table className="data">
              <thead><tr><th>Repair</th><th>Device</th><th>Customer</th><th className="right">Quote</th><th className="right">Estimate</th><th>Lines</th><th>Repair status</th><th>{state === 'DRAFT' ? 'Saved' : 'Sent'}</th>{state !== 'DRAFT' && state !== 'PENDING' && <th>Decided</th>}</tr></thead>
              <tbody>
                {rows.map((r) => {
                  const over = r.total > r.estimateHigh
                  return (
                    <tr key={r.repairId} className="clickable" onClick={() => navigate(`/repairs/${r.repairId}`)}>
                      <td><Link className="mono" to={`/repairs/${r.repairId}`} onClick={(e) => e.stopPropagation()}>{r.repairId}</Link></td>
                      <td className="small">{r.device}</td>
                      <td>{r.contactName}</td>
                      <td className={`right num ${over ? 'red' : ''}`}>{inr(r.total)}{over && <div className="small">above estimate</div>}</td>
                      <td className="right num small muted">{inr(r.estimateLow)}–{inr(r.estimateHigh)}</td>
                      <td className="num">{r.itemCount}</td>
                      <td><StatusTag status={r.repairStatus} /></td>
                      <td className="small">{dateTime(r.at)}</td>
                      {state !== 'DRAFT' && state !== 'PENDING' && <td className="small">{dateTime(r.decidedAt)}{r.declineReason && <div className="muted">{r.declineReason}</div>}</td>}
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}
      </QueryView>
    </>
  )
}
