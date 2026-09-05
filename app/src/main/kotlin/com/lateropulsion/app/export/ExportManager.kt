package com.lateropulsion.app.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.lateropulsion.core.common.Clock
import com.lateropulsion.core.model.AuditAction
import com.lateropulsion.core.model.AuditEvent
import com.lateropulsion.core.model.AuditRepository
import com.lateropulsion.core.model.ClinicianId
import com.lateropulsion.core.model.Ids
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes an export to app cache and hands it to the system share sheet through a FileProvider URI.
 * Every export is audited with its kind and whether it was de-identified (REQ-SEC-005/006).
 */
@Singleton
class ExportManager @Inject constructor(@ApplicationContext private val ctx: Context, private val audit: AuditRepository, private val clock: Clock) {
    suspend fun share(fileName: String, mime: String, kind: String, targetType: String, targetId: String, deidentified: Boolean, clinician: ClinicianId?, write: (File) -> Unit): Uri = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.filter { clock.nowUtcMillis() - it.lastModified() > 6 * 3600_000L }?.forEach { it.delete() }
        val f = File(dir, fileName)
        write(f)
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.exports", f)
        audit.record(AuditEvent(Ids.audit(), clinician, clock.nowUtcMillis(), AuditAction.EXPORT, targetType, targetId, "kind=$kind deidentified=$deidentified file=$fileName"))
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(Intent.createChooser(intent, "Share $kind").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        uri
    }
}
