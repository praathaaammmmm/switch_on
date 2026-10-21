package com.switchon.customer.data.remote

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** The customer API (/v1). The bearer token is added by [AuthInterceptor]. */
interface SwitchOnApi {
    @POST("v1/guest-sessions") suspend fun guestSession(@Body body: GuestSessionRequest): GuestSessionResponse
    @GET("v1/me") suspend fun me(): ProfileDto

    @GET("v1/catalog/devices") suspend fun devices(): DevicesResponse
    @GET("v1/catalog/issues") suspend fun issues(): IssuesResponse
    @POST("v1/estimates") suspend fun estimate(@Body body: EstimateRequest): EstimateResponse
    @GET("v1/serviceability") suspend fun serviceability(@Query("pinCode") pinCode: String): ServiceabilityResponse
    @GET("v1/pickup-slots") suspend fun slots(): SlotsResponse

    @GET("v1/addresses") suspend fun addresses(): List<AddressDto>
    @POST("v1/addresses") suspend fun createAddress(@Body body: AddressRequest): AddressDto
    @DELETE("v1/addresses/{id}") suspend fun deleteAddress(@Path("id") id: String)
    @POST("v1/addresses/{id}/default") suspend fun makeDefault(@Path("id") id: String)

    @GET("v1/repairs") suspend fun repairs(): RepairsResponse
    @POST("v1/repairs") suspend fun createRepair(@Body body: CreateRepairRequest): RepairDto
    @GET("v1/repairs/{id}") suspend fun repair(@Path("id") id: String): RepairDto
    @POST("v1/repairs/{id}/cancel") suspend fun cancel(@Path("id") id: String, @Body body: CancelRequest): RepairDto
    @POST("v1/repairs/{id}/reschedule") suspend fun reschedule(@Path("id") id: String, @Body body: RescheduleRequest): RepairDto
    @POST("v1/repairs/{id}/quote-decision") suspend fun decideQuote(@Path("id") id: String, @Body body: QuoteDecisionRequest): RepairDto
    @POST("v1/repairs/{id}/payment") suspend fun pay(@Path("id") id: String, @Body body: PaymentRequest): RepairDto
    @GET("v1/repairs/{id}/invoice") suspend fun invoice(@Path("id") id: String): InvoiceDto

    @GET("v1/warranty-claims") suspend fun claims(): WarrantyClaimsResponse
    @POST("v1/warranty-claims") suspend fun createClaim(@Body body: WarrantyClaimRequest): WarrantyClaimCreated

    @POST("v1/support-tickets") suspend fun createTicket(@Body body: SupportTicketRequest): SupportTicketDto

    /** Development builds only; the backend refuses without a valid token and doesn't expose it in production. */
    @POST("internal/v1/repairs/{id}/advance") suspend fun internalAdvance(@Path("id") id: String, @Header("X-Internal-Token") token: String): RepairDto
}
