// Mirrors backend/src/main/kotlin/com/switchon/api/AdminDto.kt (and the customer DTOs it embeds).

export type Role = 'OWNER' | 'OPERATIONS' | 'SUPPORT' | 'FINANCE' | 'VIEWER'
export type PermissionCode =
  | 'repairs.manage' | 'repairs.note' | 'pricing.manage' | 'partners.manage' | 'areas.manage'
  | 'quotes.manage' | 'payments.manage' | 'claims.manage' | 'support.manage' | 'team.manage'

export interface AdminMe { id: string; email: string; name: string; role: Role; permissions: PermissionCode[] }
export interface StatusMeta { code: string; title: string; next: string[]; beforePickup: boolean }
export interface AdminMeta { statuses: StatusMeta[]; roles: Record<Role, PermissionCode[]> }
export interface StaffOption { id: string; name: string; role: Role }

export interface AuditEntry {
  id: number; adminEmail: string; action: string; entityType: string; entityId: string
  reason: string | null; before: unknown; after: unknown; at: string
}
export interface AuditResponse { items: AuditEntry[]; nextBefore: number | null }

export interface Address { id: string; label: string; line1: string; line2: string; area: string; city: string; pincode: string; landmark: string; isDefault: boolean }
export interface LineItem { label: string; detail: string; kind: 'PART' | 'LABOUR' | 'FEE'; amount: number }
export interface Quote {
  diagnosis: string; findings: string[]; items: LineItem[]; total: number; warranty: string
  sentAt: string; decision: 'APPROVED' | 'DECLINED' | null; decidedAt: string | null; declineReason: string | null
}
export interface RepairEvent { at: string; status: string | null; kind: string; title: string; message: string; actorType: string; actorName: string; metadata: Record<string, string> }
export interface Handover { stage: string; sealCode: string; handledBy: string; photoCount: number; sealIntact: boolean; at: string }
export interface Payment { method: string; amount: number; reference: string; at: string }
export interface Repair {
  id: string; status: string; deviceCategory: string; issue: string; brand: string | null; model: string | null
  symptoms: string[]; notes: string; photoCount: number; estimateLow: number; estimateHigh: number; turnaround: string
  address: Address; slot: { date: string; startHour: number }; contactName: string; contactPhone: string
  createdAt: string; updatedAt: string
  partner: { name: string; phone: string; vehicle: string; rating: number; trips: number } | null
  shop: { name: string; area: string; rating: number; reviews: number; technician: string; verifiedSince: string } | null
  sealCode: string | null; quote: Quote | null; events: RepairEvent[]; handovers: Handover[]; payment: Payment | null
  invoiceNo: string | null; warranty: { startsOn: string; endsOn: string; coverage: string } | null
  deliveredAt: string | null; cancelledAt: string | null; cancelReason: string | null; warrantyClaimFor: string | null
  amountDue: number; canCancel: boolean; canReschedule: boolean
}

export interface RepairSummary {
  id: string; status: string; statusTitle: string; deviceCategory: string; issue: string; brand: string | null; model: string | null
  customerId: string; customerName: string | null; contactName: string; contactPhone: string; city: string; pincode: string
  slotDate: string; slotStartHour: number; estimateLow: number; estimateHigh: number; createdAt: string; updatedAt: string
  partnerName: string | null; shopName: string | null; technicianName: string | null; delayed: boolean; delayReason: string | null
  warrantyClaimFor: string | null
}
export interface RepairList { items: RepairSummary[]; total: number }
export interface NextStatus { status: string; title: string; allowed: boolean; needsNote: boolean; hint: string }
export interface InternalNote { id: number; body: string; author: string; at: string }
export interface CustomerSummary {
  id: string; kind: string; name: string | null; phone: string | null; email: string | null; createdAt: string
  repairs: number; activeRepairs: number; lastRepairAt: string | null
}
export interface QuoteDraft { repairId: string; diagnosis: string; findings: string[]; items: LineItem[]; warrantyText: string; total: number; updatedBy: string; updatedAt: string }
export interface Refund { id: string; amount: number; reason: string; by: string; at: string }
export interface Claim {
  id: string; customerId: string; customerName: string | null; originalRepairId: string; claimRepairId: string; claimRepairStatus: string
  problem: string; detail: string; status: 'OPEN' | 'INFO_REQUESTED' | 'APPROVED' | 'RESOLVED' | 'REJECTED'; decisionNote: string | null
  openedAt: string; resolvedAt: string | null; warrantyEndsOn: string | null
}
export interface TicketSummary {
  id: string; customerId: string; customerName: string | null; repairId: string | null; topic: string; status: 'OPEN' | 'CLOSED'
  assignedTo: string | null; assignedName: string | null; createdAt: string; updatedAt: string; messages: number
}
export interface TicketMessage { id: number; visibility: 'CUSTOMER' | 'INTERNAL'; body: string; author: string; at: string }
export interface TicketDetail { ticket: TicketSummary; detail: string; messages: TicketMessage[] }

