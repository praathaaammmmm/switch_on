package com.switchon.api

/**
 * All configuration comes from environment variables; nothing secret is committed.
 * Internal (dev/test) routes exist only when APP_ENV=development *and* INTERNAL_API_TOKEN is set.
 */
data class Config(
    val port: Int,
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    val environment: String,
    val internalToken: String?,
    val seedDemoForNewGuests: Boolean,
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
            )
        }
    }
}
