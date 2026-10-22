package com.switchon.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The admin API over HTTP: cookie sessions, the CSRF header, throttling and role checks. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminApiTest {
    private lateinit var tdb: TestDatabase
    private lateinit var s: Services
    private val clock = fixedClockAt(LocalDate.of(2026, 10, 21))
    private val password = "correct horse battery"

    @BeforeAll fun setUp() {
        tdb = TestDatabase(); s = Services(tdb.db, devConfig, clock)
        s.db.tx { update("UPDATE pickup_slot_templates SET capacity = 500") } // many bookings share tomorrow's windows here
    }
    @AfterAll fun tearDown() = tdb.close()

    private fun api(block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        application { module(s) }
        block(createClient { install(ContentNegotiation) { json(json) }; install(HttpCookies) })
    }

    private fun HttpRequestBuilder.dashboard() { header(ADMIN_CSRF_HEADER, ADMIN_CSRF_VALUE); contentType(ContentType.Application.Json) }

    private suspend fun HttpClient.login(email: String, pw: String = password): HttpResponse =
        post("/v1/admin/session") { dashboard(); setBody(AdminLoginRequest(email, pw)) }

    private suspend fun HttpResponse.code() = body<ErrorBody>().error.code

    @Test fun `login sets an HttpOnly SameSite=Strict cookie and me reports the role's permissions`() = api { client ->
        val owner = createAdmin(s, AdminRole.OWNER)
        val res = client.login(owner.email.uppercase())
        assertEquals(HttpStatusCode.OK, res.status)
        val cookie = res.headers.getAll(HttpHeaders.SetCookie)!!.single { it.startsWith("$ADMIN_COOKIE=") }
        assertTrue("HttpOnly" in cookie && "SameSite=Strict" in cookie && "Path=/v1/admin" in cookie, cookie)
        assertFalse(res.body<String>().contains("password", ignoreCase = true))
        val me = client.get("/v1/admin/me").body<AdminMe>()
        assertEquals("OWNER", me.role)
        assertTrue("team.manage" in me.permissions && "pricing.manage" in me.permissions)
        assertEquals("no-store", client.get("/v1/admin/overview").headers[HttpHeaders.CacheControl])
        // The session is stored only as a hash.
        val token = cookie.substringAfter("=").substringBefore(";")
        assertEquals(0, s.db.tx { one("SELECT count(*) FROM admin_sessions WHERE token_hash = ?", token) { it.getInt(1) }!! })
    }

    @Test fun `no session means 401 everywhere`() = api { client ->
        listOf("/v1/admin/me", "/v1/admin/repairs", "/v1/admin/overview", "/v1/admin/audit", "/v1/admin/catalog").forEach {
            val res = client.get(it)
            assertEquals(HttpStatusCode.Unauthorized, res.status, it)
            assertEquals("unauthorized", res.code())
        }
        // The customer API is unchanged and doesn't accept staff cookies either.
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/repairs").status)
    }

    @Test fun `wrong passwords are rejected, audited and throttled`() = api { client ->
        val support = createAdmin(s, AdminRole.SUPPORT)
        repeat(5) { assertEquals(HttpStatusCode.Unauthorized, client.login(support.email, "wrong password!!").status) }
        val blocked = client.login(support.email)
        assertEquals(HttpStatusCode.TooManyRequests, blocked.status, "even the right password waits once throttled")
        assertEquals("too_many_attempts", blocked.code())
        val failures = s.db.tx { one("SELECT count(*) FROM admin_audit_log WHERE action = 'auth.login_failed' AND admin_email = ?", support.email) { it.getInt(1) }!! }
        assertEquals(5, failures)
        // Unknown emails get the same answer as wrong passwords.
        val unknown = client.login("nobody@switchon.test")
        assertTrue(unknown.status == HttpStatusCode.Unauthorized || unknown.status == HttpStatusCode.TooManyRequests)
    }

    @Test fun `mutations without the dashboard header are refused`() = api { client ->
        val owner = createAdmin(s, AdminRole.OWNER)
        client.login(owner.email)
        val before = s.estimate(EstimateRequest("PHONE", "OTHER")).basePrice
        val res = client.patch("/v1/admin/catalog/services/PHONE/OTHER") {
            contentType(ContentType.Application.Json); setBody(ServiceUpdateRequest(basePrice = 449, reason = "Cross-site attempt"))
        }
        assertEquals(HttpStatusCode.Forbidden, res.status)
        assertEquals("csrf", res.code())
        assertEquals(before, s.estimate(EstimateRequest("PHONE", "OTHER")).basePrice)
    }

    @Test fun `each role can only do its own job`() = api { client ->
        val viewer = createAdmin(s, AdminRole.VIEWER)
        client.login(viewer.email)
        assertEquals(HttpStatusCode.OK, client.get("/v1/admin/repairs").status)
        assertEquals(HttpStatusCode.OK, client.get("/v1/admin/catalog").status)
        val price = client.patch("/v1/admin/catalog/services/PHONE/OTHER") { dashboard(); setBody(ServiceUpdateRequest(basePrice = 449, reason = "Viewer trying")) }
        assertEquals(HttpStatusCode.Forbidden, price.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/v1/admin/team").status, "only owners see the team")
        client.delete("/v1/admin/session") { dashboard() }

        val finance = createAdmin(s, AdminRole.FINANCE)
        client.login(finance.email)
        assertEquals(HttpStatusCode.Forbidden, client.patch("/v1/admin/catalog/services/PHONE/OTHER") { dashboard(); setBody(ServiceUpdateRequest(basePrice = 449, reason = "Finance trying")) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/v1/admin/payments").status)
        client.delete("/v1/admin/session") { dashboard() }

        val ops = createAdmin(s, AdminRole.OPERATIONS)
        client.login(ops.email)
        val ok = client.patch("/v1/admin/catalog/services/PHONE/OTHER") { dashboard(); setBody(ServiceUpdateRequest(basePrice = 449, reason = "Ops price review")) }
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals(449, ok.body<ServiceDetailDto>().entry.basePrice)
        val noReason = client.patch("/v1/admin/catalog/services/PHONE/OTHER") { dashboard(); setBody(ServiceUpdateRequest(basePrice = 499)) }
        assertEquals("reason_required", noReason.code())
    }

    @Test fun `logout and deactivation end the session`() = api { client ->
        val owner = createAdmin(s, AdminRole.OWNER)
        val member = createAdmin(s, AdminRole.SUPPORT)
        client.login(member.email)
        assertEquals(HttpStatusCode.OK, client.get("/v1/admin/me").status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/admin/session") { dashboard() }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/admin/me").status)

        // A second client stays signed in until an owner deactivates the account.
        testApplication {
            application { module(s) }
            val other = createClient { install(ContentNegotiation) { json(json) }; install(HttpCookies) }
            other.login(member.email)
            assertEquals(HttpStatusCode.OK, other.get("/v1/admin/me").status)
            client.login(owner.email)
            assertEquals(HttpStatusCode.OK, client.patch("/v1/admin/team/${member.id}") { dashboard(); setBody(AdminUpdateRequest(active = false)) }.status)
            assertEquals(HttpStatusCode.Unauthorized, other.get("/v1/admin/me").status)
            assertEquals(HttpStatusCode.Unauthorized, other.login(member.email).status)
        }
    }

    @Test fun `repair list filters over HTTP and status changes obey the lifecycle`() = api { client ->
        val f = Fixture(s)
        val r = f.book(category = "WEARABLE", issue = "BATTERY", brand = "Fitbit")
        client.login(createAdmin(s, AdminRole.OPERATIONS).email)
        val list = client.get("/v1/admin/repairs?category=WEARABLE&q=${r.id}").body<RepairListResponse>()
        assertEquals(listOf(r.id), list.items.map { it.id })
        assertEquals(0, client.get("/v1/admin/repairs?category=LAPTOP&q=${r.id}").body<RepairListResponse>().total)
        val bad = client.post("/v1/admin/repairs/${r.id}/status") { dashboard(); setBody(StatusChangeRequest("DELIVERED")) }
        assertEquals(HttpStatusCode.Conflict, bad.status)
        assertEquals("invalid_transition", bad.code())
        val cancel = client.post("/v1/admin/repairs/${r.id}/cancel") { dashboard(); setBody(ReasonRequest("Customer asked by phone")) }
        assertEquals("CANCELLED", cancel.body<AdminRepairDetail>().repair.status)
        val audit = client.get("/v1/admin/audit?entityType=repair&entityId=${r.id}").body<AuditResponse>()
        assertEquals("repair.status", audit.items.first().action)
        assertEquals("Customer asked by phone", audit.items.first().reason)
    }
}
