// Generated from backend/src/main/kotlin/com/switchon/api/Dto.kt — keep the two in step when the API changes.
package com.switchon.customer.data.remote

import kotlinx.serialization.Serializable

// JSON contract for /v1. Timestamps are ISO-8601 instants in UTC ("2026-10-21T06:30:00Z"); pickup slot dates and
// hours are Asia/Kolkata calendar values. Amounts are whole rupees and always computed by the server.

// ---- Session ------------------------------------------------------------------------------------------------
@Serializable data class GuestSessionRequest(val installationId: String)
@Serializable data class ProfileDto(val customerId: String, val kind: String, val name: String?, val phone: String?, val email: String?)
@Serializable data class GuestSessionResponse(val token: String, val profile: ProfileDto, val created: Boolean)

// ---- Catalogue ----------------------------------------------------------------------------------------------
@Serializable data class ModelDto(val name: String)
@Serializable data class BrandDto(val name: String, val priceMultiplier: Double, val models: List<String>)
@Serializable data class CategoryDto(val code: String, val label: String, val brands: List<BrandDto>)
@Serializable data class DevicesResponse(val categories: List<CategoryDto>)

@Serializable data class OfferingDto(val deviceCategory: String, val basePrice: Int, val turnaround: String, val label: String)
@Serializable data class IssueDto(val code: String, val label: String, val hint: String, val offerings: List<OfferingDto>)
@Serializable data class IssuesResponse(val issues: List<IssueDto>, val servicePincodePrefixes: List<String>)

@Serializable data class EstimateRequest(val deviceCategory: String, val issue: String, val brand: String? = null)
@Serializable data class EstimateResponse(
    val deviceCategory: String, val issue: String, val brand: String?, val label: String,
    val basePrice: Int, val low: Int, val high: Int, val turnaround: String, val warrantyDays: Int,
)

@Serializable data class ServiceabilityResponse(val pinCode: String, val serviceable: Boolean, val city: String?)

@Serializable data class SlotDto(val date: String, val startHour: Int, val endHour: Int, val available: Boolean, val remaining: Int)
@Serializable data class SlotsResponse(val timezone: String, val slots: List<SlotDto>)
@Serializable data class SlotRef(val date: String, val startHour: Int)

// ---- Addresses ----------------------------------------------------------------------------------------------
@Serializable data class AddressRequest(
    val label: String, val line1: String, val line2: String = "", val area: String, val city: String,
    val pincode: String, val landmark: String = "",
)
@Serializable data class AddressDto(
    val id: String, val label: String, val line1: String, val line2: String, val area: String, val city: String,
    val pincode: String, val landmark: String, val isDefault: Boolean,
)

// ---- Repairs ------------------------------------------------------------------------------------------------
@Serializable data class CreateRepairRequest(
    /** Generated once per booking by the app; replaying the same id returns the same repair. */
    val clientRequestId: String,
    val deviceCategory: String,
    val issue: String,
    val brand: String? = null,
    val model: String? = null,
    val symptoms: List<String> = emptyList(),
    val notes: String = "",
    val photoCount: Int = 0,
    val addressId: String,
    val slot: SlotRef,
    val contactName: String,
    val contactPhone: String,
)

@Serializable data class PartnerDto(val name: String, val phone: String, val vehicle: String, val rating: Double, val trips: Int)
@Serializable data class ShopDto(val name: String, val area: String, val rating: Double, val reviews: Int, val technician: String, val verifiedSince: String)
@Serializable data class EventDto(
    val at: String, val status: String?, val kind: String, val title: String, val message: String,
    val actorType: String, val actorName: String, val metadata: Map<String, String> = emptyMap(),
)
@Serializable data class HandoverDto(val stage: String, val sealCode: String, val handledBy: String, val photoCount: Int, val sealIntact: Boolean, val at: String)
@Serializable data class LineItemDto(val label: String, val detail: String, val kind: String, val amount: Int)
@Serializable data class QuoteDto(
    val diagnosis: String, val findings: List<String>, val items: List<LineItemDto>, val total: Int, val warranty: String,
    val sentAt: String, val decision: String?, val decidedAt: String?, val declineReason: String?,
)
@Serializable data class PaymentDto(val method: String, val amount: Int, val reference: String, val at: String)
@Serializable data class WarrantyDto(val startsOn: String, val endsOn: String, val coverage: String)

@Serializable data class RepairDto(
    val id: String,
    val status: String,
    val deviceCategory: String,
    val issue: String,
    val brand: String?,
    val model: String?,
    val symptoms: List<String>,
    val notes: String,
    val photoCount: Int,
    val estimateLow: Int,
    val estimateHigh: Int,
    val turnaround: String,
    val address: AddressDto,
    val slot: SlotRef,
    val contactName: String,
    val contactPhone: String,
    val createdAt: String,
    val updatedAt: String,
    val partner: PartnerDto?,
    val shop: ShopDto?,
    val sealCode: String?,
    val quote: QuoteDto?,
    val events: List<EventDto>,
    val handovers: List<HandoverDto>,
    val payment: PaymentDto?,
    val invoiceNo: String?,
    val warranty: WarrantyDto?,
    val deliveredAt: String?,
    val cancelledAt: String?,
    val cancelReason: String?,
    val warrantyClaimFor: String?,
    // Derived, so every client applies the same rules.
    val amountDue: Int,
    val canCancel: Boolean,
    val canReschedule: Boolean,
)
@Serializable data class RepairsResponse(val repairs: List<RepairDto>)

@Serializable data class CancelRequest(val reason: String)
@Serializable data class RescheduleRequest(val slot: SlotRef)
@Serializable data class QuoteDecisionRequest(val decision: String, val reason: String? = null)
@Serializable data class PaymentRequest(
    val method: String,
    /** Generated once per payment attempt by the app; a replay returns the recorded payment. */
    val idempotencyKey: String,
    val upiId: String? = null,
    val cashConfirmed: Boolean = false,
)

@Serializable data class InvoiceDto(
    val invoiceNo: String, val repairId: String, val issuedAt: String, val billedTo: String, val address: String,
    val items: List<LineItemDto>, val taxable: Int, val cgst: Int, val sgst: Int, val total: Int,
    val payment: PaymentDto, val warrantyEndsOn: String?, val gstin: String,
)

// ---- Warranty and support -----------------------------------------------------------------------------------
@Serializable data class WarrantyClaimRequest(
    val clientRequestId: String, val originalRepairId: String, val problem: String, val detail: String = "",
    val addressId: String, val slot: SlotRef,
)
@Serializable data class WarrantyClaimDto(
    val id: String, val originalRepairId: String, val claimRepairId: String, val problem: String, val detail: String,
    val status: String, val openedAt: String, val resolvedAt: String?,
)
@Serializable data class WarrantyClaimCreated(val claim: WarrantyClaimDto, val repair: RepairDto)
@Serializable data class WarrantyClaimsResponse(val claims: List<WarrantyClaimDto>)

@Serializable data class SupportTicketRequest(val clientRequestId: String, val repairId: String? = null, val topic: String, val detail: String)
@Serializable data class SupportTicketDto(val id: String, val repairId: String?, val topic: String, val detail: String, val status: String, val createdAt: String)
@Serializable data class SupportTicketsResponse(val tickets: List<SupportTicketDto>)

@Serializable data class ErrorBody(val error: ErrorDetail)
@Serializable data class ErrorDetail(val code: String, val message: String)
