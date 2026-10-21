package com.switchon.customer.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.switchon.customer.SwitchOnApp
import com.switchon.customer.data.remote.RemoteBackend
import java.util.concurrent.TimeUnit

/** Sends queued bookings and support tickets once there's a connection. Never touches payments or quote decisions. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val backend = (applicationContext as SwitchOnApp).graph.backend as? RemoteBackend ?: return Result.success()
        return if (backend.flushPending()) Result.success() else Result.retry()
    }

    companion object {
        private const val NAME = "switchon-sync"

        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
