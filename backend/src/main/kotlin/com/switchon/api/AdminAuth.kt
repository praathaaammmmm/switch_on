package com.switchon.api

import io.ktor.http.HttpStatusCode
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

// =============================================================================================================
// Roles and permissions
// =============================================================================================================

enum class AdminRole { OWNER, OPERATIONS, SUPPORT, FINANCE, VIEWER }

/**
 * What each role may change. Every role can read the dashboard (except the team list); VIEWER can only read.
 * Checked on the server for every mutation; the dashboard uses the same list to hide controls.
 */
enum class Permission(val code: String, vararg roles: AdminRole) {
    REPAIRS_MANAGE("repairs.manage", AdminRole.OWNER, AdminRole.OPERATIONS),
    REPAIRS_NOTE("repairs.note", AdminRole.OWNER, AdminRole.OPERATIONS, AdminRole.SUPPORT),
    PRICING_MANAGE("pricing.manage", AdminRole.OWNER, AdminRole.OPERATIONS),
    PARTNERS_MANAGE("partners.manage", AdminRole.OWNER, AdminRole.OPERATIONS),
    AREAS_MANAGE("areas.manage", AdminRole.OWNER, AdminRole.OPERATIONS),
    QUOTES_MANAGE("quotes.manage", AdminRole.OWNER, AdminRole.OPERATIONS),
    PAYMENTS_MANAGE("payments.manage", AdminRole.OWNER, AdminRole.FINANCE),
    CLAIMS_MANAGE("claims.manage", AdminRole.OWNER, AdminRole.OPERATIONS, AdminRole.SUPPORT),
    SUPPORT_MANAGE("support.manage", AdminRole.OWNER, AdminRole.OPERATIONS, AdminRole.SUPPORT),
    TEAM_MANAGE("team.manage", AdminRole.OWNER);

    val roles: Set<AdminRole> = roles.toSet()

    companion object {
        fun of(role: AdminRole) = entries.filter { role in it.roles }
    }
}

/** The signed-in staff member for one request. */
data class AdminPrincipal(val id: UUID, val email: String, val name: String, val role: AdminRole, val sessionHash: String) {
    fun can(p: Permission) = role in p.roles
    fun require(p: Permission) {
        if (!can(p)) throw ApiException(HttpStatusCode.Forbidden, "forbidden", "Your role (${role.name.lowercase()}) can't do this.")
    }
}

// =============================================================================================================
// Passwords: PBKDF2-HMAC-SHA512 from the JDK, so no extra crypto dependency
// =============================================================================================================

object Passwords {
    private const val ALGORITHM = "PBKDF2WithHmacSHA512"
    /** OWASP's 2023 recommendation for PBKDF2-HMAC-SHA512. Stored per hash, so it can rise later without breaking logins. */
    const val ITERATIONS = 210_000
    const val MIN_LENGTH = 12
    private val random = SecureRandom()
    private val b64 = Base64.getEncoder()
    private val unb64 = Base64.getDecoder()

    fun hash(password: String, iterations: Int = ITERATIONS): String {
        val salt = ByteArray(16).also(random::nextBytes)
        return "pbkdf2_sha512\$$iterations\$${b64.encodeToString(salt)}\$${b64.encodeToString(derive(password, salt, iterations))}"
    }

    fun verify(password: String, stored: String): Boolean {
        val parts = stored.split('$')
        if (parts.size != 4 || parts[0] != "pbkdf2_sha512") return false
        val iterations = parts[1].toIntOrNull() ?: return false
        val expected = unb64.decode(parts[3])
        return MessageDigest.isEqual(derive(password, unb64.decode(parts[2]), iterations), expected)
    }

    /** A hash to verify against when the email is unknown, so timing doesn't reveal which emails exist. */
    val dummy: String by lazy { hash("not-a-real-password-" + UUID.randomUUID()) }

