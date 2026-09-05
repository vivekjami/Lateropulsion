package com.lateropulsion.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lateropulsion.core.common.Clock
import com.lateropulsion.core.common.LpLog
import com.lateropulsion.core.datastore.SettingsStore
import com.lateropulsion.core.model.ClinicianId
import com.lateropulsion.core.model.MediaRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Retention (ARCHITECTURE §11.3): the worker only *lists* expired media and records the count; deletion
 * requires a clinician's explicit confirmation in Settings. Automated silent deletion of clinical records
 * is a poor idea in a regulated context.
 */
@Singleton
class RetentionCheck @Inject constructor(private val media: MediaRepository, private val settings: SettingsStore, private val clock: Clock) {
    suspend fun scan(): Int {
        val n = media.expired(clock.nowUtcMillis()).size
        settings.update { it.copy(retentionPendingCount = n, lastRetentionCheckAt = clock.nowUtcMillis()) }
        return n
    }

    suspend fun pending(): Int = settings.current().retentionPendingCount

    suspend fun deleteExpired(by: ClinicianId?): Int {
        var n = 0
        for (m in media.expired(clock.nowUtcMillis())) {
            runCatching { File(m.pathEncrypted).delete() }
            if (media.delete(m.id).isSuccess) n++
        }
        settings.update { it.copy(retentionPendingCount = 0) }
        LpLog.i("Retention", "expired media deleted after confirmation", "count" to n, "by" to (by?.value?.take(8) ?: "unknown"))
        return n
    }
}

@HiltWorker
class RetentionWorker @AssistedInject constructor(@Assisted ctx: Context, @Assisted params: WorkerParameters, private val check: RetentionCheck) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result { check.scan(); return Result.success() }

    companion object {
        fun schedule(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("retention-check", ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<RetentionWorker>(1, TimeUnit.DAYS).build())
        }
    }
}
