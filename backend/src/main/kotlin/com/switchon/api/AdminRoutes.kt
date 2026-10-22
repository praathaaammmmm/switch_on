package com.switchon.api

import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/** Everything the dashboard needs, built once per server. */
class AdminApi(val s: Services) {
    val auth = AdminAuth(s)
    val repairs = AdminRepairs(s)
    val catalog = AdminCatalog(s)
    val ops = AdminOps(s)
}

/** The header every dashboard request carries. Browsers can't add it cross-site without a CORS preflight we never allow. */
const val ADMIN_CSRF_HEADER = "X-Requested-With"
const val ADMIN_CSRF_VALUE = "switchon-admin"

@Serializable data class StatusMeta(val code: String, val title: String, val next: List<String>, val beforePickup: Boolean)
@Serializable data class AdminMeta(val statuses: List<StatusMeta>, val roles: Map<String, List<String>>)
@Serializable data class PasswordChangeRequest(val current: String, val new: String)
@Serializable data class BlackoutRemoveRequest(val date: String, val startHour: Int, val reason: String)

private val AdminGuard = createRouteScopedPlugin("AdminGuard") {
    onCall { call ->
        // Responses carry customer data: never cache them anywhere.
        call.response.header(HttpHeaders.CacheControl, "no-store")
        if (call.request.httpMethod != HttpMethod.Get && call.request.header(ADMIN_CSRF_HEADER) != ADMIN_CSRF_VALUE)
            throw ApiException(HttpStatusCode.Forbidden, "csrf", "Missing dashboard request header.")
    }
}

