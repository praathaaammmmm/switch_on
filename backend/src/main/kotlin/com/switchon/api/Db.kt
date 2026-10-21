package com.switchon.api

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.postgresql.util.PSQLException
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/** Marks a string bound as jsonb; write the placeholder as `?::jsonb` in SQL. */
@JvmInline value class Json(val raw: String)

class Db(val dataSource: DataSource) {

    /** Runs [block] in one transaction (READ COMMITTED; use `FOR UPDATE` where a row must not change underneath). */
    fun <T> tx(block: Tx.() -> T): T = dataSource.connection.use { c ->
        c.autoCommit = false
        try {
            val result = Tx(c).block()
            c.commit()
            result
        } catch (e: Throwable) {
            c.rollback()
            throw e
        }
    }

    companion object {
        fun connect(url: String, user: String, password: String, poolSize: Int = 10): Db {
            val ds = HikariDataSource(HikariConfig().apply {
                jdbcUrl = url
                username = user
                this.password = password
                maximumPoolSize = poolSize
                // Every timestamp read and written in UTC regardless of the server's zone.
                connectionInitSql = "SET TIME ZONE 'UTC'"
            })
            return Db(ds)
        }

        fun migrate(ds: DataSource) {
            Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate()
        }
    }
}

class Tx(val c: Connection) {

    private fun bind(sql: String, args: Array<out Any?>) = c.prepareStatement(sql).apply {
        args.forEachIndexed { i, a ->
            val n = i + 1
            when (a) {
                null -> setObject(n, null)
                is Json -> setString(n, a.raw)
                is Instant -> setObject(n, OffsetDateTime.ofInstant(a, ZoneOffset.UTC))
                is LocalDate -> setObject(n, a)
                is UUID -> setObject(n, a)
                is List<*> -> setArray(n, c.createArrayOf("text", a.map { it.toString() }.toTypedArray()))
                else -> setObject(n, a)
            }
        }
    }

    fun update(sql: String, vararg args: Any?): Int = bind(sql, args).use { it.executeUpdate() }

    fun <T> query(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> = bind(sql, args).use { st ->
        st.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
    }

    fun <T> one(sql: String, vararg args: Any?, map: (ResultSet) -> T): T? = query(sql, *args, map = map).firstOrNull()
}

fun ResultSet.instant(col: String): Instant? = getObject(col, OffsetDateTime::class.java)?.toInstant()
fun ResultSet.date(col: String): LocalDate? = getObject(col, LocalDate::class.java)
fun ResultSet.uuid(col: String): UUID? = getObject(col, UUID::class.java)
fun ResultSet.uuid(index: Int): UUID? = getObject(index, UUID::class.java)
fun ResultSet.date(index: Int): LocalDate? = getObject(index, LocalDate::class.java)
fun ResultSet.int(col: String): Int? = getInt(col).takeUnless { wasNull() }
@Suppress("UNCHECKED_CAST")
fun ResultSet.strings(col: String): List<String> = (getArray(col)?.array as? Array<String>)?.toList().orEmpty()

/** True when [e] is a unique-constraint violation, optionally on the named constraint. */
fun isUniqueViolation(e: Throwable, constraint: String? = null): Boolean {
    val p = generateSequence(e) { it.cause }.filterIsInstance<PSQLException>().firstOrNull() ?: return false
    return p.sqlState == "23505" && (constraint == null || p.serverErrorMessage?.constraint == constraint)
}
