package com.switchon.api

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.security.MessageDigest

fun main() {
    val config = Config.fromEnv()
    val db = Db.connect(config.databaseUrl, config.databaseUser, config.databasePassword)
    Db.migrate(db.dataSource)
    val services = Services(db, config)
    if (AdminAuth(services).seedOwner()) LoggerFactory.getLogger("api").warn("Created the development owner ${config.adminOwnerEmail} from ADMIN_OWNER_EMAIL")
    embeddedServer(Netty, port = config.port) { module(services) }.start(wait = true)
}

@Serializable data class Health(val status: String, val environment: String)

fun Application.module(services: Services) {
    val log = LoggerFactory.getLogger("api")
    install(ContentNegotiation) { json(json) }
    install(CallLogging)
    install(StatusPages) {
        exception<ApiException> { call, e -> call.respond(e.status, ErrorBody(ErrorDetail(e.code, e.message ?: ""))) }
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorBody(ErrorDetail("invalid_request", "The request body is missing a field or has the wrong type.")))
        }
        exception<Throwable> { call, e ->
            log.error("Unhandled error", e)
            call.respond(HttpStatusCode.InternalServerError, ErrorBody(ErrorDetail("server_error", "Something went wrong on our side. Nothing was changed.")))
        }
    }

    routing {
        get("/health") { call.respond(Health("ok", services.config.environment)) }
        route("/v1") { publicRoutes(services) }
        // Staff dashboard: cookie sessions, role checks and an audit row for every change.
        route("/v1/admin") { adminRoutes(AdminApi(services)) }
        if (services.config.internalRoutesEnabled) {
            log.warn("Internal development routes are ENABLED at /internal/v1")
            route("/internal/v1") { internalRoutes(services) }
        }
    }
}

private fun ApplicationCall.customer(s: Services) =
    s.authenticate(request.header("Authorization")?.removePrefix("Bearer ")?.trim())

private suspend fun ApplicationCall.created(body: Any, isNew: Boolean) =
    respond(if (isNew) HttpStatusCode.Created else HttpStatusCode.OK, body)

private fun Route.publicRoutes(s: Services) {
    post("/guest-sessions") {
        val res = s.guestSession(call.receive<GuestSessionRequest>().installationId)
        call.respond(if (res.created) HttpStatusCode.Created else HttpStatusCode.OK, res)
    }
    get("/me") { call.respond(s.profile(call.customer(s))) }

    // Catalogue and availability (no customer data, but still behind a session to keep the API closed).
    get("/catalog/devices") { call.customer(s); call.respond(s.devices()) }
    get("/catalog/issues") { call.customer(s); call.respond(s.issues()) }
    post("/estimates") { call.customer(s); call.respond(s.estimate(call.receive())) }
    get("/serviceability") {
        call.customer(s)
        call.respond(s.serviceability(call.request.queryParameters["pinCode"] ?: throw badRequest("pinCode is required.")))
    }
    get("/pickup-slots") { call.customer(s); call.respond(s.slots()) }

    get("/addresses") { call.respond(s.addresses(call.customer(s))) }
    post("/addresses") { val c = call.customer(s); call.respond(HttpStatusCode.Created, s.createAddress(c, call.receive())) }
    delete("/addresses/{id}") { s.deleteAddress(call.customer(s), call.parameters["id"]!!); call.respond(HttpStatusCode.NoContent) }
    post("/addresses/{id}/default") { s.makeDefaultAddress(call.customer(s), call.parameters["id"]!!); call.respond(HttpStatusCode.NoContent) }

    get("/repairs") { call.respond(RepairsResponse(s.repairs(call.customer(s)))) }
    post("/repairs") {
        val c = call.customer(s)
        val (repair, isNew) = s.createRepair(c, call.receive())
        call.created(repair, isNew)
    }
    route("/repairs/{repairId}") {
        get { call.respond(s.repair(call.customer(s), call.parameters["repairId"]!!)) }
        post("/cancel") { val c = call.customer(s); call.respond(s.cancel(c, call.parameters["repairId"]!!, call.receive<CancelRequest>().reason)) }
        post("/reschedule") { val c = call.customer(s); call.respond(s.reschedule(c, call.parameters["repairId"]!!, call.receive<RescheduleRequest>().slot)) }
        post("/quote-decision") { val c = call.customer(s); call.respond(s.decideQuote(c, call.parameters["repairId"]!!, call.receive())) }
        post("/payment") { val c = call.customer(s); call.respond(s.pay(c, call.parameters["repairId"]!!, call.receive())) }
        get("/invoice") { call.respond(s.invoice(call.customer(s), call.parameters["repairId"]!!)) }
    }

    get("/warranty-claims") { call.respond(WarrantyClaimsResponse(s.claims(call.customer(s)))) }
    post("/warranty-claims") {
        val c = call.customer(s)
        val (created, isNew) = s.createClaim(c, call.receive())
        call.created(created, isNew)
    }

    get("/support-tickets") { call.respond(SupportTicketsResponse(s.tickets(call.customer(s)))) }
    post("/support-tickets") {
        val c = call.customer(s)
        val (ticket, isNew) = s.createTicket(c, call.receive())
        call.created(ticket, isNew)
    }
}

/** Development tools: stand-ins for the partner and shop apps. Never installed in production. */
private fun Route.internalRoutes(s: Services) {
    val expected = s.config.internalToken!!.toByteArray()
    fun ApplicationCall.requireInternal() {
        val given = request.header("X-Internal-Token")?.toByteArray() ?: ByteArray(0)
        if (!MessageDigest.isEqual(given, expected)) throw ApiException(HttpStatusCode.Forbidden, "forbidden", "Internal token required.")
    }
    post("/repairs/{repairId}/advance") { call.requireInternal(); call.respond(s.advance(call.parameters["repairId"]!!)) }
    post("/repairs/{repairId}/quote") { call.requireInternal(); call.respond(s.internalQuote(call.parameters["repairId"]!!, call.receive())) }
    post("/repairs/{repairId}/pickup-failed") { call.requireInternal(); call.respond(s.pickupFailed(call.parameters["repairId"]!!, call.receive<InternalNoteRequest>().reason)) }
    post("/repairs/{repairId}/not-possible") { call.requireInternal(); call.respond(s.notPossible(call.parameters["repairId"]!!, call.receive<InternalNoteRequest>().reason)) }
    post("/customers/{customerId}/seed-demo") {
        call.requireInternal()
        DemoSeeder(s).seed(parseUuid(call.parameters["customerId"]!!, "customerId"))
        call.respond(HttpStatusCode.NoContent)
    }
}