    fun policyProblem(password: String): String? = when {
        password.length < MIN_LENGTH -> "Use at least $MIN_LENGTH characters."
        password.length > 200 -> "Use at most 200 characters."
        password.isBlank() -> "The password can't be blank."
        else -> null
    }

    private fun derive(password: String, salt: ByteArray, iterations: Int): ByteArray =
        SecretKeyFactory.getInstance(ALGORITHM).generateSecret(PBEKeySpec(password.toCharArray(), salt, iterations, 512)).encoded
}

// =============================================================================================================
// Login throttling: in memory, per email and per client address
// =============================================================================================================

/** After [limit] failures inside [window], further attempts for that key are refused until the window passes. */
class LoginThrottle(private val limit: Int = 5, private val window: Duration = Duration.ofMinutes(15)) {
    private val failures = ConcurrentHashMap<String, MutableList<Instant>>()

    fun blocked(key: String, now: Instant): Boolean = recent(key, now).size >= limit

    fun fail(key: String, now: Instant) { failures.compute(key) { _, l -> (l ?: mutableListOf()).apply { add(now) } } }
    fun clear(key: String) { failures.remove(key) }

    private fun recent(key: String, now: Instant): List<Instant> =
        failures.computeIfPresent(key) { _, l -> l.apply { removeIf { it.isBefore(now.minus(window)) } }.takeIf { it.isNotEmpty() } }.orEmpty()
}

// =============================================================================================================
// Sessions
// =============================================================================================================

const val ADMIN_COOKIE = "so_admin"

class AdminAuth(private val s: Services) {
    private val db = s.db
    private val random = SecureRandom()
    val throttle = LoginThrottle()

    data class Login(val token: String, val principal: AdminPrincipal, val expiresAt: Instant)

    fun login(email: String, password: String, client: String): Login {
        val now = s.now()
        val key = email.trim().lowercase()
        if (throttle.blocked("email:$key", now) || throttle.blocked("ip:$client", now))
            throw ApiException(HttpStatusCode.TooManyRequests, "too_many_attempts", "Too many failed sign-ins. Wait 15 minutes and try again.")
        val user = db.tx {
            one("SELECT id, email, name, role, password_hash, active FROM admin_users WHERE lower(email) = ?", key) {
                Triple(AdminPrincipal(it.uuid("id")!!, it.getString("email"), it.getString("name"), AdminRole.valueOf(it.getString("role")), ""),
                    it.getString("password_hash"), it.getBoolean("active"))
            }
        }
        val ok = Passwords.verify(password, user?.second ?: Passwords.dummy) && user != null && user.third
        if (!ok) {
            throttle.fail("email:$key", now); throttle.fail("ip:$client", now)
            db.tx { audit(this, null, key.take(200), "auth.login_failed", "admin_user", user?.first?.id?.toString() ?: "unknown", null, null, null) }
            throw ApiException(HttpStatusCode.Unauthorized, "invalid_credentials", "That email and password don't match an active account.")
        }
        throttle.clear("email:$key")
        val token = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        val hash = s.sha256(token)
        val expires = now.plus(Duration.ofHours(s.config.adminSessionHours))
        val principal = user!!.first.copy(sessionHash = hash)
        db.tx {
            update("INSERT INTO admin_sessions (token_hash, admin_id, created_at, last_seen_at, expires_at) VALUES (?,?,?,?,?)", hash, principal.id, now, now, expires)
            update("UPDATE admin_users SET last_login_at = ? WHERE id = ?", now, principal.id)
            audit(this, principal, "auth.login", "admin_user", principal.id.toString(), null, null, null)
        }
        return Login(token, principal, expires)
    }

