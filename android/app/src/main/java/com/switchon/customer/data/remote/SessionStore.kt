package com.switchon.customer.data.remote

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

/** The guest identity on this device. */
interface SessionStore {
    /** Generated once on first launch and kept for the life of the installation. */
    val installationId: String
    var token: String?
    var customerId: String?
    /** The customer has gone past onboarding at least once; the app opens straight on Home next time. */
    var entered: Boolean
}

/** Keeps the guest identity in EncryptedSharedPreferences (AES-256, key in the Android Keystore). */
class EncryptedSessionStore(context: Context) : SessionStore {
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "switchon_session",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override val installationId: String = prefs.getString(KEY_INSTALLATION, null)
        ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY_INSTALLATION, it).apply() }

    override var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(v) = prefs.edit().putString(KEY_TOKEN, v).apply()

    override var customerId: String?
        get() = prefs.getString(KEY_CUSTOMER, null)
        set(v) = prefs.edit().putString(KEY_CUSTOMER, v).apply()

    override var entered: Boolean
        get() = prefs.getBoolean(KEY_ENTERED, false)
        set(v) = prefs.edit().putBoolean(KEY_ENTERED, v).apply()

    private companion object {
        const val KEY_INSTALLATION = "installation_id"
        const val KEY_TOKEN = "session_token"
        const val KEY_CUSTOMER = "customer_id"
        const val KEY_ENTERED = "entered"
    }
}

/** In-memory store for the demo build and tests. */
class MemorySessionStore(override val installationId: String = UUID.randomUUID().toString()) : SessionStore {
    override var token: String? = null
    override var customerId: String? = null
    override var entered: Boolean = false
}