export interface RepairDetail {
  repair: Repair; summary: RepairSummary; customer: CustomerSummary; nextStatuses: NextStatus[]; notes: InternalNote[]
  partnerId: string | null; shopId: string | null; technicianId: string | null; quoteDraft: QuoteDraft | null
  claim: Claim | null; tickets: TicketSummary[]; refunds: Refund[]; audit: AuditEntry[]
}

export interface CustomerList { items: CustomerSummary[]; total: number }
export interface CustomerDetail {
  customer: CustomerSummary; repairs: RepairSummary[]; addresses: { address: Address; deleted: boolean }[]
  claims: Claim[]; tickets: TicketSummary[]
}

export interface Overview {
  today: string; bookingsToday: number; bookings7d: number; activeRepairs: number; pickupsToday: number; deliveriesToday: number
  pendingQuotes: number; draftQuotes: number; delayedRepairs: number; revenueToday: number; revenue7d: number; revenue30d: number
  refunds30d: number; unreconciledPayments: number; openClaims: number; openTickets: number; unassignedTickets: number
  statusCounts: Record<string, number>; delayed: RepairSummary[]; recentActivity: AuditEntry[]
}

export interface Category { code: string; label: string; sortOrder: number; active: boolean }
export interface Issue { code: string; label: string; hint: string; sortOrder: number }
export interface ServiceEntry {
  category: string; issue: string; label: string; basePrice: number; diagnosisFee: number; turnaround: string
  labelOverride: string | null; active: boolean; updatedAt: string; bookings: number
}
export interface Brand { id: number; category: string; name: string; multiplier: number }
export interface Catalog { categories: Category[]; issues: Issue[]; entries: ServiceEntry[]; brands: Brand[] }
export interface PriceHistory {
  id: number; scope: 'SERVICE' | 'BRAND'; category: string; issue: string | null; brand: string | null; field: string
  oldValue: string | null; newValue: string | null; reason: string; changedBy: string; at: string
}
export interface ServiceDetail { entry: ServiceEntry; estimates: { brand: string | null; low: number; high: number }[]; history: PriceHistory[] }

export interface Technician { id: string; shopId: string; name: string; skills: string[]; active: boolean; activeJobs: number }
export interface Shop {
  id: string; name: string; area: string; rating: number; reviews: number; verifiedSince: string; active: boolean
  capacity: number; capabilities: string[]; activeJobs: number; technicians: Technician[]
}
export interface Courier {
  id: string; name: string; phone: string; vehicle: string; rating: number; trips: number; active: boolean
  dailyCapacity: number; assignedToday: number; activeJobs: number
}
export interface Partners { shops: Shop[]; couriers: Courier[] }

export interface Areas {
  areas: { prefix: string; city: string; active: boolean }[]
  templates: { startHour: number; capacity: number }[]
  blackouts: { date: string; startHour: number; reason: string }[]
}
export interface LogisticsDay {
  date: string
  slots: { startHour: number; capacity: number; booked: number; blocked: boolean; blockReason: string | null }[]
  pickups: RepairSummary[]; deliveries: RepairSummary[]
}

export interface QuoteListItem {
  repairId: string; state: 'DRAFT' | 'PENDING' | 'APPROVED' | 'DECLINED'; total: number; itemCount: number
  estimateLow: number; estimateHigh: number; contactName: string; device: string; repairStatus: string; at: string
  decidedAt: string | null; declineReason: string | null
}

export interface PaymentRow {
  repairId: string; method: string; amount: number; reference: string; at: string; refunded: number; net: number
  reconciledAt: string | null; reconciledBy: string | null; reconciledNote: string | null; invoiceNo: string | null; customerName: string
}
export interface Payments { items: PaymentRow[]; totals: { collected: number; refunded: number; net: number; unreconciled: number } }
export interface Invoice {
  invoice: {
    invoiceNo: string; repairId: string; issuedAt: string; billedTo: string; address: string; items: LineItem[]
    taxable: number; cgst: number; sgst: number; total: number; payment: Payment; warrantyEndsOn: string | null; gstin: string
  }
  payment: PaymentRow; refunds: Refund[]
}

export interface AdminUser { id: string; email: string; name: string; role: Role; active: boolean; createdAt: string; lastLoginAt: string | null }
