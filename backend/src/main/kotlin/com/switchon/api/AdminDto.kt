package com.switchon.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Request and response bodies for /v1/admin. Money is whole rupees; times are ISO-8601 UTC instants.

// ---- Session ------------------------------------------------------------------------------------------------
@Serializable data class AdminLoginRequest(val email: String, val password: String)
@Serializable data class AdminMe(val id: String, val email: String, val name: String, val role: String, val permissions: List<String>)

// ---- Shared -------------------------------------------------------------------------------------------------
@Serializable data class ReasonRequest(val reason: String = "")
@Serializable data class NoteRequest(val body: String)

@Serializable data class AuditEntryDto(
    val id: Long, val adminEmail: String, val action: String, val entityType: String, val entityId: String,
    val reason: String?, val before: JsonElement?, val after: JsonElement?, val at: String,
)
@Serializable data class AuditResponse(val items: List<AuditEntryDto>, val nextBefore: Long?)

// ---- Repairs ------------------------------------------------------------------------------------------------
@Serializable data class RepairSummary(
    val id: String, val status: String, val statusTitle: String, val deviceCategory: String, val issue: String,
    val brand: String?, val model: String?, val customerId: String, val customerName: String?, val contactName: String,
    val contactPhone: String, val city: String, val pincode: String, val slotDate: String, val slotStartHour: Int,
    val estimateLow: Int, val estimateHigh: Int, val createdAt: String, val updatedAt: String,
    val partnerName: String?, val shopName: String?, val technicianName: String?,
    val delayed: Boolean, val delayReason: String?, val warrantyClaimFor: String?,
)
@Serializable data class RepairListResponse(val items: List<RepairSummary>, val total: Int)

/** A status the repair could move to next, and whether staff can do it from the dashboard. */
@Serializable data class NextStatusDto(val status: String, val title: String, val allowed: Boolean, val needsNote: Boolean, val hint: String)
@Serializable data class InternalNoteDto(val id: Long, val body: String, val author: String, val at: String)
@Serializable data class AdminRepairDetail(
    val repair: RepairDto,
    val summary: RepairSummary,
    val customer: CustomerSummary,
    val nextStatuses: List<NextStatusDto>,
    val notes: List<InternalNoteDto>,
    val partnerId: String?, val shopId: String?, val technicianId: String?,
    val quoteDraft: QuoteDraftDto?,
    val claim: AdminClaimDto?,
    val tickets: List<AdminTicketSummary>,
    val refunds: List<RefundDto>,
    val audit: List<AuditEntryDto>,
)
@Serializable data class StatusChangeRequest(val to: String, val note: String = "")
@Serializable data class AssignRequest(val partnerId: String? = null, val shopId: String? = null, val technicianId: String? = null, val reason: String = "")
@Serializable data class AdminRescheduleRequest(val date: String, val startHour: Int, val reason: String)

// ---- Customers ----------------------------------------------------------------------------------------------
@Serializable data class CustomerSummary(
    val id: String, val kind: String, val name: String?, val phone: String?, val email: String?, val createdAt: String,
    val repairs: Int, val activeRepairs: Int, val lastRepairAt: String?,
)
@Serializable data class CustomerListResponse(val items: List<CustomerSummary>, val total: Int)
@Serializable data class AdminAddressDto(val address: AddressDto, val deleted: Boolean)
@Serializable data class CustomerDetail(
    val customer: CustomerSummary, val repairs: List<RepairSummary>, val addresses: List<AdminAddressDto>,
    val claims: List<AdminClaimDto>, val tickets: List<AdminTicketSummary>,
)

// ---- Overview -----------------------------------------------------------------------------------------------
@Serializable data class OverviewDto(
    val today: String,
    val bookingsToday: Int, val bookings7d: Int, val activeRepairs: Int,
    val pickupsToday: Int, val deliveriesToday: Int,
    val pendingQuotes: Int, val draftQuotes: Int, val delayedRepairs: Int,
    val revenueToday: Int, val revenue7d: Int, val revenue30d: Int, val refunds30d: Int, val unreconciledPayments: Int,
    val openClaims: Int, val openTickets: Int, val unassignedTickets: Int,
    val statusCounts: Map<String, Int>,
    val delayed: List<RepairSummary>,
    val recentActivity: List<AuditEntryDto>,
)

