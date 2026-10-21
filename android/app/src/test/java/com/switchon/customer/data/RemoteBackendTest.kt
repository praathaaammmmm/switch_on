package com.switchon.customer.data

import com.switchon.customer.data.local.MemoryLocalStore
import com.switchon.customer.data.remote.*
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.Collections
import java.util.concurrent.TimeUnit

private class Net(override var online: Boolean = true) : NetworkMonitor

/** RemoteBackend and the view model against a fake API server: success, offline cache, server errors, duplicates. */
class RemoteBackendTest {

    private lateinit var server: MockWebServer
    private val session = MemorySessionStore("6f1c1d2e-0000-4000-8000-000000000001")
    private val store = MemoryLocalStore()
    private val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())
    private var syncScheduled = 0

    /** Per-path canned responses; the default is a healthy server. */
    private val routes = mutableMapOf<String, () -> MockResponse>()
    private var repairsList = listOf(repairDto("SO-482917", "QUOTE_PENDING"))

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    private inline fun <reified T> ok(v: T, code: Int = 200) = json(apiJson.encodeToString(v), code)
    private fun error(code: Int, errCode: String, message: String) = ok(ErrorBody(ErrorDetail(errCode, message)), code)

    private val profile = ProfileDto("c0ffee00-0000-4000-8000-000000000001", "GUEST", "Priya Sharma", "9845012873", null)

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add(request)
                val path = request.path!!.substringBefore('?')
                routes[path]?.let { return it() }
                return when {
                    path == "/v1/guest-sessions" -> ok(GuestSessionResponse("token-1", profile, created = true), 201)
                    request.getHeader("Authorization") != "Bearer token-1" -> error(401, "unauthorized", "Session expired")
                    path == "/v1/me" -> ok(profile)
                    path == "/v1/catalog/devices" -> ok(DevicesResponse(listOf(CategoryDto("PHONE", "Phone", listOf(BrandDto("Apple", 2.6, listOf("iPhone 13")))))))
                    path == "/v1/catalog/issues" -> ok(IssuesResponse(listOf(IssueDto("SCREEN", "Screen", "Cracked", listOf(OfferingDto("PHONE", 1599, "24–48 hours", "Screen")))), listOf("560")))
                    path == "/v1/repairs" && request.method == "GET" -> ok(RepairsResponse(repairsList))
                    path == "/v1/repairs" && request.method == "POST" -> ok(repairDto("SO-100200", "PICKUP_SCHEDULED"), 201)
                    path == "/v1/addresses" -> json("[]")
                    path == "/v1/pickup-slots" -> ok(SlotsResponse("Asia/Kolkata", listOf(SlotDto("2026-10-22", 9, 11, true, 6), SlotDto("2026-10-22", 11, 13, false, 0))))
                    path.endsWith("/quote-decision") -> ok(repairDto("SO-482917", "REPAIRING", decision = "APPROVED"))
                    path.endsWith("/payment") -> ok(repairDto("SO-482917", "WARRANTY_ACTIVE"))
                    path == "/v1/support-tickets" -> ok(SupportTicketDto("TKT-12345", null, "Billing", "Please resend it", "OPEN", "2026-10-21T05:00:00Z"), 201)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After fun tearDown() {
        runCatching { server.shutdown() }
        Catalog.reset() // these tests load a server catalogue into the shared object
    }

    private fun backend() = RemoteBackend(buildApi(server.url("/").toString(), session, debugLogging = false), session, store, scheduleSync = { syncScheduled++ })
    private fun vm(net: Net = Net(), b: RemoteBackend = backend()) = AppViewModel(b, net, session, b, latency = false)
    private fun count(path: String) = requests.count { it.path!!.startsWith(path) }

    // ---- Success ----

    @Test fun opensAGuestSessionLoadsTheCatalogueAndMapsRepairs() = runTest {
        val b = backend()
        val p = b.open()
        assertEquals("token-1", session.token)
        assertEquals("Priya Sharma", p.name)
        assertEquals("+91 98450 12873", p.phone)
        // The server's catalogue replaces the bundled prices.
        assertEquals(1599, Catalog.basePrice(DeviceType.PHONE, IssueType.SCREEN))
        val r = b.repairs().single()
        assertEquals("SO-482917", r.id)
        assertEquals(Stage.QUOTE_PENDING, r.stage)
        // 2026-10-20T12:16:00Z is 17:46 in Asia/Kolkata.
        assertEquals(17, r.quote!!.sentAt.hour)
        assertEquals(46, r.quote!!.sentAt.minute)
        assertEquals("guest session sent the installation id", true, requests.first().body.readUtf8().contains(session.installationId))
    }

    @Test fun slotsComeFromTheServerWithAvailability() = runTest {
        val b = backend(); b.open()
        val day = b.slots()[LocalDate.of(2026, 10, 22)]!!
        assertEquals(listOf(true, false), day.map { it.second })
    }

    // ---- Offline cache ----

    @Test fun lastKnownRepairsStayReadableOffline() = runTest {
        val b = backend(); b.open(); b.repairs()
        server.shutdown()
        assertTrue(runCatching { b.repairs() }.exceptionOrNull() is OfflineException)
        assertEquals(listOf("SO-482917"), b.cachedRepairs().map { it.id })
    }

    @Test fun viewModelShowsTheCachedCopyWhenTheServerIsUnreachable() = runTest {
        vm().apply { open() }  // first launch online fills the cache
        server.shutdown()
        val reopened = vm()      // app restarted with no connection
        reopened.open()
        assertEquals(listOf("SO-482917"), reopened.repairs.map { it.id })
        assertTrue(reopened.repairsLoaded)
        assertTrue(reopened.repairsStale)
        assertNotNull(reopened.loadError)
    }

    // ---- Server errors ----

    @Test fun conflictsComeBackAsTheServersMessage() = runTest {
        routes["/v1/repairs/SO-482917/quote-decision"] = { error(409, "quote_already_decided", "This quote has already been decided.") }
        val v = vm(); v.open()
        val e = v.approveQuote("SO-482917").exceptionOrNull()
        assertTrue(e is NotAllowedException)
        assertEquals("This quote has already been decided.", e!!.message)
        assertEquals("unchanged locally", Stage.QUOTE_PENDING, v.repair("SO-482917")!!.stage)
    }

    @Test fun serverFaultsBecomeServerException() = runTest {
        routes["/v1/repairs"] = { json("""{"error":{"code":"server_error","message":"boom"}}""", 500) }
        val b = backend(); b.open()
        assertTrue(runCatching { b.repairs() }.exceptionOrNull() is ServerException)
    }

    @Test fun anExpiredTokenIsRenewedForTheSameInstallation() = runTest {
        val b = backend(); b.open()
        session.token = "stale"
        assertEquals("SO-482917", b.repairs().single().id)
        assertEquals("token-1", session.token)
        assertEquals(2, count("/v1/guest-sessions"))
    }

    // ---- Duplicate prevention ----

    @Test fun repeatedApproveTapsSendOneRequest() {
        routes["/v1/repairs/SO-482917/quote-decision"] = { ok(repairDto("SO-482917", "REPAIRING", decision = "APPROVED")).setBodyDelay(300, TimeUnit.MILLISECONDS) }
        val v = vm()
        runBlocking { v.open() }
        val results = runBlocking {
            val a = async(kotlinx.coroutines.Dispatchers.IO) { v.approveQuote("SO-482917") }
            Thread.sleep(50)
            val b = async(kotlinx.coroutines.Dispatchers.IO) { v.approveQuote("SO-482917") }
            listOf(a.await(), b.await())
        }
        assertEquals(1, results.count { it.isSuccess })
        assertTrue(results.any { it.exceptionOrNull() is BusyException })
        assertEquals(1, count("/v1/repairs/SO-482917/quote-decision"))
        assertEquals(Stage.REPAIRING, v.repair("SO-482917")!!.stage)
    }

    @Test fun aRetriedPaymentReusesItsIdempotencyKey() = runTest {
        var calls = 0
        routes["/v1/repairs/SO-482917/payment"] = {
            calls++
            if (calls == 1) MockResponse().setResponseCode(503) else ok(repairDto("SO-482917", "WARRANTY_ACTIVE"))
        }
        val v = vm(); v.open()
        assertTrue(v.pay("SO-482917", PayMethod.CARD).exceptionOrNull() is ServerException)
        assertTrue(v.pay("SO-482917", PayMethod.CARD).isSuccess)
        val keys = requests.filter { it.path!!.endsWith("/payment") }.map { apiJson.decodeFromString<PaymentRequest>(it.body.readUtf8()).idempotencyKey }
        assertEquals(2, keys.size)
        assertEquals("same key on retry, so the server can't record it twice", keys[0], keys[1])
    }

    // ---- Offline queue ----

    @Test fun anOfflineBookingIsQueuedAndSentLaterWithTheSameRequestId() = runTest {
        val net = Net()
        val b = backend()
        val v = vm(net, b); v.open()
        v.startBooking(DeviceType.PHONE, IssueType.SCREEN)
        v.draft = v.draft.copy(addressId = "addr-1", slot = Slot(LocalDate.of(2026, 10, 22), 9), contactName = "Priya", contactPhone = "9845012873")
        // pickupProblem needs a known address; give the view model one.
        v.addresses.add(Address("addr-1", "Home", "Flat 4B", "", "Indiranagar", "Bengaluru", "560038"))
        val requestId = v.draft.requestId

        net.online = false
        val e = v.confirmBooking().exceptionOrNull()
        assertTrue(e is QueuedException)
        assertEquals(1, v.pending.size)
        assertEquals(1, syncScheduled)
        assertEquals("nothing sent while offline", 0, count("/v1/repairs") - requests.count { it.path == "/v1/repairs" && it.method == "GET" })

        net.online = true
        assertTrue(b.flushPending())
        val sent = requests.single { it.path == "/v1/repairs" && it.method == "POST" }
        assertEquals(requestId, apiJson.decodeFromString<CreateRepairRequest>(sent.body.readUtf8()).clientRequestId)
        assertTrue(store.pending().isEmpty())
        assertTrue(b.cachedRepairs().any { it.id == "SO-100200" })
    }

    /** The API has no field for device-check data: the report stays on the phone and links up when the queue sends. */
    @Test fun aQueuedBookingsDeviceCheckStaysOnThePhoneAndLinksWhenSent() = runTest {
        val net = Net()
        val reports = com.switchon.customer.data.diagnostics.MemorySavedReports()
        val b = RemoteBackend(buildApi(server.url("/").toString(), session, debugLogging = false), session, store,
            scheduleSync = { syncScheduled++ }, onBookingSent = reports::link)
        val v = AppViewModel(b, net, session, b, latency = false, reports = reports); v.open()
        v.addresses.add(Address("addr-1", "Home", "Flat 4B", "", "Indiranagar", "Bengaluru", "560038"))
        v.startBooking(DeviceType.PHONE, IssueType.SCREEN)
        val report = com.switchon.customer.data.diagnostics.DiagnosticRules.buildReport(
            com.switchon.customer.data.diagnostics.DeviceInfo("Google", "Pixel 7", "Android 15", 35, DeviceType.PHONE),
            emptyMap(), emptyList(), java.time.LocalDateTime.of(2026, 10, 22, 9, 0))
        v.attachDiagnosticReport(report, fromBooking = true)
        v.draft = v.draft.copy(addressId = "addr-1", slot = Slot(LocalDate.of(2026, 10, 22), 9), contactName = "Priya", contactPhone = "9845012873")

        net.online = false
        assertTrue(v.confirmBooking().exceptionOrNull() is QueuedException)
        net.online = true
        assertTrue(b.flushPending())

        val sent = requests.single { it.path == "/v1/repairs" && it.method == "POST" }.body.readUtf8()
        val keys = kotlinx.serialization.json.Json.parseToJsonElement(sent).let { it as kotlinx.serialization.json.JsonObject }.keys
        assertTrue("no invented report field in the booking payload", keys.none { it.contains("diagnostic", ignoreCase = true) || it.contains("report", ignoreCase = true) })
        assertEquals(report, v.savedReport("SO-100200"))
    }

    @Test fun aRefusedQueuedBookingIsKeptWithItsReason() = runTest {
        routes["/v1/repairs"] = { error(409, "slot_full", "That pickup window just filled up. Choose another.") }
        val b = backend(); b.open()
        b.queueBooking(BookingDraft(DeviceType.PHONE, IssueType.SCREEN, addressId = "a", slot = Slot(LocalDate.of(2026, 10, 22), 9), contactName = "P", contactPhone = "9845012873"))
        assertTrue("refusals aren't retried", b.flushPending())
        assertEquals("That pickup window just filled up. Choose another.", b.pending().single().error)
    }

    @Test fun anOfflineSupportReportIsQueued() = runTest {
        val net = Net()
        val v = vm(net); v.open()
        net.online = false
        assertTrue(v.reportIssue(null, "Billing", "Please resend the invoice").exceptionOrNull() is QueuedException)
        assertEquals("TICKET", v.pending.single().kind)
    }

    @Test fun quoteDecisionsAndPaymentsAreNeverQueued() = runTest {
        val net = Net()
        val v = vm(net); v.open()
        net.online = false
        assertTrue(v.approveQuote("SO-482917").exceptionOrNull() is OfflineException)
        assertTrue(v.pay("SO-482917", PayMethod.CARD).exceptionOrNull() is OfflineException)
        assertTrue(v.pending.isEmpty())
    }

    @Test fun guestEntryIsRemembered() {
        val v = vm()
        assertFalse(v.signedIn)
        v.continueAsGuest()
        assertTrue(vm().signedIn) // a new launch skips onboarding
        v.logout()
        assertFalse(vm().signedIn)
    }

    // ---- Fixtures ----

    private fun repairDto(id: String, status: String, decision: String? = null) = RepairDto(
        id = id, status = status, deviceCategory = "PHONE", issue = "SCREEN", brand = "Apple", model = "iPhone 13",
        symptoms = listOf("Cracked glass"), notes = "", photoCount = 0, estimateLow = 3899, estimateHigh = 5299, turnaround = "24–48 hours",
        address = AddressDto("addr-1", "Home", "Flat 4B", "", "Indiranagar", "Bengaluru", "560038", "", true),
        slot = SlotRef("2026-10-22", 9), contactName = "Priya Sharma", contactPhone = "9845012873",
        createdAt = "2026-10-19T15:44:00Z", updatedAt = "2026-10-20T12:16:00Z",
        partner = null, shop = ShopDto("FixHub Electronics", "Indiranagar", 4.8, 2140, "Suresh N.", "Mar 2024"), sealCode = "7Q4K-2190",
        quote = QuoteDto("Cracked display", listOf("Face ID OK"), listOf(LineItemDto("Display", "Part", "PART", 4000), LineItemDto("Labour", "70 min", "LABOUR", 700)),
            4700, "90 days", "2026-10-20T12:16:00Z", decision, if (decision != null) "2026-10-21T04:00:00Z" else null, null),
        events = listOf(EventDto("2026-10-19T15:44:00Z", "PICKUP_REQUESTED", "STATUS", "Pickup requested", "Booked", "CUSTOMER", "You")),
        handovers = emptyList(), payment = null, invoiceNo = null, warranty = null, deliveredAt = null, cancelledAt = null, cancelReason = null,
        warrantyClaimFor = null, amountDue = 0, canCancel = false, canReschedule = false,
    )
}