    /** The staff member behind a session cookie, or 401. Expired, revoked and deactivated sessions all fail. */
    fun authenticate(token: String?): AdminPrincipal {
        if (token.isNullOrBlank()) throw adminUnauthorized()
        val hash = s.sha256(token)
        val now = s.now()
        return db.tx {
            val p = one(
                """SELECT u.id, u.email, u.name, u.role FROM admin_sessions s JOIN admin_users u ON u.id = s.admin_id
                   WHERE s.token_hash = ? AND s.revoked_at IS NULL AND s.expires_at > ? AND u.active""", hash, now,
            ) { AdminPrincipal(it.uuid("id")!!, it.getString("email"), it.getString("name"), AdminRole.valueOf(it.getString("role")), hash) }
                ?: throw adminUnauthorized()
            update("UPDATE admin_sessions SET last_seen_at = ? WHERE token_hash = ?", now, hash)
            p
        }
    }

    fun logout(p: AdminPrincipal) = db.tx {
        update("UPDATE admin_sessions SET revoked_at = ? WHERE token_hash = ? AND revoked_at IS NULL", s.now(), p.sessionHash)
        audit(this, p, "auth.logout", "admin_user", p.id.toString(), null, null, null)
    }

    /**
     * Development only: makes sure the owner named in ADMIN_OWNER_EMAIL / ADMIN_OWNER_PASSWORD exists. Never overwrites
     * an existing account, so changing the password in the dashboard sticks.
     */
    fun seedOwner(): Boolean {
        val email = s.config.adminOwnerEmail ?: return false
        val password = s.config.adminOwnerPassword ?: return false
        if (!s.config.isDevelopment) return false
        Passwords.policyProblem(password)?.let { error("ADMIN_OWNER_PASSWORD: $it") }
        return db.tx {
            if (one("SELECT 1 FROM admin_users WHERE lower(email) = lower(?)", email) { 1 } != null) return@tx false
            val id = UUID.randomUUID()
            update("INSERT INTO admin_users (id, email, name, role, password_hash) VALUES (?,?,?,?,?)", id, email.trim(), "Owner", "OWNER", Passwords.hash(password))
            audit(this, null, "system", "team.seed_owner", "admin_user", id.toString(), "Development owner from ADMIN_OWNER_EMAIL", null, mapOf("email" to email, "role" to "OWNER"))
            true
        }
    }
}

fun adminUnauthorized() = ApiException(HttpStatusCode.Unauthorized, "unauthorized", "Sign in to continue.")

// =============================================================================================================
// Audit log
// =============================================================================================================

/**
 * Appends one audit row in the caller's transaction, so the record and the change commit (or roll back) together.
 * [before] and [after] hold only the fields that matter for the action. The table rejects updates and deletes.
 */
fun audit(tx: Tx, p: AdminPrincipal?, action: String, entityType: String, entityId: String, reason: String?, before: Map<String, Any?>?, after: Map<String, Any?>?) =
    audit(tx, p?.id, p?.email ?: "system", action, entityType, entityId, reason, before, after)

fun audit(tx: Tx, adminId: UUID?, email: String, action: String, entityType: String, entityId: String, reason: String?, before: Map<String, Any?>?, after: Map<String, Any?>?) {
    tx.update(
        "INSERT INTO admin_audit_log (admin_id, admin_email, action, entity_type, entity_id, reason, before, after, occurred_at) VALUES (?,?,?,?,?,?,?::jsonb,?::jsonb,now())",
        adminId, email, action, entityType, entityId, reason, before?.let { Json(jsonObject(it)) }, after?.let { Json(jsonObject(it)) },
    )
}

/** Small JSON encoder for audit snapshots: strings, numbers, booleans, nulls and lists of those. */
fun jsonObject(m: Map<String, Any?>): String = m.entries.joinToString(",", "{", "}") { (k, v) -> "${quote(k)}:${jsonValue(v)}" }

private fun jsonValue(v: Any?): String = when (v) {
    null -> "null"
    is Number, is Boolean -> v.toString()
    is Collection<*> -> v.joinToString(",", "[", "]") { jsonValue(it) }
    is Map<*, *> -> jsonObject(v.entries.associate { it.key.toString() to it.value })
    else -> quote(v.toString())
}

private fun quote(s: String) = buildString {
    append('"')
    s.forEach { c ->
        when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
    }
    append('"')
}
