package com.switchon.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** End-to-end over HTTP against a throwaway database, exactly as the app talks to the API. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApiTest {
    private lateinit var tdb: TestDatabase
    private val clock = fixedClockAt(LocalDate.of(2026, 10, 21))

    @BeforeAll fun setUp() { tdb = TestDatabase() }
    @AfterAll fun tearDown() = tdb.close()

    private fun api(config: Config = devConfig, block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        application { module(Services(tdb.db, config, clock)) }
        block(createClient { install(ContentNegotiation) { json(json) } })
    }

    private suspend fun HttpClient.guest(installation: String = UUID.randomUUID().toString()): String =
        post("/v1/guest-sessions") { contentType(ContentType.Application.Json); setBody(GuestSessionRequest(installation)) }.body<GuestSessionResponse>().token

    private suspend inline fun <reified T> HttpClient.postJson(path: String, token: String, body: T): HttpResponse =
        post(path) { bearerAuth(token); contentType(ContentType.Application.Json); setBody(body) }

    private suspend fun HttpClient.internal(path: String, body: Any = InternalNoteRequest()): HttpResponse =
        post(path) { header("X-Internal-Token", "test-internal-token"); contentType(ContentType.Application.Json); setBody(body as InternalNoteRequest) }

    private suspend fun HttpResponse.code() = body<ErrorBody>().error.code

    @Test fun `guest books, tracks, approves, pays once and gets an invoice`() = api { client ->
        val token = client.guest()
        val address = client.postJson("/v1/addresses", token, AddressRequest("Home", "Flat 4B", "", "Indiranagar", "Bengaluru", "560038")).body<AddressDto>()
        val slot = client.get("/v1/pickup-slots") { bearerAuth(token) }.body<SlotsResponse>().slots.first { it.available }
        assertEquals("Asia/Kolkata", client.get("/v1/pickup-slots") { bearerAuth(token) }.body<SlotsResponse>().timezone)

        val request = CreateRepairRequest(UUID.randomUUID().toString(), "PHONE", "BATTERY", "OnePlus", "OnePlus 11", listOf("Drains quickly"), "",
            0, address.id, SlotRef(slot.date, slot.startHour), "Priya Sharma", "9845012873")
        val created = client.postJson("/v1/repairs", token, request)
        assertEquals(HttpStatusCode.Created, created.status)
        val repair = created.body<RepairDto>()
        val estimate = client.postJson("/v1/estimates", token, EstimateRequest("PHONE", "BATTERY", "OnePlus")).body<EstimateResponse>()
        assertEquals(estimate.low to estimate.high, repair.estimateLow to repair.estimateHigh)

        // A replay (e.g. WorkManager retry) returns the same booking with 200.
        val replay = client.postJson("/v1/repairs", token, request)
        assertEquals(HttpStatusCode.OK, replay.status)
        assertEquals(repair.id, replay.body<RepairDto>().id)

        // History persists: a fresh read sees it.
        assertEquals(listOf(repair.id), client.get("/v1/repairs") { bearerAuth(token) }.body<RepairsResponse>().repairs.map { it.id })

        repeat(5) { assertEquals(HttpStatusCode.OK, client.internal("/internal/v1/repairs/${repair.id}/advance").status) }
        assertEquals("cannot_advance", client.internal("/internal/v1/repairs/${repair.id}/advance").code())

        val approved = client.postJson("/v1/repairs/${repair.id}/quote-decision", token, QuoteDecisionRequest("APPROVE")).body<RepairDto>()
        assertEquals("REPAIRING", approved.status)
        val again = client.postJson("/v1/repairs/${repair.id}/quote-decision", token, QuoteDecisionRequest("APPROVE"))
        assertEquals(HttpStatusCode.Conflict, again.status)
        assertEquals("quote_already_decided", again.code())

        assertEquals(HttpStatusCode.NotFound, client.get("/v1/repairs/${repair.id}/invoice") { bearerAuth(token) }.status)
        repeat(3) { client.internal("/internal/v1/repairs/${repair.id}/advance") }

        val key = UUID.randomUUID().toString()
        val paid = client.postJson("/v1/repairs/${repair.id}/payment", token, PaymentRequest("CARD", key)).body<RepairDto>()
        assertEquals("WARRANTY_ACTIVE", paid.status)
        assertEquals(approved.quote!!.total, paid.payment!!.amount)
        assertEquals(HttpStatusCode.OK, client.postJson("/v1/repairs/${repair.id}/payment", token, PaymentRequest("CARD", key)).status)
        val twice = client.postJson("/v1/repairs/${repair.id}/payment", token, PaymentRequest("CASH", UUID.randomUUID().toString(), cashConfirmed = true))
        assertEquals(HttpStatusCode.Conflict, twice.status)
        assertEquals("payment_already_recorded", twice.code())

        val invoice = client.get("/v1/repairs/${repair.id}/invoice") { bearerAuth(token) }.body<InvoiceDto>()
        assertEquals(paid.invoiceNo, invoice.invoiceNo)
        assertEquals(invoice.total, invoice.taxable + invoice.cgst + invoice.sgst)

        // Every event is timestamped in UTC and in order.
        val events = paid.events
        assertTrue(events.all { it.at.endsWith("Z") })
        assertEquals(events.map { it.at }.sorted(), events.map { it.at })
        assertEquals(listOf("PICKUP_REQUESTED", "PICKUP_SCHEDULED", "PARTNER_ASSIGNED", "PICKED_UP", "AT_SHOP", "DIAGNOSED", "QUOTE_PENDING",
            "REPAIRING", "QUALITY_CHECK", "RETURN_SCHEDULED", "DELIVERED", "PAYMENT_PENDING", "PAYMENT_COMPLETED", "WARRANTY_ACTIVE"),
            events.mapNotNull { it.status })

        // Warranty claim and support ticket persist.
        val claim = client.postJson("/v1/warranty-claims", token,
            WarrantyClaimRequest(UUID.randomUUID().toString(), repair.id, "Same fault is back", "Drains again", address.id, SlotRef(slot.date, slot.startHour)))
        assertEquals(HttpStatusCode.Created, claim.status)
        assertEquals(1, client.get("/v1/warranty-claims") { bearerAuth(token) }.body<WarrantyClaimsResponse>().claims.size)
        val ticket = client.postJson("/v1/support-tickets", token, SupportTicketRequest(UUID.randomUUID().toString(), repair.id, "Billing", "Please resend the invoice"))
        assertEquals(HttpStatusCode.Created, ticket.status)
        assertTrue(Regex("^TKT-\\d{5}$").matches(ticket.body<SupportTicketDto>().id))
    }

    @Test fun `cancel before pickup, refused after`() = api { client ->
        val token = client.guest()
        val address = client.postJson("/v1/addresses", token, AddressRequest("Home", "1 Main", "", "Jayanagar", "Bengaluru", "560011")).body<AddressDto>()
        val slot = SlotRef(LocalDate.of(2026, 10, 22).toString(), 13)
        fun req() = CreateRepairRequest(UUID.randomUUID().toString(), "LAPTOP", "PERFORMANCE", null, null, emptyList(), "", 0, address.id, slot, "Priya", "9845012873")
        val a = client.postJson("/v1/repairs", token, req()).body<RepairDto>()
        assertEquals("CANCELLED", client.postJson("/v1/repairs/${a.id}/cancel", token, CancelRequest("Booked by mistake")).body<RepairDto>().status)

        val b = client.postJson("/v1/repairs", token, req()).body<RepairDto>()
        repeat(2) { client.internal("/internal/v1/repairs/${b.id}/advance") }
        val refused = client.postJson("/v1/repairs/${b.id}/cancel", token, CancelRequest("Too late"))
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertEquals("cancel_not_allowed", refused.code())
    }

    @Test fun `auth and isolation`() = api { client ->
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/repairs").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/repairs") { bearerAuth("forged") }.status)
        val alice = client.guest(); val bob = client.guest()
        val address = client.postJson("/v1/addresses", alice, AddressRequest("Home", "1 Main", "", "Jayanagar", "Bengaluru", "560011")).body<AddressDto>()
        val r = client.postJson("/v1/repairs", alice, CreateRepairRequest(UUID.randomUUID().toString(), "PHONE", "OTHER", null, null, emptyList(), "", 0,
            address.id, SlotRef("2026-10-22", 15), "Alice", "9845012873")).body<RepairDto>()
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/repairs/${r.id}") { bearerAuth(bob) }.status)
        assertTrue(client.get("/v1/repairs") { bearerAuth(bob) }.body<RepairsResponse>().repairs.isEmpty())
        assertEquals(HttpStatusCode.NotFound, client.postJson("/v1/repairs/${r.id}/cancel", bob, CancelRequest("x")).status)
    }

    @Test fun `same installation reopens the same history`() = api { client ->
        val installation = UUID.randomUUID().toString()
        val first = client.guest(installation)
        val address = client.postJson("/v1/addresses", first, AddressRequest("Home", "1 Main", "", "Jayanagar", "Bengaluru", "560011")).body<AddressDto>()
        val r = client.postJson("/v1/repairs", first, CreateRepairRequest(UUID.randomUUID().toString(), "TABLET", "SCREEN", null, null, emptyList(), "", 0,
            address.id, SlotRef("2026-10-22", 9), "Priya", "9845012873")).body<RepairDto>()
        val reopened = client.guest(installation) // app restarted and asked for a new session
        assertEquals(listOf(r.id), client.get("/v1/repairs") { bearerAuth(reopened) }.body<RepairsResponse>().repairs.map { it.id })
    }

    @Test fun `bad input gets a clear 4xx`() = api { client ->
        val token = client.guest()
        val bad = client.post("/v1/repairs") { bearerAuth(token); contentType(ContentType.Application.Json); setBody("{\"nope\":1}") }
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertEquals("invalid_request", bad.code())
        assertEquals(HttpStatusCode.UnprocessableEntity,
            client.postJson("/v1/addresses", token, AddressRequest("Home", "1 Main", "", "Fort", "Kochi", "682001")).status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/v1/serviceability") { bearerAuth(token) }.status)
        assertNotNull(client.get("/v1/catalog/devices") { bearerAuth(token) }.body<DevicesResponse>().categories.firstOrNull { it.code == "PHONE" })
        assertEquals(6, client.get("/v1/catalog/issues") { bearerAuth(token) }.body<IssuesResponse>().issues.size)
    }

    @Test fun `internal routes need the token and are absent in production`() = api { client ->
        assertEquals(HttpStatusCode.Forbidden, client.post("/internal/v1/repairs/SO-000000/advance") { header("X-Internal-Token", "wrong") }.status)
    }

    @Test fun `production config has no internal routes`() = api(devConfig.copy(environment = "production")) { client ->
        assertEquals(HttpStatusCode.NotFound, client.post("/internal/v1/repairs/SO-000000/advance") { header("X-Internal-Token", "test-internal-token") }.status)
        assertEquals("production", client.get("/health").body<Health>().environment)
    }
}
