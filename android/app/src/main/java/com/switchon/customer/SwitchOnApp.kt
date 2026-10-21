package com.switchon.customer

import android.app.Application
import android.content.Context
import com.switchon.customer.data.ActionQueue
import com.switchon.customer.data.AndroidNetworkMonitor
import com.switchon.customer.data.SyncWorker
import com.switchon.customer.data.backend.MockBackend
import com.switchon.customer.data.backend.RepairBackend
import com.switchon.customer.data.diagnostics.PrefsSavedReports
import com.switchon.customer.data.diagnostics.SavedReports
import com.switchon.customer.data.local.CacheDatabase
import com.switchon.customer.data.local.RoomLocalStore
import com.switchon.customer.data.remote.EncryptedSessionStore
import com.switchon.customer.data.remote.MemorySessionStore
import com.switchon.customer.data.remote.RemoteBackend
import com.switchon.customer.data.remote.SessionStore
import com.switchon.customer.data.remote.buildApi

class SwitchOnApp : Application() {
    val graph by lazy { AppGraph(this) }
}

/** Wires the app together. The flavor decides the backend: prod → the API, demo → sample data in memory. */
class AppGraph(context: Context) {
    private val app = context.applicationContext
    val network = AndroidNetworkMonitor(app)
    val session: SessionStore = if (BuildConfig.DEMO_MODE) MemorySessionStore() else EncryptedSessionStore(app)
    val reports: SavedReports = PrefsSavedReports(app)

    val backend: RepairBackend = if (BuildConfig.DEMO_MODE) MockBackend() else RemoteBackend(
        api = buildApi(BuildConfig.API_BASE_URL, session, debugLogging = BuildConfig.DEBUG),
        session = session,
        store = RoomLocalStore(CacheDatabase.create(app).dao()),
        internalToken = BuildConfig.INTERNAL_TOKEN,
        scheduleSync = { SyncWorker.schedule(app) },
        onBookingSent = reports::link,
    )

    val queue: ActionQueue? = backend as? ActionQueue
}
