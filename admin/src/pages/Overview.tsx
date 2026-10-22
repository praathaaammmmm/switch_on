import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import type { AdminMeta, Overview } from '../api/types'
import { dateOnly, dateTime, inr } from '../lib/format'
import { Empty, Eyebrow, Kpi, PageHead, Panel, QueryView } from '../components/ui'
import { RepairTable } from './Repairs'
import { AuditTable } from './Audit'

export function OverviewPage() {
  const q = useQuery({ queryKey: ['overview'], queryFn: () => api<Overview>('/overview'), refetchInterval: 60_000 })
  const meta = useQuery({ queryKey: ['meta'], queryFn: () => api<AdminMeta>('/meta'), staleTime: Infinity })
  return (
    <>
      <PageHead eyebrow={q.data ? `Today · ${dateOnly(q.data.today)}` : 'Today'} title="Overview" />
      <QueryView q={q} rows={6}>
        {(o) => (
          <>
            <div className="stack">
              <Eyebrow>Operations</Eyebrow>
              <div className="kpis">
                <Kpi label="Bookings today" value={o.bookingsToday} sub={`${o.bookings7d} in 7 days`} to="/repairs" />
                <Kpi label="Active repairs" value={o.activeRepairs} to="/repairs?status=active" />
                <Kpi label="Pickups today" value={o.pickupsToday} to="/logistics" />
                <Kpi label="Out for delivery" value={o.deliveriesToday} to="/repairs?status=RETURN_SCHEDULED" />
                <Kpi label="Quotes awaiting customer" value={o.pendingQuotes} to="/quotes" />
                <Kpi label="Quote drafts" value={o.draftQuotes} to="/quotes" />
                <Kpi label="Delayed repairs" value={o.delayedRepairs} alert={o.delayedRepairs > 0} to="/repairs?status=delayed" />
              </div>
            </div>
            <div className="stack">
              <Eyebrow>Money and service</Eyebrow>
              <div className="kpis">
                <Kpi label="Revenue today" value={inr(o.revenueToday)} to="/payments" />
                <Kpi label="Revenue 7 days" value={inr(o.revenue7d)} to="/payments" />
                <Kpi label="Revenue 30 days" value={inr(o.revenue30d)} sub={o.refunds30d ? `${inr(o.refunds30d)} refunded` : undefined} to="/payments" />
                <Kpi label="Unreconciled payments" value={o.unreconciledPayments} alert={o.unreconciledPayments > 0} to="/payments" />
                <Kpi label="Open warranty claims" value={o.openClaims} alert={o.openClaims > 0} to="/claims" />
                <Kpi label="Open support tickets" value={o.openTickets} sub={`${o.unassignedTickets} unassigned`} alert={o.unassignedTickets > 0} to="/support" />
              </div>
            </div>
            <div className="grid-2">
              <Panel title="Delayed repairs" alert={o.delayed.length > 0} actions={<Link className="link" to="/repairs?status=delayed">All delayed</Link>}>
                {o.delayed.length === 0 ? <Empty title="Nothing delayed">Every active repair is inside its stage's time limit.</Empty> : <RepairTable rows={o.delayed} compact />}
              </Panel>
              <Panel title="Repairs by status">
                <table className="data">
                  <tbody>
                    {(meta.data?.statuses ?? []).filter((s) => o.statusCounts[s.code]).map((s) => (
                      <tr key={s.code}>
                        <td><Link to={`/repairs?status=${s.code}`}>{s.title}</Link></td>
                        <td className="right num mono">{o.statusCounts[s.code]}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </Panel>
            </div>
            <Panel title="Recent admin activity" actions={<Link className="link" to="/audit">Audit log</Link>}>
              {o.recentActivity.length === 0 ? <Empty title="No admin changes yet" /> : <AuditTable rows={o.recentActivity} />}
              <span className="hint">Updated {dateTime(new Date().toISOString())}</span>
            </Panel>
          </>
        )}
      </QueryView>
    </>
  )
}