fun Route.adminRoutes(a: AdminApi) {
    install(AdminGuard)
    val secure = a.s.config.adminCookieSecure
    fun ApplicationCall.admin(): AdminPrincipal = a.auth.authenticate(request.cookies[ADMIN_COOKIE])
    fun ApplicationCall.q(name: String) = request.queryParameters[name]?.takeIf { it.isNotBlank() }
    fun ApplicationCall.int(name: String, default: Int) = q(name)?.toIntOrNull() ?: default
    fun ApplicationCall.p(name: String) = parameters[name]!!
    fun me(p: AdminPrincipal) = AdminMe(p.id.toString(), p.email, p.name, p.role.name, Permission.of(p.role).map { it.code })
    fun sessionCookie(value: String, maxAge: Long) = Cookie(
        ADMIN_COOKIE, value, maxAge = maxAge.toInt(), path = "/v1/admin", httpOnly = true, secure = secure, extensions = mapOf("SameSite" to "Strict"),
    )

    // ---- Session ----
    post("/session") {
        val req = call.receive<AdminLoginRequest>()
        val login = a.auth.login(req.email, req.password, call.request.origin.remoteHost)
        call.response.cookies.append(sessionCookie(login.token, a.s.config.adminSessionHours * 3600))
        call.respond(me(login.principal))
    }
    delete("/session") {
        runCatching { a.auth.logout(call.admin()) }
        call.response.cookies.append(sessionCookie("", 0))
        call.respond(HttpStatusCode.NoContent)
    }
    get("/me") { call.respond(me(call.admin())) }
    post("/me/password") {
        val p = call.admin()
        val req = call.receive<PasswordChangeRequest>()
        a.ops.changeOwnPassword(p, req.current, req.new)
        call.respond(HttpStatusCode.NoContent)
    }
    get("/meta") {
        call.admin()
        call.respond(AdminMeta(
            RepairStatus.entries.map { StatusMeta(it.name, it.title, it.allowedNext.map { n -> n.name }, it.beforePickup) },
            AdminRole.entries.associate { r -> r.name to Permission.of(r).map { it.code } },
        ))
    }
    get("/staff") { call.admin(); call.respond(a.ops.staffOptions()) }

    // ---- Overview and audit ----
    get("/overview") { call.admin(); call.respond(a.repairs.overview()) }
    get("/audit") {
        call.admin()
        call.respond(a.repairs.auditLog(AdminRepairs.AuditQuery(
            entityType = call.q("entityType"), entityId = call.q("entityId"), adminEmail = call.q("admin"), action = call.q("action"),
            before = call.q("before")?.toLongOrNull(), limit = call.int("limit", 50),
        )))
    }

    // ---- Repairs ----
    get("/repairs") {
        call.admin()
        call.respond(a.repairs.list(AdminRepairs.RepairFilter(
            q = call.q("q"),
            statuses = call.q("status")?.split(',')?.map { it.trim() }.orEmpty(),
            category = call.q("category"),
            delayedOnly = call.q("delayed") == "true",
            activeOnly = call.q("active") == "true",
            from = call.q("from")?.let(::parseDate), to = call.q("to")?.let(::parseDate),
            partnerId = call.q("partnerId")?.let { parseUuid(it, "partnerId") }, shopId = call.q("shopId")?.let { parseUuid(it, "shopId") },
            customerId = call.q("customerId")?.let { parseUuid(it, "customerId") },
            limit = call.int("limit", 50), offset = call.int("offset", 0),
        )))
    }
    route("/repairs/{id}") {
        get { call.admin(); call.respond(a.repairs.detail(call.p("id"))) }
        post("/status") { val p = call.admin(); call.respond(a.repairs.changeStatus(p, call.p("id"), call.receive())) }
        post("/assign") { val p = call.admin(); call.respond(a.repairs.assign(p, call.p("id"), call.receive())) }
        post("/reschedule") { val p = call.admin(); call.respond(a.repairs.reschedule(p, call.p("id"), call.receive())) }
        post("/cancel") { val p = call.admin(); call.respond(a.repairs.cancel(p, call.p("id"), call.receive<ReasonRequest>().reason)) }
        post("/notes") { val p = call.admin(); call.respond(a.repairs.addNote(p, call.p("id"), call.receive<NoteRequest>().body)) }
        get("/quote-draft") {
            call.admin()
            val d = a.s.db.tx { a.ops.draftIn(this, call.p("id")) } ?: throw notFound("No draft yet.")
            call.respond(d)
        }
        put("/quote-draft") { val p = call.admin(); call.respond(a.ops.saveDraft(p, call.p("id"), call.receive())) }
        post("/quote/submit") { val p = call.admin(); call.respond(a.ops.submitQuote(p, call.p("id"))) }
        get("/invoice") { call.admin(); call.respond(a.ops.invoice(call.p("id"))) }
    }

    // ---- Customers ----
    get("/customers") { call.admin(); call.respond(a.repairs.customers(call.q("q"), call.int("limit", 50), call.int("offset", 0))) }
    get("/customers/{id}") { call.admin(); call.respond(a.repairs.customer(call.p("id"))) }

    // ---- Services & pricing ----
    get("/catalog") { call.admin(); call.respond(a.catalog.catalog()) }
    get("/catalog/history") { call.admin(); call.respond(a.catalog.history(call.q("category"), call.q("issue"), call.int("limit", 200))) }
    post("/catalog/services") { val p = call.admin(); call.respond(HttpStatusCode.Created, a.catalog.createService(p, call.receive())) }
    get("/catalog/services/{category}/{issue}") { call.admin(); call.respond(a.catalog.service(call.p("category"), call.p("issue"))) }
    patch("/catalog/services/{category}/{issue}") { val p = call.admin(); call.respond(a.catalog.updateService(p, call.p("category"), call.p("issue"), call.receive())) }
    patch("/catalog/categories/{code}") { val p = call.admin(); call.respond(a.catalog.updateCategory(p, call.p("code"), call.receive())) }
    patch("/catalog/issues/{code}") { val p = call.admin(); call.respond(a.catalog.updateIssue(p, call.p("code"), call.receive())) }
    patch("/catalog/brands/{id}") {
        val p = call.admin()
        call.respond(a.catalog.updateBrand(p, call.p("id").toIntOrNull() ?: throw badRequest("Brand id must be a number."), call.receive()))
    }

    // ---- Partners ----
    get("/partners") { call.admin(); call.respond(a.catalog.partners()) }
    post("/partners/shops") { val p = call.admin(); call.respond(a.catalog.upsertShop(p, null, call.receive())) }
    put("/partners/shops/{id}") { val p = call.admin(); call.respond(a.catalog.upsertShop(p, call.p("id"), call.receive())) }
    post("/partners/technicians") { val p = call.admin(); call.respond(a.catalog.upsertTechnician(p, null, call.receive())) }
    put("/partners/technicians/{id}") { val p = call.admin(); call.respond(a.catalog.upsertTechnician(p, call.p("id"), call.receive())) }
    post("/partners/couriers") { val p = call.admin(); call.respond(a.catalog.upsertCourier(p, null, call.receive())) }
    put("/partners/couriers/{id}") { val p = call.admin(); call.respond(a.catalog.upsertCourier(p, call.p("id"), call.receive())) }
    get("/partners/{kind}/{id}/assignments") { call.admin(); call.respond(a.catalog.assignments(call.p("kind"), call.p("id"))) }

    // ---- Pickups & service areas ----
    get("/areas") { call.admin(); call.respond(a.catalog.areas()) }
    put("/areas/service-areas") { val p = call.admin(); call.respond(a.catalog.upsertArea(p, call.receive())) }
    put("/areas/slot-templates") { val p = call.admin(); call.respond(a.catalog.setSlotTemplate(p, call.receive())) }
    post("/areas/blackouts") { val p = call.admin(); call.respond(a.catalog.addBlackout(p, call.receive())) }
    post("/areas/blackouts/remove") {
        val p = call.admin()
        val req = call.receive<BlackoutRemoveRequest>()
        call.respond(a.catalog.removeBlackout(p, req.date, req.startHour, req.reason))
    }
    get("/logistics") { call.admin(); call.respond(a.catalog.logistics(call.q("date")?.let(::parseDate) ?: a.s.todayIst())) }

    // ---- Quotes ----
    get("/quotes") { call.admin(); call.respond(a.ops.quotes(call.q("state"))) }

    // ---- Payments ----
    get("/payments") { call.admin(); call.respond(a.ops.payments(call.q("unreconciled") == "true", call.q("method"))) }
    post("/payments/{repairId}/refunds") { val p = call.admin(); call.respond(a.ops.refund(p, call.p("repairId"), call.receive())) }
    post("/payments/{repairId}/reconcile") { val p = call.admin(); call.respond(a.ops.reconcile(p, call.p("repairId"), call.receive())) }

    // ---- Warranty claims ----
    get("/claims") { call.admin(); call.respond(a.ops.claims(call.q("status"))) }
    post("/claims/{id}/{action}") {
        val p = call.admin()
        val action = call.p("action").takeIf { it in setOf("approve", "reject", "request-info", "resolve") } ?: throw notFound()
        call.respond(a.ops.claimAction(p, call.p("id"), action, call.receive()))
    }

    // ---- Support ----
    get("/tickets") { val p = call.admin(); call.respond(a.ops.tickets(call.q("status"), call.q("assigned"), call.q("q"), p)) }
    get("/tickets/{id}") { call.admin(); call.respond(a.ops.ticket(call.p("id"))) }
    post("/tickets/{id}/assign") { val p = call.admin(); call.respond(a.ops.assignTicket(p, call.p("id"), call.receive())) }
    post("/tickets/{id}/replies") { val p = call.admin(); call.respond(a.ops.addTicketMessage(p, call.p("id"), call.receive<NoteRequest>().body, "CUSTOMER")) }
    post("/tickets/{id}/notes") { val p = call.admin(); call.respond(a.ops.addTicketMessage(p, call.p("id"), call.receive<NoteRequest>().body, "INTERNAL")) }
    post("/tickets/{id}/status") { val p = call.admin(); call.respond(a.ops.setTicketStatus(p, call.p("id"), call.receive())) }

    // ---- Team ----
    get("/team") { val p = call.admin(); call.respond(a.ops.team(p)) }
    post("/team") { val p = call.admin(); call.respond(HttpStatusCode.Created, a.ops.createUser(p, call.receive())) }
    patch("/team/{id}") { val p = call.admin(); call.respond(a.ops.updateUser(p, call.p("id"), call.receive())) }
    post("/team/{id}/password") { val p = call.admin(); call.respond(a.ops.resetPassword(p, call.p("id"), call.receive())) }
}
