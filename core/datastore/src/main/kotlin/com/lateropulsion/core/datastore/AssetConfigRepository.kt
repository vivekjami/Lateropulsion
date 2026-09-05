package com.lateropulsion.core.datastore

import com.lateropulsion.core.common.LpException
import com.lateropulsion.core.common.LpLog
import com.lateropulsion.core.common.fold
import com.lateropulsion.core.model.AppConfig
import com.lateropulsion.core.model.ConfigRepository
import com.lateropulsion.core.model.DeviceProfile
import com.lateropulsion.core.model.HeadsetProfile
import com.lateropulsion.core.model.LpJson
import com.lateropulsion.core.model.ProtocolSpec
import com.lateropulsion.core.model.ScaleDefinition
import com.lateropulsion.feature.assessment.ScaleLoader
import com.lateropulsion.feature.protocol.ProtocolLoader
import com.lateropulsion.feature.protocol.ProtocolValidator
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Reads a named text asset; abstracted so tests can use the repository's `config/` directory. */
public interface AssetSource {
    public fun read(path: String): String?
    public fun list(dir: String): List<String>
}

/**
 * Loads the versioned configuration bundled as assets under `config/` (ADR-007). Every file is
 * validated on first load; a bad file is logged and skipped rather than crashing the app, and the
 * offending id is reported through [loadErrors] so the dashboard can show it.
 */
public class AssetConfigRepository(private val assets: AssetSource) : ConfigRepository {
    private val mutex = Mutex()
    private var app: AppConfig? = null
    private var protocols: List<ProtocolSpec>? = null
    private var scales: List<ScaleDefinition>? = null
    private var devices: List<DeviceProfile>? = null
    private var headsets: List<HeadsetProfile>? = null
    private val errors = ArrayList<String>()

    public val loadErrors: List<String> get() = errors

    override suspend fun appConfig(): AppConfig = mutex.withLock {
        app ?: run {
            val text = assets.read("config/app.json") ?: throw LpException(com.lateropulsion.core.common.LpError.Io("config/app.json missing"))
            val cfg = LpJson.lenient.decodeFromString(AppConfig.serializer(), text)
            val problems = cfg.validate()
            if (problems.isNotEmpty()) throw LpException(com.lateropulsion.core.common.LpError.Validation("app.json", problems.joinToString("; ")))
            cfg.also { app = it }
        }
    }

    override suspend fun protocols(): List<ProtocolSpec> {
        val cfg = appConfig()
        return mutex.withLock {
            protocols ?: run {
                val list = ArrayList<ProtocolSpec>()
                for (name in assets.list("config/protocols").filter { it.endsWith(".json") }.sorted()) {
                    val text = assets.read("config/protocols/$name") ?: continue
                    ProtocolLoader.parse(text, cfg).fold(
                        onSuccess = { list += it },
                        onFailure = { errors += "protocols/$name: ${it.message}"; LpLog.e(TAG, "protocol rejected", null, "file" to name, "reason" to it.message) },
                    )
                }
                val lib = ProtocolValidator.validateLibrary(list, cfg).filter { it.blocking }
                for (issue in lib) { errors += "library: $issue"; LpLog.e(TAG, "protocol library issue", null, "issue" to issue.toString()) }
                list.also { protocols = it }
            }
        }
    }

    override suspend fun protocol(id: String): ProtocolSpec? = protocols().firstOrNull { it.protocolId == id }

    override suspend fun scales(): List<ScaleDefinition> = mutex.withLock {
        scales ?: run {
            val list = ArrayList<ScaleDefinition>()
            for (name in assets.list("config/scales").filter { it.endsWith(".json") }.sorted()) {
                val text = assets.read("config/scales/$name") ?: continue
                ScaleLoader.parse(text).fold(
                    onSuccess = { list += it },
                    onFailure = { errors += "scales/$name: ${it.message}"; LpLog.e(TAG, "scale rejected", null, "file" to name, "reason" to it.message) },
                )
            }
            list.also { scales = it }
        }
    }

    override suspend fun scale(code: String): ScaleDefinition? = scales().firstOrNull { it.code == code }

    override suspend fun deviceProfiles(): List<DeviceProfile> = mutex.withLock {
        devices ?: run {
            val list = ArrayList<DeviceProfile>()
            for (name in assets.list("config/devices").filter { it.endsWith(".json") }.sorted()) {
                val text = assets.read("config/devices/$name") ?: continue
                try {
                    list += LpJson.lenient.decodeFromString(DeviceProfile.serializer(), text)
                } catch (e: kotlinx.serialization.SerializationException) {
                    errors += "devices/$name: ${e.message}"; LpLog.e(TAG, "device profile rejected", e, "file" to name)
                }
            }
            list.also { devices = it }
        }
    }

    override suspend fun headsetProfiles(): List<HeadsetProfile> = mutex.withLock {
        headsets ?: run {
            val list = ArrayList<HeadsetProfile>()
            for (name in assets.list("config/headsets").filter { it.endsWith(".json") }.sorted()) {
                val text = assets.read("config/headsets/$name") ?: continue
                try {
                    val h = LpJson.lenient.decodeFromString(HeadsetProfile.serializer(), text)
                    val problems = h.validate()
                    if (problems.isEmpty()) list += h else errors += "headsets/$name: ${problems.joinToString()}"
                } catch (e: kotlinx.serialization.SerializationException) {
                    errors += "headsets/$name: ${e.message}"; LpLog.e(TAG, "headset profile rejected", e, "file" to name)
                }
            }
            list.also { headsets = it }
        }
    }

    /**
     * Picks the profile whose pattern matches the running phone; the catch-all `generic-android`
     * profile is used last so the app runs on any Android 10+ phone (flagged unqualified).
     */
    public suspend fun deviceProfileFor(buildModel: String): DeviceProfile? {
        val all = deviceProfiles()
        val specific = all.filter { it.id != GENERIC_ID }.firstOrNull { runCatching { Regex(it.modelPattern).containsMatchIn(buildModel) }.getOrDefault(false) }
        return specific ?: all.firstOrNull { it.id == GENERIC_ID }
    }

    public companion object {
        public const val GENERIC_ID: String = "generic-android"
        private const val TAG = "Config"
    }
}