// ---- Services & pricing -------------------------------------------------------------------------------------
@Serializable data class CategoryAdminDto(val code: String, val label: String, val sortOrder: Int, val active: Boolean)
@Serializable data class IssueAdminDto(val code: String, val label: String, val hint: String, val sortOrder: Int)
@Serializable data class ServiceEntryDto(
    val category: String, val issue: String, val label: String, val basePrice: Int, val diagnosisFee: Int, val turnaround: String,
    val labelOverride: String?, val active: Boolean, val updatedAt: String, val bookings: Int,
)
@Serializable data class BrandAdminDto(val id: Int, val category: String, val name: String, val multiplier: Double)
@Serializable data class CatalogAdminDto(
    val categories: List<CategoryAdminDto>, val issues: List<IssueAdminDto>, val entries: List<ServiceEntryDto>, val brands: List<BrandAdminDto>,
)
@Serializable data class EstimatePreviewDto(val brand: String?, val low: Int, val high: Int)
@Serializable data class PriceHistoryDto(
    val id: Long, val scope: String, val category: String, val issue: String?, val brand: String?, val field: String,
    val oldValue: String?, val newValue: String?, val reason: String, val changedBy: String, val at: String,
)
@Serializable data class ServiceDetailDto(val entry: ServiceEntryDto, val estimates: List<EstimatePreviewDto>, val history: List<PriceHistoryDto>)
@Serializable data class ServiceUpdateRequest(
    val basePrice: Int? = null, val diagnosisFee: Int? = null, val turnaround: String? = null,
    val labelOverride: String? = null, val clearLabelOverride: Boolean = false, val active: Boolean? = null, val reason: String = "",
)
@Serializable data class ServiceCreateRequest(
    val category: String, val issue: String, val basePrice: Int, val diagnosisFee: Int = 0, val turnaround: String,
    val labelOverride: String? = null, val reason: String = "",
)
@Serializable data class CategoryUpdateRequest(val label: String? = null, val active: Boolean? = null, val sortOrder: Int? = null, val reason: String = "")
@Serializable data class IssueUpdateRequest(val label: String? = null, val hint: String? = null, val reason: String = "")
@Serializable data class BrandUpdateRequest(val multiplier: Double, val reason: String = "")

// ---- Partners -----------------------------------------------------------------------------------------------
@Serializable data class TechnicianDto(val id: String, val shopId: String, val name: String, val skills: List<String>, val active: Boolean, val activeJobs: Int)
@Serializable data class ShopAdminDto(
    val id: String, val name: String, val area: String, val rating: Double, val reviews: Int, val verifiedSince: String,
    val active: Boolean, val capacity: Int, val capabilities: List<String>, val activeJobs: Int, val technicians: List<TechnicianDto>,
)
@Serializable data class CourierDto(
    val id: String, val name: String, val phone: String, val vehicle: String, val rating: Double, val trips: Int,
    val active: Boolean, val dailyCapacity: Int, val assignedToday: Int, val activeJobs: Int,
)
@Serializable data class PartnersDto(val shops: List<ShopAdminDto>, val couriers: List<CourierDto>)
@Serializable data class ShopUpsertRequest(
    val name: String, val area: String, val capacity: Int, val capabilities: List<String>, val active: Boolean = true,
    val verifiedSince: String = "", val reason: String = "",
)
@Serializable data class TechnicianUpsertRequest(val shopId: String, val name: String, val skills: List<String> = emptyList(), val active: Boolean = true, val reason: String = "")
@Serializable data class CourierUpsertRequest(val name: String, val phone: String, val vehicle: String, val dailyCapacity: Int, val active: Boolean = true, val reason: String = "")

