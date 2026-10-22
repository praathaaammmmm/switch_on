package com.switchon.api

/**
 * All configuration comes from environment variables; nothing secret is committed.
 * Internal (dev/test) routes exist only when APP_ENV=development *and* INTERNAL_API_TOKEN is set.
 * The admin API (/v1/admin) is always on, protected by staff sessions; the owner seed runs only in development.
 */
data class Config(
    val port: Int,
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    val environment: String,
    val internalToken: String?,
    val seedDemoForNewGuests: Boolean,
    /** Development only: creates this owner on startup if no admin with the email exists. Both come from the environment. */
    val adminOwnerEmail: String? = null,
    val adminOwnerPassword: String? = null,
    /** Secure-flag the admin session cookie. Always on in production; off by default in development (plain http://localhost). */
    val adminCookieSecure: Boolean = environment != "development",
    val adminSessionHours: Long = 12,
) {
    val isDevelopment get() = environment == "development"
    val internalRoutesEnabled get() = isDevelopment && !internalToken.isNullOrBlank()

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            fun req(name: String) = env[name]?.takeIf { it.isNotBlank() } ?: error("Missing required environment variable $name")
            val environment = env["APP_ENV"] ?: "production"
            require(environment in setOf("development", "production")) { "APP_ENV must be development or production" }
            return Config(
                port = env["PORT"]?.toInt() ?: 8080,
                databaseUrl = req("DATABASE_URL"),
                databaseUser = req("DATABASE_USER"),
                databasePassword = req("DATABASE_PASSWORD"),
                environment = environment,
                internalToken = env["INTERNAL_API_TOKEN"],
                seedDemoForNewGuests = environment == "development" && env["SEED_DEMO_DATA"] == "true",
                adminOwnerEmail = env["ADMIN_OWNER_EMAIL"]?.takeIf { it.isNotBlank() },
                adminOwnerPassword = env["ADMIN_OWNER_PASSWORD"]?.takeIf { it.isNotBlank() },
                adminCookieSecure = environment != "development" || env["ADMIN_COOKIE_SECURE"] == "true",
                adminSessionHours = env["ADMIN_SESSION_HOURS"]?.toLongOrNull()?.coerceIn(1, 24) ?: 12,
            )
        }
    }
}
