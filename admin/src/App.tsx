import { Navigate, Route, Routes, useLocation } from 'react-router-dom'
import type { ReactNode } from 'react'
import { useAuth } from './auth/AuthContext'
import type { PermissionCode } from './api/types'
import { Layout } from './components/Layout'
import { Empty, Loading } from './components/ui'
import { LoginPage } from './pages/Login'
import { OverviewPage } from './pages/Overview'
import { RepairsPage } from './pages/Repairs'
import { RepairDetailPage } from './pages/RepairDetail'
import { CustomersPage, CustomerDetailPage } from './pages/Customers'
import { PricingPage } from './pages/Pricing'
import { PartnersPage } from './pages/Partners'
import { LogisticsPage } from './pages/Logistics'
import { QuotesPage } from './pages/Quotes'
import { PaymentsPage, InvoicePage } from './pages/Payments'
import { ClaimsPage } from './pages/Claims'
import { SupportPage, TicketPage } from './pages/Support'
import { TeamPage } from './pages/Team'
import { AuditPage } from './pages/Audit'

function RequireAuth({ children }: { children: ReactNode }) {
  const { me, loading } = useAuth()
  const location = useLocation()
  if (loading) return <div className="page"><Loading /></div>
  if (!me) return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />
  return <>{children}</>
}

function RequirePermission({ permission, children }: { permission: PermissionCode; children: ReactNode }) {
  const { can } = useAuth()
  return can(permission) ? <>{children}</> : <Empty title="Not available for your role">Ask an owner if you need access.</Empty>
}

export function App() {
  const { me } = useAuth()
  return (
    <Routes>
      <Route path="/login" element={me ? <Navigate to="/" replace /> : <LoginPage />} />
      <Route element={<RequireAuth><Layout /></RequireAuth>}>
        <Route index element={<OverviewPage />} />
        <Route path="repairs" element={<RepairsPage />} />
        <Route path="repairs/:id" element={<RepairDetailPage />} />
        <Route path="customers" element={<CustomersPage />} />
        <Route path="customers/:id" element={<CustomerDetailPage />} />
        <Route path="pricing" element={<PricingPage />} />
        <Route path="partners" element={<PartnersPage />} />
        <Route path="logistics" element={<LogisticsPage />} />
        <Route path="quotes" element={<QuotesPage />} />
        <Route path="payments" element={<PaymentsPage />} />
        <Route path="payments/:repairId" element={<InvoicePage />} />
        <Route path="claims" element={<ClaimsPage />} />
        <Route path="support" element={<SupportPage />} />
        <Route path="support/:id" element={<TicketPage />} />
        <Route path="team" element={<RequirePermission permission="team.manage"><TeamPage /></RequirePermission>} />
        <Route path="audit" element={<AuditPage />} />
        <Route path="*" element={<Empty title="Page not found" />} />
      </Route>
    </Routes>
  )
}