// ---- Pickups & service areas --------------------------------------------------------------------------------
@Serializable data class ServiceAreaDto(val prefix: String, val city: String, val active: Boolean)
@Serializable data class AreaUpsertRequest(val prefix: String, val city: String, val active: Boolean = true, val reason: String = "")
@Serializable data class SlotTemplateDto(val startHour: Int, val capacity: Int)
@Serializable data class SlotTemplateRequest(val startHour: Int, val capacity: Int, val reason: String = "")
@Serializable data class BlackoutDto(val date: String, val startHour: Int, val reason: String)
@Serializable data class BlackoutRequest(val date: String, val startHour: Int? = null, val reason: String)
@Serializable data class SlotLoadDto(val startHour: Int, val capacity: Int, val booked: Int, val blocked: Boolean, val blockReason: String?)
@Serializable data class LogisticsDayDto(val date: String, val slots: List<SlotLoadDto>, val pickups: List<RepairSummary>, val deliveries: List<RepairSummary>)
@Serializable data class AreasDto(val areas: List<ServiceAreaDto>, val templates: List<SlotTemplateDto>, val blackouts: List<BlackoutDto>)

// ---- Quotes -------------------------------------------------------------------------------------------------
@Serializable data class QuoteDraftDto(
    val repairId: String, val diagnosis: String, val findings: List<String>, val items: List<LineItemDto>, val warrantyText: String,
    val total: Int, val updatedBy: String, val updatedAt: String,
)
@Serializable data class QuoteDraftRequest(
    val diagnosis: String = "", val findings: List<String> = emptyList(), val items: List<LineItemDto> = emptyList(),
    val warrantyText: String = "90 days on parts and labour",
)
@Serializable data class QuoteListItem(
    val repairId: String, val state: String, val total: Int, val itemCount: Int, val estimateLow: Int, val estimateHigh: Int,
    val contactName: String, val device: String, val repairStatus: String, val at: String, val decidedAt: String?, val declineReason: String?,
)

// ---- Payments -----------------------------------------------------------------------------------------------
@Serializable data class RefundDto(val id: String, val amount: Int, val reason: String, val by: String, val at: String)
@Serializable data class PaymentAdminDto(
    val repairId: String, val method: String, val amount: Int, val reference: String, val at: String, val refunded: Int, val net: Int,
    val reconciledAt: String?, val reconciledBy: String?, val reconciledNote: String?, val invoiceNo: String?, val customerName: String,
)
@Serializable data class PaymentTotals(val collected: Int, val refunded: Int, val net: Int, val unreconciled: Int)
@Serializable data class PaymentsResponse(val items: List<PaymentAdminDto>, val totals: PaymentTotals)
@Serializable data class RefundRequest(val amount: Int, val reason: String)
@Serializable data class ReconcileRequest(val note: String = "")
@Serializable data class AdminInvoiceDto(val invoice: InvoiceDto, val payment: PaymentAdminDto, val refunds: List<RefundDto>)

// ---- Warranty claims ----------------------------------------------------------------------------------------
@Serializable data class AdminClaimDto(
    val id: String, val customerId: String, val customerName: String?, val originalRepairId: String, val claimRepairId: String,
    val claimRepairStatus: String, val problem: String, val detail: String, val status: String, val decisionNote: String?,
    val openedAt: String, val resolvedAt: String?, val warrantyEndsOn: String?,
)
@Serializable data class ClaimActionRequest(val note: String = "")

// ---- Support ------------------------------------------------------------------------------------------------
@Serializable data class AdminTicketSummary(
    val id: String, val customerId: String, val customerName: String?, val repairId: String?, val topic: String, val status: String,
    val assignedTo: String?, val assignedName: String?, val createdAt: String, val updatedAt: String, val messages: Int,
)
@Serializable data class TicketMessageDto(val id: Long, val visibility: String, val body: String, val author: String, val at: String)
@Serializable data class AdminTicketDetail(val ticket: AdminTicketSummary, val detail: String, val messages: List<TicketMessageDto>)
@Serializable data class TicketAssignRequest(val adminId: String? = null)
@Serializable data class TicketStatusRequest(val status: String, val reason: String = "")

// ---- Team ---------------------------------------------------------------------------------------------------
@Serializable data class AdminUserDto(
    val id: String, val email: String, val name: String, val role: String, val active: Boolean, val createdAt: String, val lastLoginAt: String?,
)
@Serializable data class AdminCreateRequest(val email: String, val name: String, val role: String, val password: String)
@Serializable data class AdminUpdateRequest(val name: String? = null, val role: String? = null, val active: Boolean? = null)
@Serializable data class PasswordResetRequest(val password: String)
@Serializable data class StaffOption(val id: String, val name: String, val role: String)
