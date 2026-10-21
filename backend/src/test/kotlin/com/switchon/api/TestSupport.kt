package com.switchon.api

import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * A throwaway PostgreSQL database for one test class: created on the server in TEST_DB_URL, migrated with Flyway,
 * and dropped by [close]. Run the suite with `./dev test`, which starts the local Postgres container first.
 */
class TestDatabase : AutoCloseable {
    private val adminUrl = System.getenv("TEST_DB_URL") ?: "jdbc:postgresql://localhost:5433/postgres"
    private val user = System.getenv("TEST_DB_USER") ?: "switchon"
    private val password = System.getenv("TEST_DB_PASSWORD") ?: error("TEST_DB_PASSWORD is not set. Run tests with ./dev test.")
    private val name = "switchon_test_" + UUID.randomUUID().toString().replace("-", "").take(12)
    val db: Db

    init {
        DriverManager.getConnection(adminUrl, user, password).use { it.createStatement().execute("CREATE DATABASE $name") }
        db = Db.connect(adminUrl.substringBeforeLast('/') + "/" + name, user, password, poolSize = 4)
        Db.migrate(db.dataSource)
    }

    override fun close() {
        (db.dataSource as AutoCloseable).close()
        DriverManager.getConnection(adminUrl, user, password).use { it.createStatement().execute("DROP DATABASE IF EXISTS $name WITH (FORCE)") }
    }
}

/** A clock tests can move forward. */
class MutableClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = object : Clock() {
        override fun getZone() = zone
        override fun withZone(z: ZoneId?) = this@MutableClock.withZone(z)
        override fun instant() = this@MutableClock.now
    }
    override fun instant(): Instant = now
    fun plusDays(d: Long) { now = now.plusSeconds(d * 86_400) }
}

val devConfig = Config(8080, "unused", "unused", "unused", "development", "test-internal-token", seedDemoForNewGuests = false)

/** Common setup: a guest with one serviceable address, and a booking helper. */
class Fixture(val s: Services) {
    val customer: UUID
    val address: AddressDto

    init {
        val session = s.guestSession(UUID.randomUUID().toString())
        customer = UUID.fromString(session.profile.customerId)
        address = s.createAddress(customer, AddressRequest("Home", "Flat 4B", "12th Main", "Indiranagar", "Bengaluru", "560038"))
    }

    fun tomorrow(hour: Int = 13) = SlotRef(s.todayIst().plusDays(1).toString(), hour)

    fun book(
        category: String = "PHONE", issue: String = "SCREEN", brand: String? = "Samsung", requestId: UUID = UUID.randomUUID(),
        customer: UUID = this.customer, addressId: String = address.id, slot: SlotRef = tomorrow(),
    ): RepairDto = s.createRepair(
        customer, CreateRepairRequest(requestId.toString(), category, issue, brand, null, listOf("Cracked glass"), "Dropped it", 1, addressId, slot, "Priya Sharma", "9845012873"),
    ).first

    /** Advance until [status] (internal tools), approving the quote on the way if asked. */
    fun advanceTo(id: String, status: RepairStatus, approve: Boolean = true): RepairDto {
        var r = s.repair(customer, id)
        while (r.status != status.name) {
            r = if (r.status == RepairStatus.QUOTE_PENDING.name) s.decideQuote(customer, id, QuoteDecisionRequest(if (approve) "APPROVE" else "DECLINE"))
            else s.advance(id)
        }
        return r
    }

    fun pay(id: String, key: UUID = UUID.randomUUID(), method: String = "UPI") =
        s.pay(customer, id, PaymentRequest(method, key.toString(), upiId = "priya@okhdfcbank", cashConfirmed = true))
}

fun fixedClockAt(date: LocalDate, hourIst: Int = 9): MutableClock =
    MutableClock(date.atTime(hourIst, 0).atZone(IST).toInstant())
